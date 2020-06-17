package org.jitsi.videobridge.cc;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.VideoMediaStreamImpl;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.impl.neomedia.rtp.StreamRTPManager;
import org.jitsi.impl.neomedia.transform.RtxTransformer;
import org.jitsi.impl.neomedia.transform.TransformEngine;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.TransmissionFailedException;
import org.jitsi.utils.concurrent.PeriodicRunnable;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.Logger;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.videobridge.VideoChannel;

public class BandwidthProbing extends PeriodicRunnable {
    public static final String DISABLE_RTX_PROBING_PNAME = "org.jitsi.videobridge.DISABLE_RTX_PROBING";

    public static final String PADDING_PERIOD_MS_PNAME = "org.jitsi.videobridge.PADDING_PERIOD_MS";

    private static final Logger logger = Logger.getLogger(BandwidthProbing.class);

    private static final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(BandwidthProbing.class);

    private static final ConfigurationService cfg = LibJitsi.getConfigurationService();

    private static final long PADDING_PERIOD_MS = (cfg != null) ? cfg
            .getInt("org.jitsi.videobridge.PADDING_PERIOD_MS", 15) : 15L;

    private static final boolean DISABLE_RTX_PROBING = (cfg != null && cfg
            .getBoolean("org.jitsi.videobridge.DISABLE_RTX_PROBING", false));

    private final VideoChannel dest;

    private int vp8PT = -1;

    private int seqNum = (new Random()).nextInt(65535);

    private long ts = (new Random()).nextInt() & 0xFFFFFFFFL;

    public BandwidthProbing(VideoChannel dest) {
        super(PADDING_PERIOD_MS);
        this.dest = dest;
    }

    public void run() {
        super.run();
        MediaStream destStream = this.dest.getStream();
        if (destStream == null || (destStream
                .getDirection() != null &&
                !destStream.getDirection().allowsSending()) || !(destStream instanceof VideoMediaStreamImpl))
            return;
        VideoMediaStreamImpl videoStreamImpl = (VideoMediaStreamImpl)destStream;
        List<AdaptiveTrackProjection> adaptiveTrackProjectionList = this.dest.getBitrateController().getAdaptiveTrackProjections();
        if (adaptiveTrackProjectionList == null || adaptiveTrackProjectionList.isEmpty())
            return;
        long totalTargetBps = 0L, totalIdealBps = 0L;
        List<Long> ssrcsToProtect = new ArrayList<Long>();
        for (AdaptiveTrackProjection adaptiveTrackProjection : adaptiveTrackProjectionList) {
            MediaStreamTrackDesc sourceTrack = adaptiveTrackProjection.getSource();
            if (sourceTrack == null)
                continue;
            long targetBps = sourceTrack.getBps(adaptiveTrackProjection
                    .getTargetIndex());
            if (targetBps > 0L) {
                long ssrc = adaptiveTrackProjection.getSSRC();
                if (ssrc > -1L)
                    ssrcsToProtect.add(Long.valueOf(ssrc));
            }
            totalTargetBps += targetBps;
            totalIdealBps += sourceTrack.getBps(adaptiveTrackProjection
                    .getIdealIndex());
        }
        long totalNeededBps = totalIdealBps - totalTargetBps;
        if (totalNeededBps < 1L)
            return;
        long bweBps = videoStreamImpl.getOrCreateBandwidthEstimator().getLatestEstimate();
        if (totalIdealBps <= bweBps) {
            this.dest.getBitrateController().update(bweBps);
            return;
        }
        long maxPaddingBps = bweBps - totalTargetBps;
        long paddingBps = Math.min(totalNeededBps, maxPaddingBps);
        if (timeSeriesLogger.isTraceEnabled()) {
            DiagnosticContext diagnosticContext = videoStreamImpl.getDiagnosticContext();
            timeSeriesLogger.trace((Map)diagnosticContext
                    .makeTimeSeriesPoint("sent_padding")
                    .addField("padding_bps", Long.valueOf(paddingBps))
                    .addField("total_ideal_bps", Long.valueOf(totalIdealBps))
                    .addField("total_target_bps", Long.valueOf(totalTargetBps))
                    .addField("needed_bps", Long.valueOf(totalNeededBps))
                    .addField("max_padding_bps", Long.valueOf(maxPaddingBps))
                    .addField("bwe_bps", Long.valueOf(bweBps)));
        }
        if (paddingBps < 1L)
            return;
        MediaStreamImpl stream = (MediaStreamImpl)destStream;
        int bytes = (int)(PADDING_PERIOD_MS * paddingBps / 1000L / 8L);
        RtxTransformer rtxTransformer = stream.getRtxTransformer();
        if (!DISABLE_RTX_PROBING)
            if (!ssrcsToProtect.isEmpty())
                for (Long ssrc : ssrcsToProtect) {
                    bytes = rtxTransformer.sendPadding(ssrc.longValue(), bytes);
                    if (bytes < 1)
                        return;
                }
        long mediaSSRC = getSenderSSRC();
        if (this.vp8PT == -1) {
            this.vp8PT = stream.getDynamicRTPPayloadType("VP8");
            if (this.vp8PT == -1) {
                logger.warn("The VP8 payload type is undefined. Failed to probe with the SSRC of the bridge.");
                return;
            }
        }
        this.ts += 3000L;
        int pktLen = 267;
        int len = bytes / pktLen + 1;
        for (int i = 0; i < len; i++) {
            try {
                RawPacket pkt = RawPacket.makeRTP(mediaSSRC, this.vp8PT, this.seqNum++, this.ts, pktLen);
                stream.injectPacket(pkt, true, (TransformEngine)rtxTransformer);
            } catch (TransmissionFailedException tfe) {
                logger.warn("Failed to retransmit a packet.");
            }
        }
    }

    private long getSenderSSRC() {
        StreamRTPManager streamRTPManager = this.dest.getStream().getStreamRTPManager();
        if (streamRTPManager == null)
            return -1L;
        return this.dest.getStream().getStreamRTPManager().getLocalSSRC();
    }
}