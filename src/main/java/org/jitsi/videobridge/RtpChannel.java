package org.jitsi.videobridge;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.media.rtp.RTPConnector;
import javax.media.rtp.SessionAddress;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.JingleUtils;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.eventadmin.EventAdmin;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.RTPConnectorInputStream;
import org.jitsi.impl.neomedia.SSRCFactoryImpl;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackReceiver;
import org.jitsi.impl.neomedia.rtp.TransportCCEngine;
import org.jitsi.impl.neomedia.transform.PacketTransformer;
import org.jitsi.impl.neomedia.transform.TransformEngine;
import org.jitsi.impl.neomedia.transform.zrtp.ZrtpRawPacket;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.neomedia.MediaDirection;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.RTPExtension;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.RetransmissionRequester;
import org.jitsi.service.neomedia.SSRCFactory;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.service.neomedia.recording.Recorder;
import org.jitsi.service.neomedia.recording.Synchronizer;
import org.jitsi.service.neomedia.stats.MediaStreamStats2;
import org.jitsi.service.neomedia.stats.SendTrackStats;
import org.jitsi.util.RTPUtils;
import org.jitsi.utils.DatagramPacketFilter;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.event.WeakReferencePropertyChangeListener;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.transform.RtpChannelTransformEngine;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jitsi.videobridge.xmpp.MediaStreamTrackFactory;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.RTPLevelRelayType;
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension;
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension;
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension;
import org.jivesoftware.smack.packet.IQ;
import org.jxmpp.jid.Jid;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

public class RtpChannel extends Channel implements PropertyChangeListener {
    private static final String DISABLE_ABS_SEND_TIME_PNAME = "org.jitsi.videobridge.DISABLE_ABS_SEND_TIME";

    private static final Logger classLogger = Logger.getLogger(RtpChannel.class);

    private static final long[] NO_RECEIVE_SSRCS = new long[0];

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final int MAX_RECEIVE_SSRCS = 50;

    protected final ConferenceSpeechActivity conferenceSpeechActivity;

    static RtpChannel getChannel(MediaStream stream) {
        return (RtpChannel)stream.getProperty(RtpChannel.class.getName());
    }

    private final RtpChannelDatagramFilter[] datagramFilters = new RtpChannelDatagramFilter[2];

    private final long initialLocalSSRC;

    private final PropertyChangeListener propertyChangeListener = (PropertyChangeListener)new WeakReferencePropertyChangeListener(this);

    private int[] receivePTs = new int[0];

    private long[] receiveSSRCs = NO_RECEIVE_SSRCS;

    private final Object receiveSSRCsSyncRoot = new Object();

    private RTPLevelRelayType rtpLevelRelayType;

    private MediaStream stream;

    private final Object streamSyncRoot = new Object();

    private boolean streamClosed = false;

