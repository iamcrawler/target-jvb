package org.jitsi.videobridge;


import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import org.jitsi.impl.neomedia.VideoMediaStreamImpl;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackReceiver;
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc;
import org.jitsi.impl.neomedia.rtp.translator.RTCPFeedbackMessageSender;
import org.jitsi.impl.neomedia.rtp.translator.RTPTranslatorImpl;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.VideoMediaStream;
import org.jitsi.service.neomedia.rtp.BandwidthEstimator;
import org.jitsi.utils.ArrayUtils;
import org.jitsi.utils.concurrent.PeriodicRunnable;
import org.jitsi.utils.concurrent.RecurringRunnable;
import org.jitsi.utils.concurrent.RecurringRunnableExecutor;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.cc.BandwidthProbing;
import org.jitsi.videobridge.cc.BitrateController;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.RTPLevelRelayType;
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension;
import org.jitsi.xmpp.extensions.jingle.RtcpFbPacketExtension;

public class VideoChannel extends RtpChannel {
    public static final String DISABLE_LASTN_NOTIFICATIONS_PNAME = "org.jitsi.videobridge.DISABLE_LASTN_NOTIFICATIONS";

    public static final String DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS_PNAME = "org.jitsi.videobridge.DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS";

    @Deprecated
    public static final String DISABLE_NACK_TERMINATION_PNAME = "org.jitsi.videobridge.DISABLE_NACK_TERMINATION";

    static final String ENABLE_LIPSYNC_HACK_PNAME = VideoChannel.class
            .getName() + ".ENABLE_LIPSYNC_HACK";

    private static final String LOG_OVERSENDING_STATS_PNAME = "org.jitsi.videobridge.LOG_OVERSENDING_STATS";

    private static final ConfigurationService cfg = LibJitsi.getConfigurationService();

    public static final boolean DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS = cfg
            .getBoolean("org.jitsi.videobridge.DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS", false);

    private static final int[] DEFAULT_RTCP_RECV_REPORT_SSRCS = DISABLE_DEFAULT_RTCP_RECV_REPORT_SSRCS ? new int[0] : new int[2];

    private static final int MAX_FRAME_HEIGHT_DEFAULT = 2160;

    static {
        (new int[2])[0] = 1;
        (new int[2])[1] = 2;
    }

    private static final Logger classLogger = Logger.getLogger(VideoChannel.class);

    private static final Timer delayedFirTimer = new Timer();

    private static RecurringRunnableExecutor recurringExecutor;

    private final boolean disableLastNNotifications;

    private int maxFrameHeight = 2160;

    private static synchronized RecurringRunnableExecutor getRecurringExecutor() {
        if (recurringExecutor == null)
            recurringExecutor = new RecurringRunnableExecutor(VideoChannel.class.getSimpleName());
        return recurringExecutor;
    }

    private final BitrateController bitrateController = new BitrateController(this);

    private final BandwidthProbing bandwidthProbing = new BandwidthProbing(this);

    private final Logger logger;

    private TimerTask delayedFirTask;

    private final Object delayedFirTaskSyncRoot = new Object();

    private final RecurringRunnable logOversendingStatsRunnable;

    private int lastN = -1;

    VideoChannel(Content content, String id, String channelBundleId, String transportNamespace, Boolean initiator) {
        super(content, id, channelBundleId, transportNamespace, initiator);
        this
                .logger = Logger.getLogger(classLogger, content

                .getConference().getLogger());
        this
                .disableLastNNotifications = (cfg != null && cfg.getBoolean("org.jitsi.videobridge.DISABLE_LASTN_NOTIFICATIONS", false));
        initializeTransformerEngine();
        if (cfg != null && cfg.getBoolean("org.jitsi.videobridge.LOG_OVERSENDING_STATS", false)) {
            this.logOversendingStatsRunnable = createLogOversendingStatsRunnable();
            getRecurringExecutor().registerRecurringRunnable(this.logOversendingStatsRunnable);
        } else {
            this.logOversendingStatsRunnable = null;
        }
        getRecurringExecutor().registerRecurringRunnable((RecurringRunnable)this.bandwidthProbing);
    }

