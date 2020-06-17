package org.jitsi.videobridge;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.ice4j.socket.IceTcpSocketWrapper;
import org.ice4j.socket.IceUdpSocketWrapper;
import org.jitsi.impl.neomedia.transform.dtls.DtlsPacketTransformer;
import org.jitsi.impl.neomedia.transform.dtls.DtlsTransformEngine;
import org.jitsi.impl.osgi.framework.AsyncExecutor;
import org.jitsi.sctp4j.NetworkLink;
import org.jitsi.sctp4j.Sctp;
import org.jitsi.sctp4j.SctpDataCallback;
import org.jitsi.sctp4j.SctpNotification;
import org.jitsi.sctp4j.SctpSocket;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.SrtpControl;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.util.RawPacketQueue;
import org.jitsi.utils.concurrent.ExecutorUtils;
import org.jitsi.utils.logging.Logger;
import org.jitsi.utils.queue.PacketQueue;

public class SctpConnection extends Channel implements SctpDataCallback, SctpSocket.NotificationListener {
    private static int debugIdGen = -1;

    private static final int DTLS_BUFFER_SIZE = 2048;

    private static final boolean LOG_SCTP_PACKETS = false;

    private static final Logger classLogger = Logger.getLogger(SctpConnection.class);

    private static final int MSG_CHANNEL_ACK = 2;

    private static final byte[] MSG_CHANNEL_ACK_BYTES = new byte[] { 2 };

    private static final int MSG_OPEN_CHANNEL = 3;

    private static final int SCTP_BUFFER_SIZE = 2035;

    private static final ExecutorService threadPool = ExecutorUtils.newCachedThreadPool(true, SctpConnection.class

            .getName());

    static final int WEB_RTC_PPID_BIN = 53;

    static final int WEB_RTC_PPID_CTRL = 50;

    static final int WEB_RTC_PPID_STRING = 51;

    private static final String WEBRTC_DATA_CHANNEL_PROTOCOL = "http://jitsi.org/protocols/colibri";

    private boolean acceptedIncomingConnection;

    private boolean assocIsUp;

    private static synchronized int generateDebugId() {
        debugIdGen += 2;
        return debugIdGen;
    }

    private final Map<Integer, WebRtcDataStream> channels = new HashMap<>();

    private final int debugId;

    private final AsyncExecutor<Runnable> sctpDispatcher = new AsyncExecutor(15L, TimeUnit.MILLISECONDS);

    private final List<WebRtcDataStreamListener> listeners = new ArrayList<>();

    private final Object isReadyWaitLock = new Object();

    private final int remoteSctpPort;

    private SctpSocket sctpSocket;

    private boolean started;

    private final Object syncRoot = new Object();

    private final RawPacketQueue packetQueue;

    private DtlsPacketTransformer transformer = null;

    private final Handler handler = new Handler();

    private final Logger logger;

    public SctpConnection(String id, Content content, AbstractEndpoint endpoint, int remoteSctpPort, String channelBundleId, Boolean initiator) {
        super(content, id, channelBundleId, "urn:xmpp:jingle:transports:ice-udp:1", initiator);
        this
                .logger = Logger.getLogger(classLogger, content.getConference().getLogger());
        setEndpoint(endpoint);
        this

                .packetQueue = new RawPacketQueue(false, getClass().getSimpleName() + "-" + endpoint.getID(), this.handler);
        this.remoteSctpPort = remoteSctpPort;
        this.debugId = generateDebugId();
    }

    public void addChannelListener(WebRtcDataStreamListener listener) {
        if (listener == null)
            throw new NullPointerException("listener");
        synchronized (this.listeners) {
            if (!this.listeners.contains(listener))
                this.listeners.add(listener);
        }
    }

    protected void closeStream() {
        synchronized (this.syncRoot) {
            this.assocIsUp = false;
            this.acceptedIncomingConnection = false;
            this.packetQueue.close();
            if (this.sctpSocket != null) {
                this.sctpSocket.close();
                this.sctpSocket = null;
            }
        }
    }