    private final PropertyChangeListener streamPropertyChangeListener = new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent ev) {
            RtpChannel.this.streamPropertyChange(ev);
        }
    };

    private final SessionAddress streamTarget = new SessionAddress();

    RtpChannelTransformEngine transformEngine = null;

    private final Logger logger;

    private boolean verifyRemoteAddress = true;

    protected final Statistics statistics = new Statistics();

    private Set<Integer> signaledSSRCs = new HashSet<>();

    public RtpChannel(Content content, String id, String channelBundleId, String transportNamespace, Boolean initiator) {
        super(content, id, channelBundleId, transportNamespace, initiator);
        this
                .logger = Logger.getLogger(classLogger, content

                .getConference().getLogger());
        this.initialLocalSSRC = Videobridge.RANDOM.nextLong() & 0xFFFFFFFFL;
        Conference conference = content.getConference();
        conference.addPropertyChangeListener(this.propertyChangeListener);
        this.conferenceSpeechActivity = conference.getSpeechActivity();
        if (this.conferenceSpeechActivity != null)
            this.conferenceSpeechActivity.addPropertyChangeListener(this.propertyChangeListener);
        content.addPropertyChangeListener(this.propertyChangeListener);
        if ("urn:xmpp:jingle:transports:ice-udp:1".equals(this.transportNamespace))
            this.verifyRemoteAddress = false;
        touch();
    }

    protected boolean acceptControlInputStreamDatagramPacket(DatagramPacket p) {
        boolean accept;
        InetAddress ctrlAddr = this.streamTarget.getControlAddress();
        int ctrlPort = this.streamTarget.getControlPort();
        if (ctrlAddr == null) {
            this.streamTarget.setControlHostAddress(p.getAddress());
            this.streamTarget.setControlPort(p.getPort());
            InetAddress dataAddr = this.streamTarget.getDataAddress();
            int dataPort = this.streamTarget.getDataPort();
            if (dataAddr != null) {
                ctrlAddr = this.streamTarget.getControlAddress();
                ctrlPort = this.streamTarget.getControlPort();
                this.stream.setTarget(new MediaStreamTarget(dataAddr, dataPort, ctrlAddr, ctrlPort));
            }
            accept = true;
        } else {
            accept = (!this.verifyRemoteAddress || (ctrlAddr.equals(p.getAddress()) && ctrlPort == p.getPort()));
        }
        if (accept) {
            touch(Channel.ActivityType.PAYLOAD);
            if (p.getLength() > 8) {
                byte[] data = p.getData();
                int offset = p.getOffset();
                byte b0 = data[offset];
                if ((b0 & 0xC0) >>> 6 == 2 && (data[offset + 1] & 0xFF) == 203) {
                    int sc = b0 & 0x1F;
                    if (sc > 0) {
                        int ssrc = RTPUtils.readInt(data, offset + 4);
                        if (removeReceiveSSRC(ssrc))
                            notifyFocus();
                    }
                }
            }
        }
        return accept;
    }

    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p) {
        boolean accept;
        InetAddress dataAddr = this.streamTarget.getDataAddress();
        int dataPort = this.streamTarget.getDataPort();
        if (dataAddr == null) {
            MediaStreamTarget newStreamTarget;
            this.streamTarget.setDataHostAddress(p.getAddress());
            this.streamTarget.setDataPort(p.getPort());
            dataAddr = this.streamTarget.getDataAddress();
            dataPort = this.streamTarget.getDataPort();
            InetAddress ctrlAddr = this.streamTarget.getControlAddress();
            int ctrlPort = this.streamTarget.getControlPort();
            if (ctrlAddr == null) {
                newStreamTarget = new MediaStreamTarget(new InetSocketAddress(dataAddr, dataPort), null);
            } else {
                newStreamTarget = new MediaStreamTarget(dataAddr, dataPort, ctrlAddr, ctrlPort);
            }
            this.stream.setTarget(newStreamTarget);
            accept = true;
        } else {
            accept = (!this.verifyRemoteAddress || (dataAddr.equals(p.getAddress()) && dataPort == p.getPort()));
        }
        if (accept) {
            touch(Channel.ActivityType.PAYLOAD);
            if (p.getLength() >= 12) {
                byte[] data = p.getData();
                int off = p.getOffset();
                int v = (data[off] & 0xC0) >>> 6;
                if (v == 0) {
                    if ((data[off] & 0x10) == 16) {
                        byte[] zrtpMagicCookie = ZrtpRawPacket.ZRTP_MAGIC;
                        if (data[off + 4] == zrtpMagicCookie[0] && data[off + 5] == zrtpMagicCookie[1] && data[off + 6] == zrtpMagicCookie[2] && data[off + 7] == zrtpMagicCookie[3])
                            accept = false;
                    }
                } else if (v == 2) {
                    boolean notify;
                    int ssrc = RTPUtils.readInt(data, off + 8);
                    try {
                        notify = addReceiveSSRC(ssrc, true);
                    } catch (SizeExceededException see) {
                        accept = false;
                        notify = false;
                    }
                    if (notify && getContent().isRecording()) {
                        Recorder recorder = getContent().getRecorder();
                        if (recorder != null) {
                            AbstractEndpoint endpoint = getEndpoint();
                            if (endpoint != null) {
                                Synchronizer synchronizer = recorder.getSynchronizer();
                                if (synchronizer != null)
                                    synchronizer.setEndpoint(ssrc & 0xFFFFFFFFL, endpoint

                                            .getID());
                            }
                        }
                    }
                    if (RTPLevelRelayType.MIXER.equals(getRTPLevelRelayType())) {
                        Map<Byte, MediaFormat> payloadTypes = this.stream.getDynamicRTPPayloadTypes();
                        if (payloadTypes != null) {
                            int pt = data[off + 1] & Byte.MAX_VALUE;
                            MediaFormat format = payloadTypes.get(Byte.valueOf((byte)pt));
                            if (format != null &&
                                    !format.equals(this.stream.getFormat())) {
                                this.stream.setFormat(format);
                                synchronized (this.streamSyncRoot) {
                                    this.stream.setDirection(MediaDirection.SENDRECV);
                                }
                                notify = true;
                            }
                        }
                    }
                    if (notify)
                        notifyFocus();
                }
            }
        }
        return accept;
    }

    private boolean addReceiveSSRC(int receiveSSRC, boolean checkLimit) throws SizeExceededException {
        synchronized (this.receiveSSRCsSyncRoot) {
            long now = System.currentTimeMillis();
            int length = this.receiveSSRCs.length;
            for (int i = 0; i < length; i += 2) {
                if ((int)this.receiveSSRCs[i] == receiveSSRC) {
                    this.receiveSSRCs[i + 1] = now;
                    return false;
                }
            }
            if (checkLimit && length >= 25)
                throw new SizeExceededException();
            long[] newReceiveSSRCs = new long[length + 2];
            System.arraycopy(this.receiveSSRCs, 0, newReceiveSSRCs, 0, length);
            newReceiveSSRCs[length] = 0xFFFFFFFFL & receiveSSRC;
            newReceiveSSRCs[length + 1] = now;
            this.receiveSSRCs = newReceiveSSRCs;
            return true;
        }
    }

    protected void closeStream() {
        if (!this.streamClosed && this.stream != null) {
            MediaStreamStats2 mss = this.stream.getMediaStreamStats();
            this.statistics.bytesReceived = mss.getReceiveStats().getBytes();
            this.statistics.bytesSent = mss.getSendStats().getBytes();
            this.statistics.packetsReceived = mss.getReceiveStats().getPackets();
            this.statistics.packetsSent = mss.getSendStats().getPackets();
            this.stream.setProperty(RtpChannel.class.getName(), null);
            removeStreamListeners();
            this.stream.close();
            this.streamClosed = true;
        }
    }

    public void describe(ColibriConferenceIQ.ChannelCommon commonIq) {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel)commonIq;
        iq.setRTPLevelRelayType(getRTPLevelRelayType());
        super.describe((ColibriConferenceIQ.ChannelCommon)iq);
        iq.setDirection(
                (this.stream.getDirection() != null) ? this.stream
                        .getDirection().toString() : null);
        iq.setLastN(null);
        long initialLocalSSRC = getInitialLocalSSRC();
        if (initialLocalSSRC != -1L) {
            SourcePacketExtension source = new SourcePacketExtension();
            source.setSSRC(initialLocalSSRC);
            iq.addSource(source);
        }
        iq.setSSRCs(getReceiveSSRCs());
    }

    protected void dominantSpeakerChanged() {}

    public RtpChannelDatagramFilter getDatagramFilter(boolean rtcp) {
        RtpChannelDatagramFilter datagramFilter;
        int index = rtcp ? 1 : 0;
        synchronized (this.datagramFilters) {
            datagramFilter = this.datagramFilters[index];
            if (datagramFilter == null)
                this.datagramFilters[index] = datagramFilter = new RtpChannelDatagramFilter(this, rtcp);
        }
        return datagramFilter;
    }

    private long getInitialLocalSSRC() {
        switch (getRTPLevelRelayType()) {
            case MIXER:
                return this.initialLocalSSRC;
            case TRANSLATOR:
                return getContent().getInitialLocalSSRC();
        }
        return -1L;
    }

    private MediaService getMediaService() {
        return getContent().getMediaService();
    }

    public int[] getDefaultReceiveSSRCs() {
        return EMPTY_INT_ARRAY;
    }

    public int[] getReceiveSSRCs() {
        long[] receiveSSRCsField = this.receiveSSRCs;
        int length = receiveSSRCsField.length;
        if (length == 0)
            return ColibriConferenceIQ.NO_SSRCS;
        int[] receiveSSRCs = new int[length / 2];
        for (int src = 0, dst = 0; src < length; src += 2, dst++)
            receiveSSRCs[dst] = (int)receiveSSRCsField[src];
        return receiveSSRCs;
    }

    public RTPLevelRelayType getRTPLevelRelayType() {
        MediaType mediaType = getContent().getMediaType();
        if (this.rtpLevelRelayType == null){
            if(MediaType.AUDIO.equals(mediaType)){//音频使用混音
                logger.info("===========音频使用混音===========");
                setRTPLevelRelayType(RTPLevelRelayType.MIXER);
            }else {
                logger.info("===========其他使用默认==============");
                setRTPLevelRelayType(RTPLevelRelayType.TRANSLATOR);
            }
        }

        return this.rtpLevelRelayType;
    }

    public MediaStream getStream() {
        return this.stream;
    }

    public SessionAddress getStreamTarget() {
        return this.streamTarget;
    }

    public void initialize() throws IOException {
        initialize((RTPLevelRelayType)null);
    }

    void initialize(RTPLevelRelayType rtpLevelRelayType) throws IOException {
        super.initialize();
        MediaService mediaService = getMediaService();
        MediaType mediaType = getContent().getMediaType();
        synchronized (this.streamSyncRoot) {
            TransportManager transportManager = getTransportManager();
            TransportCCEngine transportCCEngine = transportManager.getTransportCCEngine();
            this
                    .stream = mediaService.createMediaStream(null, mediaType,

                    getSrtpControl());
            this.stream.addPropertyChangeListener(this.streamPropertyChangeListener);
            this.stream.setName(getID());
            this.stream.setProperty(RtpChannel.class.getName(), this);
            if (this.stream instanceof MediaStreamImpl) {
                MediaStreamImpl streamImpl = (MediaStreamImpl)this.stream;
                DiagnosticContext diagnosticContext = streamImpl.getDiagnosticContext();
                getContent().getConference()
                        .appendDiagnosticInformation(diagnosticContext);
            }
            if (this.transformEngine != null)
                this.stream.setExternalTransformer((TransformEngine)this.transformEngine);
            if (transportCCEngine != null)
                this.stream.setTransportCCEngine(transportCCEngine);
            this.logger.info(Logger.Category.STATISTICS, "create_stream," +
                    getLoggingId());
            if (rtpLevelRelayType != null)
                setRTPLevelRelayType(rtpLevelRelayType);
            if (transportManager.isConnected())
                transportConnected();
        }
    }

    private Iterable<TransformChainManipulator> getTransformChainManipulators() {
        try {
            ArrayList<TransformChainManipulator> manipulators = new ArrayList<>();
            BundleContext bundleContext = getBundleContext();
            Collection<ServiceReference<TransformChainManipulator>> serviceReferences = bundleContext.getServiceReferences(TransformChainManipulator.class, null);
            for (ServiceReference<TransformChainManipulator> serviceReference : serviceReferences) {
                TransformChainManipulator transformChainManipulators = (TransformChainManipulator)bundleContext.getService(serviceReference);
                if (transformChainManipulators != null)
                    manipulators.add(transformChainManipulators);
            }
            return manipulators;
        } catch (InvalidSyntaxException e) {
            this.logger.warn("Cannot fetch TransformChainManipulators", (Throwable)e);
            return null;
        }
    }

    RtpChannelTransformEngine initializeTransformerEngine() {
        Iterable<TransformChainManipulator> manipulators = getTransformChainManipulators();
        this.transformEngine = new RtpChannelTransformEngine(this, manipulators);
        if (this.stream != null)
            this.stream.setExternalTransformer((TransformEngine)this.transformEngine);
        return this.transformEngine;
    }

    protected void configureStream(MediaStream stream) {
        RetransmissionRequester retransmissionRequester = stream.getRetransmissionRequester();
        if (retransmissionRequester != null)
            retransmissionRequester
                    .setSenderSsrc(getContent().getInitialLocalSSRC());
    }

    protected void maybeStartStream() throws IOException {
        synchronized (this.streamSyncRoot) {
            if (this.stream == null)
                return;
        }
        configureStream(this.stream);
        MediaStreamTarget streamTarget = createStreamTarget();
        StreamConnector connector = getStreamConnector();
        if (connector == null) {
            this.logger.info("Not starting stream, connector is null");
            return;
        }
        if (streamTarget != null) {
            InetSocketAddress dataAddr = streamTarget.getDataAddress();
            if (dataAddr == null) {
                this.logger.info("Not starting stream, the target's data address is null");
                return;
            }
            this.streamTarget.setDataHostAddress(dataAddr.getAddress());
            this.streamTarget.setDataPort(dataAddr.getPort());
            InetSocketAddress ctrlAddr = streamTarget.getControlAddress();
            if (ctrlAddr != null) {
                this.streamTarget.setControlHostAddress(ctrlAddr.getAddress());
                this.streamTarget.setControlPort(ctrlAddr.getPort());
            }
            this.stream.setTarget(streamTarget);
        }
        this.stream.setConnector(connector);
        Content content = getContent();
        Conference conference = content.getConference();
        if (!this.stream.isStarted()) {
            if (RTPLevelRelayType.MIXER.equals(getRTPLevelRelayType()))
                this.stream.setSSRCFactory((SSRCFactory)new SSRCFactoryImpl(this.initialLocalSSRC));
            synchronized (this.streamSyncRoot) {
                this.stream.start();
            }
            EventAdmin eventAdmin = conference.getEventAdmin();
            if (eventAdmin != null)
                eventAdmin.sendEvent(EventFactory.streamStarted(this));
        }
        if (this.logger.isTraceEnabled())
            this.logger.debug(Logger.Category.STATISTICS, "ch_direction," +
                    getLoggingId() + " direction=" + this.stream
                    .getDirection());
    }

    private void notifyFocus() {
        Content content = getContent();
        Conference conference = content.getConference();
        Jid focus = conference.getFocus();
        if (focus == null)
            return;
        Collection<ComponentImpl> components = conference.getVideobridge().getComponents();
        if (!components.isEmpty())
            try {
                ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();
                conference.describeShallow(conferenceIQ);
                ColibriConferenceIQ.Content contentIQ = conferenceIQ.getOrCreateContent(content.getName());
                ColibriConferenceIQ.Channel channelIQ = new ColibriConferenceIQ.Channel();
                describe((ColibriConferenceIQ.ChannelCommon)channelIQ);
                contentIQ.addChannel(channelIQ);
                conferenceIQ.setTo(focus);
                conferenceIQ.setType(IQ.Type.set);
                for (ComponentImpl component : components)
                    component.send((IQ)conferenceIQ);
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                } else if (t instanceof ThreadDeath) {
                    throw (ThreadDeath)t;
                }
            }
    }

    protected void onEndpointChanged(AbstractEndpoint oldValue, AbstractEndpoint newValue) {
        super.onEndpointChanged(oldValue, newValue);
        if (oldValue != null) {
            oldValue.removeChannel(this);
            oldValue.removePropertyChangeListener(this);
        }
        if (newValue != null) {
            newValue.addChannel(this);
            newValue.addPropertyChangeListener(this);
        }
    }

    public void propertyChange(PropertyChangeEvent ev) {
        Object source = ev.getSource();
        if (this.conferenceSpeechActivity == source && this.conferenceSpeechActivity != null) {
            String propertyName = ev.getPropertyName();
            if (ConferenceSpeechActivity.DOMINANT_ENDPOINT_PROPERTY_NAME
                    .equals(propertyName))
                dominantSpeakerChanged();
        }
    }

    private boolean removeReceiveSSRC(int receiveSSRC) {
        boolean removed = false;
        synchronized (this.receiveSSRCsSyncRoot) {
            int length = this.receiveSSRCs.length;
            if (length == 2) {
                if ((int)this.receiveSSRCs[0] == receiveSSRC) {
                    this.receiveSSRCs = NO_RECEIVE_SSRCS;
                    removed = true;
                }
            } else {
                for (int i = 0; i < length; i += 2) {
                    if ((int)this.receiveSSRCs[i] == receiveSSRC) {
                        long[] newReceiveSSRCs = new long[length - 2];
                        if (i != 0)
                            System.arraycopy(this.receiveSSRCs, 0, newReceiveSSRCs, 0, i);
                        if (i != newReceiveSSRCs.length)
                            System.arraycopy(this.receiveSSRCs, i + 2, newReceiveSSRCs, i, newReceiveSSRCs.length - i);
                        this.receiveSSRCs = newReceiveSSRCs;
                        removed = true;
                        break;
                    }
                }
            }
        }
        return removed;
    }

    protected void removeStreamListeners() {
        try {
            this.stream.removePropertyChangeListener(this.streamPropertyChangeListener);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            } else if (t instanceof ThreadDeath) {
                throw (ThreadDeath)t;
            }
        }
    }

    protected void rtpLevelRelayTypeChanged(RTPLevelRelayType oldValue, RTPLevelRelayType newValue) {}

    boolean rtpTranslatorWillWrite(boolean data, RawPacket pkt, RtpChannel source) {
        return true;
    }

    void endpointMessageTransportConnected() {}

    public void setDirection(MediaDirection direction) {
        if (this.streamTarget.getDataAddress() != null)
            this.stream.setDirection(direction);
        touch();
    }

    public void setLastN(int lastN) {}

    public void setPayloadTypes(List<PayloadTypePacketExtension> payloadTypes) {
        if (payloadTypes != null && !payloadTypes.isEmpty()) {
            MediaService mediaService = getMediaService();
            if (mediaService != null) {
                int payloadTypeCount = payloadTypes.size();
                this.receivePTs = new int[payloadTypeCount];
                this.stream.clearDynamicRTPPayloadTypes();
                for (int i = 0; i < payloadTypeCount; i++) {
                    PayloadTypePacketExtension payloadType = payloadTypes.get(i);
                    this.receivePTs[i] = payloadType.getID();
                    MediaFormat mediaFormat = JingleUtils.payloadTypeToMediaFormat(payloadType, mediaService, null);
                    if (mediaFormat != null)
                        this.stream.addDynamicRTPPayloadType(
                                (byte)payloadType.getID(), mediaFormat);
                }
                TransportManager transportManager = getTransportManager();
                if (transportManager != null)
                    transportManager.payloadTypesChanged(this);
            }
        }
        touch();
    }

    public void setRtpHeaderExtensions(Collection<RTPHdrExtPacketExtension> rtpHeaderExtensions) {
        if (rtpHeaderExtensions != null && rtpHeaderExtensions.size() > 0) {
            this.stream.clearRTPExtensions();
            for (RTPHdrExtPacketExtension rtpHdrExtPacketExtension : rtpHeaderExtensions)
                addRtpHeaderExtension(rtpHdrExtPacketExtension);
        }
    }

    protected void addRtpHeaderExtension(RTPHdrExtPacketExtension rtpHdrExtPacketExtension) {
        URI uri = rtpHdrExtPacketExtension.getURI();
        if (uri == null) {
            this.logger.warn("Failed to add an RTP header extension with an invalid URI: " + rtpHdrExtPacketExtension

                    .getAttribute("uri"));
            return;
        }
        Byte id = Byte.valueOf(rtpHdrExtPacketExtension.getID());
        if (id == null) {
            this.logger.warn("Failed to add an RTP header extension with an invalid ID: " + rtpHdrExtPacketExtension

                    .getID());
            return;
        }
        if ("http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time".equals(uri.toString())) {
            ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(
                    getBundleContext(), ConfigurationService.class);
            if (cfg != null && cfg
                    .getBoolean("org.jitsi.videobridge.DISABLE_ABS_SEND_TIME", false))
                return;
        }
        MediaStream stream = getStream();
        if (stream != null)
            stream.addRTPExtension(id.byteValue(), new RTPExtension(uri));
    }

    public void setRTPLevelRelayType(RTPLevelRelayType rtpLevelRelayType) {
        if (rtpLevelRelayType == null)
            throw new NullPointerException("rtpLevelRelayType");
        if (this.rtpLevelRelayType == null) {
            MediaDevice device;
            RTPLevelRelayType oldValue = null;
            this.rtpLevelRelayType = rtpLevelRelayType;
            RTPLevelRelayType newValue = getRTPLevelRelayType();
            switch (newValue) {
                case MIXER:
                    device = getContent().getMixer();
                    this.stream.setDevice(device);
                    if (this.stream.getFormat() == null)
                        this.stream.setDirection(MediaDirection.RECVONLY);
                    break;
                case TRANSLATOR:
                    this.stream.setRTPTranslator(getContent().getRTPTranslator());
                    break;
                default:
                    throw new IllegalStateException("rtpLevelRelayType");
            }
            rtpLevelRelayTypeChanged(oldValue, newValue);
        } else if (!this.rtpLevelRelayType.equals(rtpLevelRelayType)) {

        }
        touch();
    }

    void speechActivityEndpointsChanged(List<AbstractEndpoint> endpoints) {}

    private void streamPropertyChange(PropertyChangeEvent ev) {
        String propertyName = ev.getPropertyName();
        String prefix = MediaStreamImpl.class.getName() + ".rtpConnector";
        if (propertyName.startsWith(prefix)) {
            String rtpConnectorPropertyName = propertyName.substring(prefix.length());
            Object newValue = ev.getNewValue();
            if (rtpConnectorPropertyName.equals("")) {
                if (newValue instanceof RTPConnector)
                    streamRTPConnectorChanged((RTPConnector)ev
                            .getOldValue(), (RTPConnector)newValue);
            } else if (newValue instanceof RTPConnectorInputStream) {
                DatagramPacketFilter datagramPacketFilter;
                if (rtpConnectorPropertyName.equals(".controlInputStream")) {
                    datagramPacketFilter = new DatagramPacketFilter() {
                        public boolean accept(DatagramPacket p) {
                            return RtpChannel.this
                                    .acceptControlInputStreamDatagramPacket(p);
                        }
                    };
                } else if (rtpConnectorPropertyName.equals(".dataInputStream")) {
                    datagramPacketFilter = new DatagramPacketFilter() {
                        public boolean accept(DatagramPacket p) {
                            return RtpChannel.this.acceptDataInputStreamDatagramPacket(p);
                        }
                    };
                } else {
                    datagramPacketFilter = null;
                }
                if (datagramPacketFilter != null)
                    ((RTPConnectorInputStream)newValue)
                            .addDatagramPacketFilter(datagramPacketFilter);
            }
        }
    }

    protected void streamRTPConnectorChanged(RTPConnector oldValue, RTPConnector newValue) {}

    public void setSources(List<SourcePacketExtension> sources) {
        if (sources == null || sources.isEmpty())
            return;
        synchronized (this.receiveSSRCsSyncRoot) {
            Set<Integer> oldSignaledSSRCs = new HashSet<>(this.signaledSSRCs);
            Set<Integer> newSignaledSSRCs = new HashSet<>();
            for (SourcePacketExtension source : sources) {
                long ssrc = source.getSSRC();
                if (ssrc != -1L)
                    newSignaledSSRCs.add(Integer.valueOf((int)ssrc));
            }
            Set<Integer> addedSSRCs = new HashSet<>(newSignaledSSRCs);
            addedSSRCs.removeAll(oldSignaledSSRCs);
            if (!addedSSRCs.isEmpty()) {
                Recorder recorder = null;
                Synchronizer synchronizer = null;
                AbstractEndpoint endpoint = null;
                if (getContent().isRecording()) {
                    recorder = getContent().getRecorder();
                    synchronizer = recorder.getSynchronizer();
                    endpoint = getEndpoint();
                }
                for (Integer addedSSRC : addedSSRCs) {
                    try {
                        addReceiveSSRC(addedSSRC.intValue(), false);
                        if (recorder != null && endpoint != null && synchronizer != null)
                            synchronizer.setEndpoint(addedSSRC
                                    .intValue() & 0xFFFFFFFFL, endpoint
                                    .getID());
                    } catch (SizeExceededException see) {
                        this.logger.error("An unexpected exception occurred.", see);
                    }
                }
            }
            oldSignaledSSRCs.removeAll(newSignaledSSRCs);
            if (!oldSignaledSSRCs.isEmpty())
                for (Integer removedSSRC : oldSignaledSSRCs)
                    removeReceiveSSRC(removedSSRC.intValue());
            this.signaledSSRCs = newSignaledSSRCs;
        }
        touch();
    }

    public void setSourceGroups(List<SourceGroupPacketExtension> sourceGroups) {}

    public int[] getReceivePTs() {
        return this.receivePTs;
    }

    public boolean expire() {
        if (!super.expire())
            return false;
        updateStatisticsOnExpire();
        RtpChannelTransformEngine rtpChannelTransformEngine = this.transformEngine;
        if (rtpChannelTransformEngine != null) {
            PacketTransformer t = rtpChannelTransformEngine.getRTPTransformer();
            if (t != null)
                t.close();
            t = rtpChannelTransformEngine.getRTCPTransformer();
            if (t != null)
                t.close();
        }
        return true;
    }

    private void updateStatisticsOnExpire() {
        Conference conference = getContent().getConference();
        if (conference != null && conference.includeInStatistics()) {
            Conference.Statistics conferenceStatistics = conference.getStatistics();
            conferenceStatistics.totalChannels.incrementAndGet();
            long lastPayloadActivityTime = getLastPayloadActivityTime();
            long lastTransportActivityTime = getLastTransportActivityTime();
            if (lastTransportActivityTime == 0L)
                conferenceStatistics.totalNoTransportChannels.incrementAndGet();
            if (lastPayloadActivityTime == 0L)
                conferenceStatistics.totalNoPayloadChannels.incrementAndGet();
            updatePacketsAndBytes(conferenceStatistics);
            MediaStream stream = this.stream;
            SendTrackStats sendTrackStats;
            if (stream != null && (
                    sendTrackStats = stream.getMediaStreamStats().getSendStats()) != null)
                this.logger.info(Logger.Category.STATISTICS, "expire_ch_stats," +
                        getLoggingId() + " bRecv=" + this.statistics.bytesReceived + ",bSent=" + this.statistics.bytesSent + ",pRecv=" + this.statistics.packetsReceived + ",pSent=" + this.statistics.packetsSent + ",bRetr=" + sendTrackStats

                        .getBytesRetransmitted() + ",bNotRetr=" + sendTrackStats

                        .getBytesNotRetransmitted() + ",pRetr=" + sendTrackStats

                        .getPacketsRetransmitted() + ",pNotRetr=" + sendTrackStats

                        .getPacketsNotRetransmitted() + ",pMiss=" + sendTrackStats

                        .getPacketsMissingFromCache());
        }
    }

    protected void updatePacketsAndBytes(Conference.Statistics conferenceStatistics) {
        if (conferenceStatistics != null) {
            conferenceStatistics.totalBytesReceived
                    .addAndGet(this.statistics.bytesReceived);
            conferenceStatistics.totalBytesSent
                    .addAndGet(this.statistics.bytesSent);
            conferenceStatistics.totalPacketsReceived
                    .addAndGet(this.statistics.packetsReceived);
            conferenceStatistics.totalPacketsSent
                    .addAndGet(this.statistics.packetsSent);
        }
    }

    public ConferenceSpeechActivity getConferenceSpeechActivity() {
        return this.conferenceSpeechActivity;
    }

    public boolean setPacketDelay(int packetDelay) {
        RtpChannelTransformEngine engine = this.transformEngine;
        if (engine == null)
            engine = initializeTransformerEngine();
        return engine.setPacketDelay(packetDelay);
    }

    public RtpChannelTransformEngine getTransformEngine() {
        return this.transformEngine;
    }

    public boolean setRtpEncodingParameters(List<SourcePacketExtension> sources, List<SourceGroupPacketExtension> sourceGroups) {
        boolean changed = false;
        if (sources == null)
            sources = Collections.emptyList();
        if (sourceGroups == null)
            sourceGroups = Collections.emptyList();
        if (sources.isEmpty() && sourceGroups.isEmpty())
            return false;
        setSources(sources);
        setSourceGroups(sourceGroups);
        MediaStreamTrackReceiver mediaStreamTrackReceiver = this.stream.getMediaStreamTrackReceiver();
        if (mediaStreamTrackReceiver != null) {
            MediaStreamTrackDesc[] newTracks = MediaStreamTrackFactory.createMediaStreamTracks(mediaStreamTrackReceiver, sources, sourceGroups);
            changed = mediaStreamTrackReceiver.setMediaStreamTracks(newTracks);
        }
        if (changed)
            getContent().getChannels().stream()
                    .filter(c -> (c != this && c instanceof RtpChannel))
                    .forEach(c -> ((RtpChannel)c).updateBitrateController());
        return changed;
    }

    protected void updateBitrateController() {}

    public String getLoggingId() {
        return getLoggingId(this);
    }

    public static String getLoggingId(RtpChannel rtpChannel) {
        String channelId = Channel.getLoggingId(rtpChannel);
        MediaStream stream = (rtpChannel == null) ? null : rtpChannel.getStream();
        return channelId + ",stream=" + ((stream == null) ? "null" :
                ""+Integer.valueOf(stream.hashCode()));
    }

    private static class SizeExceededException extends Exception {
        private SizeExceededException() {}
    }

    protected class Statistics {
        public long bytesSent = -1L;

        public long bytesReceived = -1L;

        public long packetsSent = -1L;

        public long packetsReceived = -1L;
    }
}