    protected void maybeStartStream() throws IOException {
        MediaStream stream = getStream();
        boolean previouslyStarted = (stream != null && stream.isStarted());
        super.maybeStartStream();
        stream = getStream();
        boolean currentlyStarted = (stream != null && stream.isStarted());
        if (currentlyStarted && !previouslyStarted)
            this.bitrateController.update(null, -1L);
    }

    protected void updateBitrateController() {
        this.bitrateController.update();
    }

    public int[] getDefaultReceiveSSRCs() {
        return DEFAULT_RTCP_RECV_REPORT_SSRCS;
    }

    void initialize(RTPLevelRelayType rtpLevelRelayType) throws IOException {
        super.initialize(rtpLevelRelayType);
        ((VideoMediaStream)getStream()).getOrCreateBandwidthEstimator()
                .addListener(this.bitrateController::update);
    }

    public BitrateController getBitrateController() {
        return this.bitrateController;
    }

    public void describe(ColibriConferenceIQ.ChannelCommon commonIq) {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel)commonIq;
        super.describe((ColibriConferenceIQ.ChannelCommon)iq);
        iq.setLastN(Integer.valueOf(this.lastN));
    }

    public int getLastN() {
        return this.lastN;
    }

    public void propertyChange(PropertyChangeEvent ev) {
        super.propertyChange(ev);
        String propertyName = ev.getPropertyName();
        if (Endpoint.PINNED_ENDPOINTS_PROPERTY_NAME.equals(propertyName) || Endpoint.SELECTED_ENDPOINTS_PROPERTY_NAME
                .equals(propertyName) || Conference.ENDPOINTS_PROPERTY_NAME
                .equals(propertyName))
            this.bitrateController.update();
    }

    boolean rtpTranslatorWillWrite(boolean data, RawPacket pkt, RtpChannel source) {
        if (!data)
            return true;
        return this.bitrateController.accept(pkt);
    }

    void endpointMessageTransportConnected() {
        super.endpointMessageTransportConnected();
        sendLastNEndpointsChangeEvent(this.bitrateController
                .getForwardedEndpoints(), (Collection<String>)null, (Collection<String>)null);
    }

    public boolean expire() {
        if (!super.expire())
            return false;
        synchronized (this.delayedFirTaskSyncRoot) {
            if (this.delayedFirTask != null) {
                this.delayedFirTask.cancel();
                this.delayedFirTask = null;
            }
        }
        if (recurringExecutor != null && this.logOversendingStatsRunnable != null)
            recurringExecutor
                    .deRegisterRecurringRunnable(this.logOversendingStatsRunnable);
        if (recurringExecutor != null)
            recurringExecutor
                    .deRegisterRecurringRunnable((RecurringRunnable)this.bandwidthProbing);
        MediaStream mediaStream = getStream();
        if (mediaStream instanceof VideoMediaStream) {
            BandwidthEstimator bwe = ((VideoMediaStream)mediaStream).getOrCreateBandwidthEstimator();
            if (bwe != null) {
                BandwidthEstimator.Statistics bweStats = bwe.getStatistics();
                if (bweStats != null) {
                    bweStats.update(System.currentTimeMillis());
                    Videobridge.Statistics videobridgeStats = getContent().getConference().getVideobridge().getStatistics();
                    long lossLimitedMs = bweStats.getLossLimitedMs();
                    long lossDegradedMs = bweStats.getLossDegradedMs();
                    long participantMs = bweStats.getLossFreeMs() + lossDegradedMs + lossLimitedMs;
                    videobridgeStats.totalLossControlledParticipantMs
                            .addAndGet(participantMs);
                    videobridgeStats.totalLossLimitedParticipantMs
                            .addAndGet(lossLimitedMs);
                    videobridgeStats.totalLossDegradedParticipantMs
                            .addAndGet(lossDegradedMs);
                }
            }
        }
        return true;
    }

    public void sendLastNEndpointsChangeEvent(Collection<String> forwardedEndpoints, Collection<String> endpointsEnteringLastN, Collection<String> conferenceEndpoints) {
        if (this.disableLastNNotifications)
            return;
        AbstractEndpoint thisEndpoint = getEndpoint();
        if (thisEndpoint == null)
            return;
        if (endpointsEnteringLastN == null)
            endpointsEnteringLastN = forwardedEndpoints;
        String msg = EndpointMessageBuilder.createLastNEndpointsChangeEvent(forwardedEndpoints, endpointsEnteringLastN, conferenceEndpoints);
        try {
            thisEndpoint.sendMessage(msg);
        } catch (IOException e) {
            this.logger.error("Failed to send message on data channel.", e);
        }
    }

    public void setLastN(int lastN) {
        if (this.lastN != lastN) {
            this.lastN = lastN;
            this.bitrateController.update();
        }
        touch();
    }

    void speechActivityEndpointsChanged(List<AbstractEndpoint> endpoints) {
        this.bitrateController.update(endpoints, -1L);
    }

    public void setPayloadTypes(List<PayloadTypePacketExtension> payloadTypes) {
        super.setPayloadTypes(payloadTypes);
        boolean enableRedFilter = true;
        boolean supportsFir = false;
        boolean supportsPli = false;
        boolean supportsRemb = false;
        if (payloadTypes == null || payloadTypes.isEmpty())
            return;
        for (PayloadTypePacketExtension payloadType : payloadTypes) {
            if ("red".equals(payloadType.getName()))
                enableRedFilter = false;
            for (RtcpFbPacketExtension rtcpFb : payloadType.getRtcpFeedbackTypeList()) {
                if ("ccm".equals(rtcpFb.getAttribute("type")) && "fir"
                        .equals(rtcpFb.getAttribute("subtype"))) {
                    supportsFir = true;
                    continue;
                }
                if ("nack".equals(rtcpFb.getAttribute("type")) && "pli"
                        .equals(rtcpFb.getAttribute("subtype"))) {
                    supportsPli = true;
                    continue;
                }
                if ("goog-remb".equals(rtcpFb.getAttribute("type")))
                    supportsRemb = true;
            }
        }
        if (this.transformEngine != null)
            this.transformEngine.enableREDFilter(enableRedFilter);
        MediaStream mediaStream = getStream();
        if (mediaStream instanceof VideoMediaStreamImpl) {
            ((VideoMediaStreamImpl)mediaStream).setSupportsFir(supportsFir);
            ((VideoMediaStreamImpl)mediaStream).setSupportsPli(supportsPli);
            ((VideoMediaStreamImpl)mediaStream).setSupportsRemb(supportsRemb);
        }
    }

    protected void dominantSpeakerChanged() {
        AbstractEndpoint dominantEndpoint = this.conferenceSpeechActivity.getDominantEndpoint();
        if (dominantEndpoint != null && dominantEndpoint.equals(getEndpoint())) {
            if (getContent().getChannelCount() < 3)
                return;
            long senderRtt = getRtt();
            long maxReceiverRtt = getMaxReceiverDelay();
            if (maxReceiverRtt > 0L && senderRtt > 0L) {
                long firDelay = maxReceiverRtt - senderRtt + 10L;
                if (this.logger.isInfoEnabled())
                    this.logger.info(Logger.Category.STATISTICS, "schedule_fir," +
                            getLoggingId() + " delay=" + firDelay);
                scheduleFir(firDelay);
            }
        } else {
            synchronized (this.delayedFirTaskSyncRoot) {
                if (this.delayedFirTask != null)
                    this.delayedFirTask.cancel();
            }
        }
    }

    private long getRtt() {
        long rtt = -1L;
        MediaStream stream = getStream();
        if (stream != null)
            rtt = stream.getMediaStreamStats().getReceiveStats().getRtt();
        return rtt;
    }

    public void setMaxFrameHeight(int maxFrameHeight) {
        this.maxFrameHeight = maxFrameHeight;
        this.bitrateController.update();
    }

    public int getMaxFrameHeight() {
        return this.maxFrameHeight;
    }

    private long getMaxReceiverDelay() {
        long maxRtt = -1L;
        for (Channel channel : getContent().getChannels()) {
            if (channel instanceof VideoChannel && !equals(channel)) {
                long rtt = ((VideoChannel)channel).getRtt();
                if (maxRtt < rtt)
                    maxRtt = rtt;
            }
        }
        return maxRtt;
    }

    private void scheduleFir(long delay) {
        TimerTask task = new TimerTask() {
            public void run() {
                if (VideoChannel.this.isExpired())
                    return;
                MediaStreamTrackReceiver receiver = VideoChannel.this.getStream().getMediaStreamTrackReceiver();
                if (receiver == null)
                    return;
                MediaStreamTrackDesc[] tracks = receiver.getMediaStreamTracks();
                if (ArrayUtils.isNullOrEmpty((Object[])tracks))
                    return;
                RTPEncodingDesc[] encodings = tracks[0].getRTPEncodings();
                if (ArrayUtils.isNullOrEmpty((Object[])encodings))
                    return;
                int ssrc = (int)encodings[encodings.length - 1].getPrimarySSRC();
                RTCPFeedbackMessageSender rtcpFeedbackMessageSender = ((RTPTranslatorImpl)VideoChannel.this.getContent().getRTPTranslator()).getRtcpFeedbackMessageSender();
                if (rtcpFeedbackMessageSender != null) {
                    if (VideoChannel.this.logger.isTraceEnabled())
                        VideoChannel.this.logger.trace("send_fir,stream=" + VideoChannel.this
                                .getStream().hashCode() + ",reason=scheduled");
                    rtcpFeedbackMessageSender
                            .requestKeyframe(ssrc & 0xFFFFFFFFL);
                }
            }
        };
        synchronized (this.delayedFirTaskSyncRoot) {
            if (this.delayedFirTask != null) {
                this.logger.warn("Canceling an existing delayed FIR task for endpoint " +
                        getEndpoint().getID() + ".");
                this.delayedFirTask.cancel();
            }
            this.delayedFirTask = task;
        }
        delayedFirTimer.schedule(task, Math.max(0L, delay));
    }

    private RecurringRunnable createLogOversendingStatsRunnable() {
        return (RecurringRunnable)new PeriodicRunnable(1000L) {
            private BandwidthEstimator bandwidthEstimator = null;

            public void run() {
                super.run();
                if (this.bandwidthEstimator == null) {
                    VideoMediaStream videoStream = (VideoMediaStream)VideoChannel.this.getStream();
                    if (videoStream != null)
                        this
                                .bandwidthEstimator = videoStream.getOrCreateBandwidthEstimator();
                }
                if (this.bandwidthEstimator == null)
                    return;
                long bwe = this.bandwidthEstimator.getLatestEstimate();
                if (bwe <= 0L)
                    return;
                long sendingBitrate = 0L;
                AbstractEndpoint endpoint = VideoChannel.this.getEndpoint();
                if (endpoint != null)
                    sendingBitrate = endpoint.getChannels().stream().mapToLong(channel -> channel.getStream().getMediaStreamStats().getSendStats().getBitrate()).sum();
                if (sendingBitrate <= 0L)
                    return;
                double lossRate = VideoChannel.this.getStream().getMediaStreamStats().getSendStats().getLossRate();
                if (VideoChannel.this.logger.isDebugEnabled())
                    VideoChannel.this.logger.debug(Logger.Category.STATISTICS, "sending_bitrate," + VideoChannel.this
                            .getLoggingId() + " bwe=" + bwe + ",sbr=" + sendingBitrate + ",loss=" + lossRate + ",remb=" + this.bandwidthEstimator

                            .getLatestREMB() + ",rrLoss=" + this.bandwidthEstimator

                            .getLatestFractionLoss());
            }
        };
    }
}
