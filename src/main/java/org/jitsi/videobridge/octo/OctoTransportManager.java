package org.jitsi.videobridge.octo;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.java.sip.communicator.util.ServiceUtils;
import org.ice4j.socket.DatagramPacketFilter;
import org.ice4j.socket.DelegatingDatagramSocket;
import org.ice4j.socket.MultiplexingDatagramSocket;
import org.jitsi.impl.neomedia.transform.NullSrtpControl;
import org.jitsi.service.neomedia.DefaultStreamConnector;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.SrtpControl;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.StringUtils;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.Channel;
import org.jitsi.videobridge.TransportManager;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;

public class OctoTransportManager extends TransportManager {
    private static final Logger classLogger = Logger.getLogger(OctoTransportManager.class);

    public static final String NAMESPACE = "http://jitsi.org/octo";

    private static final int SO_TIMEOUT = 1000;

    private OctoChannel channel;

    private OctoRelay octoRelay;

    private DatagramSocket rtpSocket;

    private DatagramSocket rtcpSocket;

    private static SocketAddress relayIdToSocketAddress(String relayId) {
        if (relayId == null || !relayId.contains(":"))
            return null;
        try {
            String[] addressAndPort = relayId.split(":");
            return new InetSocketAddress(addressAndPort[0],
                    Integer.valueOf(addressAndPort[1]).intValue());
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private final SrtpControl srtpControl = (SrtpControl)new NullSrtpControl();

    private List<SocketAddress> remoteRelays;

    private final Logger logger;

    private final Object socketsSyncRoot = new Object();

    public OctoTransportManager(Channel channel) {
        if (!(channel instanceof OctoChannel))
            throw new IllegalArgumentException("channel is not an OctoChannel");
        this.channel = (OctoChannel)channel;
        this
                .logger = Logger.getLogger(classLogger, channel

                .getContent().getConference().getLogger());
        OctoRelayService relayService = (OctoRelayService)ServiceUtils.getService(channel
                .getBundleContext(), OctoRelayService.class);
        this.octoRelay = Objects.<OctoRelay>requireNonNull(relayService.getRelay());
    }

    public void close() {
        super.close();
        synchronized (this.socketsSyncRoot) {
            if (this.rtpSocket != null)
                this.rtpSocket.close();
            if (this.rtcpSocket != null)
                this.rtcpSocket.close();
        }
    }

    public StreamConnector getStreamConnector(Channel channel) {
        try {
            initializeSockets();
        } catch (IOException ioe) {
            this.logger.error("Failed to initialize sockets: ", ioe);
            return null;
        }
        return (StreamConnector)new DefaultStreamConnector(this.rtpSocket, this.rtcpSocket, true);
    }

    private void initializeSockets() throws IOException {
        synchronized (this.socketsSyncRoot) {
            if (this.rtpSocket != null)
                return;
            MultiplexingDatagramSocket relaySocket = this.octoRelay.getSocket();
            this
                    .rtpSocket = createOctoSocket((DatagramSocket)relaySocket
                    .getSocket((DatagramPacketFilter)this.channel.getDatagramFilter(false)));
            this
                    .rtcpSocket = createOctoSocket((DatagramSocket)relaySocket
                    .getSocket((DatagramPacketFilter)this.channel.getDatagramFilter(true)));
        }
    }

    private DatagramSocket createOctoSocket(DatagramSocket socket) throws SocketException {
        DelegatingDatagramSocket delegatingDatagramSocket = new DelegatingDatagramSocket(socket) {
            public void receive(DatagramPacket p) throws IOException {
                super.receive(p);
                try {
                    p.setData(p
                            .getData(), p
                            .getOffset() + 8, p
                            .getLength() - 8);
                } catch (Exception e) {
                    OctoTransportManager.this.logger.error("Failed to strip Octo header while receiving a packet:" + e);
                }
            }

            public void send(DatagramPacket p) throws IOException {
                OctoTransportManager.this.doSend(p, true);
            }
        };
        delegatingDatagramSocket.setSoTimeout(1000);
        return (DatagramSocket)delegatingDatagramSocket;
    }

    public MediaStreamTarget getStreamTarget(Channel channel) {
        MultiplexingDatagramSocket multiplexingDatagramSocket = this.octoRelay.getSocket();
        InetAddress inetAddress = multiplexingDatagramSocket.getLocalAddress();
        int port = multiplexingDatagramSocket.getLocalPort();
        return new MediaStreamTarget(inetAddress, port, inetAddress, port);
    }

    protected void describe(IceUdpTransportPacketExtension pe) {}

    public SrtpControl getSrtpControl(Channel channel) {
        return this.srtpControl;
    }

    public String getXmlNamespace() {
        return "http://jitsi.org/octo";
    }

    public void startConnectivityEstablishment(IceUdpTransportPacketExtension transport) {}

    public boolean isConnected() {
        return true;
    }

    public void setRelayIds(List<String> relayIds) {
        List<SocketAddress> remoteRelays = new ArrayList<>(relayIds.size());
        for (String relayId : relayIds) {
            SocketAddress socketAddress = relayIdToSocketAddress(relayId);
            if (socketAddress == null) {
                this.logger.error("Could not convert a relay ID to a socket address: " + relayId);
                continue;
            }
            if (remoteRelays.contains(socketAddress)) {
                this.logger.info("Relay ID duplicate: " + relayId);
                continue;
            }
            remoteRelays.add(socketAddress);
        }
        this.remoteRelays = remoteRelays;
    }

    private void doSend(DatagramPacket p, boolean addHeaders) throws IOException {
        if (addHeaders)
            p = addOctoHeaders(p);
        MultiplexingDatagramSocket multiplexingDatagramSocket = this.octoRelay.getSocket();
        IOException exception = null;
        int exceptions = 0;
        if (this.remoteRelays != null)
            for (SocketAddress remoteAddress : this.remoteRelays) {
                try {
                    p.setSocketAddress(remoteAddress);
                    multiplexingDatagramSocket.send(p);
                } catch (IOException ioe) {
                    exceptions++;
                    exception = ioe;
                }
            }
        if (exception != null) {
            this.logger.warn("Caught " + exceptions + " while trying to send a packet.");
            throw exception;
        }
    }

    private DatagramPacket addOctoHeaders(DatagramPacket p) {
        byte[] buf = p.getData();
        int off = p.getOffset();
        int len = p.getLength();
        if (off >= 8) {
            off -= 8;
        } else if (buf.length >= len + 8) {
            System.arraycopy(buf, off, buf, 8, len);
            off = 0;
        } else {
            byte[] newBuf = new byte[len + 8];
            System.arraycopy(buf, off, newBuf, 8, len);
            buf = newBuf;
            off = 0;
        }
        len += 8;
        p.setData(buf, off, len);
        OctoPacket.writeHeaders(buf, off, true, this.channel

                .getMediaType(), 0, this.channel

                .getConferenceId(), "ffffffff");
        return p;
    }

    void sendMessage(String msg, String sourceEndpointId, String conferenceId) {
        if (StringUtils.isNullOrEmpty(sourceEndpointId))
            sourceEndpointId = "ffffffff";
        if (this.logger.isDebugEnabled())
            this.logger.debug("Sending a message through Octo: " + msg);
        byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[msgBytes.length + 8];
        System.arraycopy(msgBytes, 0, buf, 8, msgBytes.length);
        OctoPacket.writeHeaders(buf, 0, true, MediaType.DATA, 0, conferenceId, sourceEndpointId);
        try {
            doSend(new DatagramPacket(buf, 0, buf.length), false);
        } catch (IOException ioe) {
            this.logger.error("Failed to send Octo data message: ", ioe);
        }
    }
}