    protected TransportManager createTransportManager(String xmlNamespace) throws IOException {
        if ("urn:xmpp:jingle:transports:ice-udp:1".equals(xmlNamespace)) {
            Content content = getContent();
            return new IceUdpTransportManager(content

                    .getConference(),
                    isInitiator(), 1, content

                    .getName());
        }
        if ("urn:xmpp:jingle:transports:raw-udp:1".equals(xmlNamespace))
            throw new IllegalArgumentException("Unsupported Jingle transport " + xmlNamespace);
        throw new IllegalArgumentException("Unsupported Jingle transport " + xmlNamespace);
    }

    public boolean expire() {
        if (!super.expire())
            return false;
        this.sctpDispatcher.shutdown();
        return true;
    }

    public void forEachDataStream(Consumer<WebRtcDataStream> action) {
        ArrayList<WebRtcDataStream> streams;
        synchronized (this.syncRoot) {
            streams = new ArrayList<>(this.channels.values());
        }
        streams.forEach(action);
    }

    private WebRtcDataStreamListener[] getChannelListeners() {
        WebRtcDataStreamListener[] ls;
        synchronized (this.listeners) {
            if (this.listeners.isEmpty()) {
                ls = null;
            } else {
                ls = this.listeners.<WebRtcDataStreamListener>toArray(
                        new WebRtcDataStreamListener[this.listeners.size()]);
            }
        }
        return ls;
    }

    public WebRtcDataStream getDefaultDataStream() {
        synchronized (this.syncRoot) {
            if (this.sctpSocket != null) {
                WebRtcDataStream highestClientSid = this.channels.values().stream().filter(s -> isInitiator() ? ((s.getSid() % 2 == 1)) : ((s.getSid() % 2 == 0))).max(Comparator.comparingInt(WebRtcDataStream::getSid)).orElse(null);
                if (highestClientSid != null)
                    return highestClientSid;
                return this.channels.values()
                        .stream()
                        .max(Comparator.comparingInt(WebRtcDataStream::getSid))
                        .orElse(null);
            }
            return null;
        }
    }

    public boolean isReady() {
        return (this.assocIsUp && this.acceptedIncomingConnection);
    }

    private void maybeOpenDefaultWebRTCDataChannel() {
        boolean openChannel;
        synchronized (this.syncRoot) {
            openChannel = (!isExpired() && this.sctpSocket != null && this.channels.size() == 0);
        }
        if (openChannel)
            openDefaultWebRTCDataChannel();
    }

    protected void maybeStartStream() {
        StreamConnector connector = getStreamConnector();
        if (connector == null)
            return;
        synchronized (this.syncRoot) {
            if (this.started)
                return;
            threadPool.execute(() -> {
                try {
                    Sctp.init();
                    runOnDtlsTransport(connector);
                } catch (IOException e) {
                    this.logger.error(e, e);
                } finally {
                    try {
                        Sctp.finish();
                    } catch (IOException e) {
                        this.logger.error("Failed to shutdown SCTP stack", e);
                    }
                }
            });
            this.started = true;
        }
    }

    private void notifyChannelOpened(WebRtcDataStream dataChannel) {
        if (!isExpired()) {
            WebRtcDataStreamListener[] ls = getChannelListeners();
            if (ls != null)
                for (WebRtcDataStreamListener l : ls)
                    l.onChannelOpened(this, dataChannel);
        }
    }

    private void onCtrlPacket(byte[] data, int sid) throws IOException {
        synchronized (this.syncRoot) {
            onCtrlPacketNotSynchronized(data, sid);
        }
    }

    private void onCtrlPacketNotSynchronized(byte[] data, int sid) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int messageType = 0xFF & buffer.get();
        if (messageType == 2) {
            if (this.logger.isDebugEnabled())
                this.logger.debug(Logger.Category.STATISTICS, "sctp_ack_received," +
                        getLoggingId() + " sid=" + sid);
            WebRtcDataStream channel = this.channels.get(Integer.valueOf(sid));
            if (channel != null) {
                if (!channel.isAcknowledged()) {
                    channel.ackReceived();
                    notifyChannelOpened(channel);
                } else {
                    this.logger.log(Level.WARNING, Logger.Category.STATISTICS, "sctp_redundant_ack_received," +
                            getLoggingId() + " sid=" + sid);
                }
            } else {
                this.logger.error(Logger.Category.STATISTICS, "sctp_no_channel_for_sid," +
                        getLoggingId() + " sid=" + sid);
            }
        } else if (messageType == 3) {
            String label, protocol;
            int channelType = 0xFF & buffer.get();
            int priority = 0xFFFF & buffer.getShort();
            long reliability = 0xFFFFFFFFL & buffer.getInt();
            int labelLength = 0xFFFF & buffer.getShort();
            int protocolLength = 0xFFFF & buffer.getShort();
            if (labelLength == 0) {
                label = "";
            } else {
                byte[] labelBytes = new byte[labelLength];
                buffer.get(labelBytes);
                label = new String(labelBytes, "UTF-8");
            }
            if (protocolLength == 0) {
                protocol = "";
            } else {
                byte[] protocolBytes = new byte[protocolLength];
                buffer.get(protocolBytes);
                protocol = new String(protocolBytes, "UTF-8");
            }
            if (this.logger.isDebugEnabled())
                this.logger.debug(Logger.Category.STATISTICS, "dc_open_request," +
                        getLoggingId() + " sid=" + sid + ",type=" + channelType + ",prio=" + priority + ",reliab=" + reliability + ",label=" + label + ",proto=" + protocol);
            WebRtcDataStream.DataCallback oldCallback = null;
            if (this.channels.containsKey(Integer.valueOf(sid))) {
                this.logger.log(Level.SEVERE, Logger.Category.STATISTICS, "sctp_channel_exists," +
                        getLoggingId() + " sid=" + sid);
                oldCallback = ((WebRtcDataStream)this.channels.get(Integer.valueOf(sid))).getDataCallback();
            }
            WebRtcDataStream newChannel = new WebRtcDataStream(this, this.sctpSocket, sid, label, true);
            this.channels.put(Integer.valueOf(sid), newChannel);
            if (oldCallback != null)
                newChannel.setDataCallback(oldCallback);
            sendOpenChannelAck(sid);
            notifyChannelOpened(newChannel);
        } else {
            this.logger.error("Unexpected ctrl msg type: " + messageType);
        }
    }

    protected void onEndpointChanged(AbstractEndpoint oldValue, AbstractEndpoint newValue) {
        super.onEndpointChanged(oldValue, newValue);
        if (oldValue != null && oldValue instanceof Endpoint)
            ((Endpoint)oldValue).setSctpConnection(null);
        if (newValue != null && newValue instanceof Endpoint)
            ((Endpoint)newValue).setSctpConnection(this);
    }

    public void onSctpNotification(SctpSocket socket, SctpNotification notification) {
        synchronized (this.syncRoot) {
            SctpNotification.AssociationChange assocChange;
            if (this.logger.isDebugEnabled())
                if (10 != notification.sn_type)
                    this.logger.info(Logger.Category.STATISTICS, "sctp_notification," +
                            getLoggingId() + " notification=" + notification);
            switch (notification.sn_type) {
                case 1:
                    assocChange = (SctpNotification.AssociationChange)notification;
                    switch (assocChange.state) {
                        case 1:
                            synchronized (this.isReadyWaitLock) {
                                if (!this.assocIsUp) {
                                    this.assocIsUp = true;
                                    this.isReadyWaitLock.notifyAll();
                                }
                            }
                            break;
                        case 2:
                        case 4:
                        case 5:
                            closeStream();
                            break;
                    }
                    break;
            }
        }
    }

    private void openDefaultWebRTCDataChannel() {
        try {
            int sid = isInitiator() ? 0 : 1;
            this.logger.debug(String.format("Will open default WebRTC data channel for: %s next SID: %d", new Object[] { getLoggingId(), Integer.valueOf(sid) }));
            openChannel(0, 0, 0L, sid, "default");
        } catch (IOException e) {
            this.logger.error(
                    String.format("Could open the default data stream for endpoint: %s", new Object[] { getLoggingId() }), e);
        }
    }

    public void onSctpPacket(byte[] data, int sid, int ssn, int tsn, long ppid, int context, int flags) {
        this.sctpDispatcher.execute(() -> {
            if (!isExpired() && this.sctpSocket != null)
                processSctpPacket(data, sid, ssn, tsn, ppid, context, flags);
        });
    }

    public WebRtcDataStream openChannel(int type, int prio, long reliab, int sid, String label) throws IOException {
        synchronized (this.syncRoot) {
            return openChannelNotSynchronized(type, prio, reliab, sid, label);
        }
    }

    private WebRtcDataStream openChannelNotSynchronized(int type, int prio, long reliab, int sid, String label) throws IOException {
        byte[] labelBytes;
        int labelByteLength;
        if (this.channels.containsKey(Integer.valueOf(sid)))
            throw new IOException("Channel on sid: " + sid + " already exists");
        if (label == null) {
            labelBytes = null;
            labelByteLength = 0;
        } else {
            labelBytes = label.getBytes("UTF-8");
            labelByteLength = Math.min(labelBytes.length, 65535);
        }
        String protocol = "http://jitsi.org/protocols/colibri";
        byte[] protocolBytes = protocol.getBytes("UTF-8");
        int protocolByteLength = Math.min(protocolBytes.length, 65535);
        ByteBuffer packet = ByteBuffer.allocate(12 + labelByteLength + protocolByteLength);
        packet.put((byte)3);
        packet.put((byte)type);
        packet.putShort((short)prio);
        packet.putInt((int)reliab);
        packet.putShort((short)labelByteLength);
        packet.putShort((short)protocolByteLength);
        if (labelByteLength != 0)
            packet.put(labelBytes, 0, labelByteLength);
        if (protocolByteLength != 0)
            packet.put(protocolBytes, 0, protocolByteLength);
        int sentCount = this.sctpSocket.send(packet.array(), true, sid, 50);
        if (sentCount != packet.capacity())
            throw new IOException("Failed to open new chanel on sid: " + sid);
        WebRtcDataStream channel = new WebRtcDataStream(this, this.sctpSocket, sid, label, false);
        this.channels.put(Integer.valueOf(sid), channel);
        return channel;
    }

    private void processSctpPacket(byte[] data, int sid, int ssn, int tsn, long ppid, int context, int flags) {
        if (ppid == 50L) {
            try {
                onCtrlPacket(data, sid);
            } catch (IOException e) {
                this.logger.error("IOException when processing ctrl packet", e);
            }
        } else if (ppid == 51L || ppid == 53L) {
            WebRtcDataStream channel;
            synchronized (this.syncRoot) {
                channel = this.channels.get(Integer.valueOf(sid));
            }
            if (channel == null) {
                this.logger.error("No channel found for sid: " + sid);
                return;
            }
            if (ppid == 51L) {
                String charsetName = "UTF-8";
                try {
                    String str = new String(data, charsetName);
                    channel.onStringMsg(str);
                } catch (UnsupportedEncodingException uee) {
                    this.logger.error("Unsupported charset encoding/name " + charsetName, uee);
                }
            } else {
                channel.onBinaryMsg(data);
            }
        } else {
            this.logger.warn("Got message on unsupported PPID: " + ppid);
        }
    }

    public void removeChannelListener(WebRtcDataStreamListener listener) {
        if (listener != null)
            synchronized (this.listeners) {
                this.listeners.remove(listener);
            }
    }

    private void runOnDtlsTransport(StreamConnector connector) throws IOException {
        IceTcpSocketWrapper iceTcpSocketWrapper = null;
        SrtpControl srtpControl = getTransportManager().getSrtpControl(this);
        DtlsTransformEngine engine = (DtlsTransformEngine)srtpControl.getTransformEngine();
        DtlsPacketTransformer transformer = (DtlsPacketTransformer)engine.getRTPTransformer();
        if (this.transformer == null)
            this.transformer = transformer;
        byte[] receiveBuffer = new byte[2035];
        synchronized (this.syncRoot) {
            this.sctpSocket = Sctp.createSocket(5000);
            this.assocIsUp = false;
            this.acceptedIncomingConnection = false;
        }
        this.sctpSocket.setLink(new NetworkLink() {
            public void onConnOut(SctpSocket s, byte[] packet) throws IOException {
                SctpConnection.this.packetQueue.add(packet, 0, packet.length);
            }
        });
        if (this.logger.isDebugEnabled())
            this.logger.debug("Connecting SCTP to port: " + this.remoteSctpPort + " to " +

                    getEndpoint().getID());
        this.sctpSocket.setNotificationListener(this);
        this.sctpSocket.listen();
        this.sctpSocket.setDataCallback(this);
        this.sctpDispatcher.execute(this::acceptIncomingSctpConnection);
        DatagramSocket datagramSocket = connector.getDataSocket();
        if (datagramSocket != null) {
            IceUdpSocketWrapper iceUdpSocketWrapper = new IceUdpSocketWrapper(datagramSocket);
        } else {
            iceTcpSocketWrapper = new IceTcpSocketWrapper(connector.getDataTCPSocket());
        }
        DatagramPacket recv = new DatagramPacket(receiveBuffer, 0, receiveBuffer.length);
        try {
            while (true) {
                iceTcpSocketWrapper.receive(recv);
                RawPacket[] send = { new RawPacket(recv.getData(), recv.getOffset(), recv.getLength()) };
                send = transformer.reverseTransform(send);
                if (send == null || send.length == 0)
                    continue;
                touch(Channel.ActivityType.PAYLOAD);
                if (this.sctpSocket == null)
                    break;
                for (RawPacket s : send) {
                    if (s != null)
                        this.sctpSocket.onConnIn(s
                                .getBuffer(), s.getOffset(), s.getLength());
                }
            }
        } catch (SocketException ex) {
            if (!"Socket closed".equals(ex.getMessage()) && !(ex instanceof org.ice4j.socket.SocketClosedException))
                throw ex;
        } finally {
            closeStream();
        }
    }

    private void acceptIncomingSctpConnection() {
        SctpSocket sctpSocket = null;
        try {
            sctpSocket = this.sctpSocket;
            while (sctpSocket != null) {
                if (sctpSocket.accept()) {
                    this.acceptedIncomingConnection = true;
                    this.logger.info(
                            String.format("SCTP socket accepted on %s", new Object[] { getLoggingId() }));
                    break;
                }
                Thread.sleep(100L);
                sctpSocket = this.sctpSocket;
            }
            synchronized (this.isReadyWaitLock) {
                while (sctpSocket != null && !isExpired() && !isReady()) {
                    this.isReadyWaitLock.wait();
                    sctpSocket = this.sctpSocket;
                }
                this.sctpDispatcher.execute(this::maybeOpenDefaultWebRTCDataChannel);
            }
        } catch (Exception e) {
            this.logger.error(
                    String.format("Error accepting SCTP connection %s", new Object[] { getLoggingId() }), e);
        }
        if (sctpSocket == null)
            this.logger.info(String.format("SctpConnection %s closed before SctpSocket accept()-ed.", new Object[] { getLoggingId() }));
    }

    private void sendOpenChannelAck(int sid) throws IOException {
        byte[] ack = MSG_CHANNEL_ACK_BYTES;
        if (this.sctpSocket.send(ack, true, sid, 50) != ack.length)
            this.logger.error("Failed to send open channel confirmation");
    }

    private class Handler implements PacketQueue.PacketHandler<RawPacket> {
        private Handler() {}

        public boolean handlePacket(RawPacket pkt) {
            if (pkt == null)
                return true;
            DtlsPacketTransformer transformer = SctpConnection.this.transformer;
            if (transformer == null) {
                SctpConnection.this.logger.error("Cannot send SCTP packet, DTLS transformer is null");
                return false;
            }
            transformer.sendApplicationData(pkt
                    .getBuffer(), pkt.getOffset(), pkt.getLength());
            return true;
        }
    }
}
