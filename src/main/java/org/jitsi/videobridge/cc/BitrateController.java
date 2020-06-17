package org.jitsi.videobridge.cc;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.RTCPPacketPredicate;
import org.jitsi.impl.neomedia.RTPPacketPredicate;
import org.jitsi.impl.neomedia.VideoMediaStreamImpl;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc;
import org.jitsi.impl.neomedia.transform.PacketTransformer;
import org.jitsi.impl.neomedia.transform.SinglePacketTransformerAdapter;
import org.jitsi.impl.neomedia.transform.TransformEngine;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.rtp.BandwidthEstimator;
import org.jitsi.utils.ArrayUtils;
import org.jitsi.utils.ByteArrayBuffer;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.Logger;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.VideoChannel;

public class BitrateController implements TransformEngine {
    public static final String BWE_CHANGE_THRESHOLD_PCT_PNAME = "org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT";

    public static final String THUMBNAIL_MAX_HEIGHT_PNAME = "org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT";

    public static final String ONSTAGE_PREFERRED_HEIGHT_PNAME = "org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT";

    public static final String ONSTAGE_PREFERRED_FRAME_RATE_PNAME = "org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE";

    public static final String ENABLE_ONSTAGE_VIDEO_SUSPEND_PNAME = "org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND";

    public static final String TRUST_BWE_PNAME = "org.jitsi.videobridge.TRUST_BWE";

    private static final RateSnapshot[] EMPTY_RATE_SNAPSHOT_ARRAY = new RateSnapshot[0];

    private static final int THUMBNAIL_MAX_HEIGHT_DEFAULT = 180;

    private static final int ONSTAGE_PREFERRED_HEIGHT_DEFAULT = 360;

    private static final double ONSTAGE_PREFERRED_FRAME_RATE_DEFAULT = 30.0D;

    private static final boolean ENABLE_ONSTAGE_VIDEO_SUSPEND_DEFAULT = false;

    private static int BWE_CHANGE_THRESHOLD_PCT_DEFAULT = 15;

    private static final ConfigurationService cfg = LibJitsi.getConfigurationService();

    private static final int BWE_CHANGE_THRESHOLD_PCT = (cfg != null) ? cfg
            .getInt("org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT", BWE_CHANGE_THRESHOLD_PCT_DEFAULT) : BWE_CHANGE_THRESHOLD_PCT_DEFAULT;

    private static final int THUMBNAIL_MAX_HEIGHT = (cfg != null) ? cfg
            .getInt("org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT", 180) : 180;

    private static final int ONSTAGE_PREFERRED_HEIGHT = (cfg != null) ? cfg
            .getInt("org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT", 360) : 360;

    private static final double ONSTAGE_PREFERRED_FRAME_RATE = (cfg != null) ? cfg
            .getDouble("org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE", 30.0D) : 30.0D;

    private static final boolean ENABLE_ONSTAGE_VIDEO_SUSPEND = (cfg != null) ? cfg
            .getBoolean("org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND", false) : false;

    private final Logger logger = Logger.getLogger(BitrateController.class);

    private final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(BitrateController.class);

    private static final Set<String> INITIAL_EMPTY_SET = Collections.unmodifiableSet(new HashSet<>(0));

    private final VideoChannel dest;

    private final Map<Long, AdaptiveTrackProjection> adaptiveTrackProjectionMap = new ConcurrentHashMap<>();

    private final PacketTransformer rtpTransformer = new RTPTransformer();

    private final PacketTransformer rtcpTransformer = (PacketTransformer)new RTCPTransformer();

    private Set<String> forwardedEndpointIds = INITIAL_EMPTY_SET;

    private final boolean trustBwe;

    private final boolean enableVideoQualityTracing;

    private long firstMediaMs = -1L;

    private long lastBwe = -1L;

    private List<AdaptiveTrackProjection> adaptiveTrackProjections;

    public BitrateController(VideoChannel dest) {
        this.dest = dest;
        ConfigurationService cfg = LibJitsi.getConfigurationService();
        this.trustBwe = (cfg != null && cfg.getBoolean("org.jitsi.videobridge.TRUST_BWE", true));
        this.enableVideoQualityTracing = this.timeSeriesLogger.isTraceEnabled();
    }

    private static boolean isLargerThanBweThreshold(long previousBwe, long currentBwe) {
        return (Math.abs(previousBwe - currentBwe) >= previousBwe * BWE_CHANGE_THRESHOLD_PCT / 100L);
    }

    public VideoChannel getVideoChannel() {
        return this.dest;
    }

    public PacketTransformer getRTPTransformer() {
        return this.rtpTransformer;
    }

    public PacketTransformer getRTCPTransformer() {
        return this.rtcpTransformer;
    }

    List<AdaptiveTrackProjection> getAdaptiveTrackProjections() {
        return this.adaptiveTrackProjections;
    }

    public boolean accept(RawPacket pkt) {
        long ssrc = pkt.getSSRCAsLong();
        if (ssrc < 0L)
            return false;
        AdaptiveTrackProjection adaptiveTrackProjection = this.adaptiveTrackProjectionMap.get(Long.valueOf(ssrc));
        if (adaptiveTrackProjection == null) {
            this.logger.warn("Dropping an RTP packet, because the SSRC has not been signaled " + ((MediaStreamImpl)this.dest

                    .getStream()).packetToString(pkt));
            return false;
        }
        return adaptiveTrackProjection.accept(pkt);
    }

    public void update(long bweBps) {
        if (this.timeSeriesLogger.isTraceEnabled()) {
            VideoMediaStreamImpl destStream = (VideoMediaStreamImpl)this.dest.getStream();
            this.timeSeriesLogger.trace((Map)destStream.getDiagnosticContext()
                    .makeTimeSeriesPoint("new_bwe")
                    .addField("bwe_bps", Long.valueOf(bweBps)));
        }
        update(null, bweBps);
    }

    public void update() {
        update(null, -1L);
    }

    public void update(List<AbstractEndpoint> conferenceEndpoints, long bweBps) {
        if (bweBps > -1L) {
            if (!isLargerThanBweThreshold(this.lastBwe, bweBps))
                return;
            this.lastBwe = bweBps;
        }
        if (conferenceEndpoints == null) {
            conferenceEndpoints = this.dest.getConferenceSpeechActivity().getEndpoints();
        } else {
            conferenceEndpoints = new ArrayList<AbstractEndpoint>(conferenceEndpoints);
        }
        if (!(this.dest.getStream() instanceof VideoMediaStreamImpl))
            return;
        VideoMediaStreamImpl destStream = (VideoMediaStreamImpl)this.dest.getStream();
        BandwidthEstimator bwe = (destStream == null) ? null : destStream.getOrCreateBandwidthEstimator();
        long nowMs = System.currentTimeMillis();
        boolean trustBwe = this.trustBwe;
        if (trustBwe)
            if (this.firstMediaMs == -1L || nowMs - this.firstMediaMs < 10000L)
                trustBwe = false;
        if (bwe != null && bweBps == -1L && trustBwe)
            bweBps = bwe.getLatestEstimate();
        if (bweBps < 0L || !trustBwe ||
                !destStream.getRtxTransformer().destinationSupportsRtx())
            bweBps = Long.MAX_VALUE;
        TrackBitrateAllocation[] trackBitrateAllocations = allocate(bweBps, conferenceEndpoints);
        Set<String> oldForwardedEndpointIds = this.forwardedEndpointIds;
        Set<String> newForwardedEndpointIds = new HashSet<String>();
        Set<String> endpointsEnteringLastNIds = new HashSet<String>();
        Set<String> conferenceEndpointIds = new HashSet<String>();
        long totalIdealBps = 0L, totalTargetBps = 0L;
        int totalIdealIdx = 0, totalTargetIdx = 0;
        List<AdaptiveTrackProjection> adaptiveTrackProjections = new ArrayList<AdaptiveTrackProjection>();
        if (!ArrayUtils.isNullOrEmpty((Object[])trackBitrateAllocations)) {
            for (TrackBitrateAllocation trackBitrateAllocation : trackBitrateAllocations) {
                conferenceEndpointIds.add(trackBitrateAllocation.endpointID);
                int trackTargetIdx = trackBitrateAllocation.getTargetIndex();
                int trackIdealIdx = trackBitrateAllocation.getIdealIndex();
                AdaptiveTrackProjection adaptiveTrackProjection = lookupOrCreateAdaptiveTrackProjection(trackBitrateAllocation);
                if (adaptiveTrackProjection != null) {
                    adaptiveTrackProjections.add(adaptiveTrackProjection);
                    adaptiveTrackProjection.setTargetIndex(trackTargetIdx);
                    adaptiveTrackProjection.setIdealIndex(trackIdealIdx);
                    if (trackBitrateAllocation.track != null && this.enableVideoQualityTracing) {
                        DiagnosticContext diagnosticContext = destStream.getDiagnosticContext();
                        long trackTargetBps = trackBitrateAllocation.getTargetBitrate();
                        long trackIdealBps = trackBitrateAllocation.getIdealBitrate();
                        totalTargetBps += trackTargetBps;
                        totalIdealBps += trackIdealBps;
                        totalTargetIdx += trackTargetIdx;
                        totalIdealIdx += trackIdealIdx;
                        this.timeSeriesLogger.trace((Map)diagnosticContext
                                .makeTimeSeriesPoint("track_quality", nowMs)
                                .addField("track_id",
                                        Integer.valueOf(trackBitrateAllocation.track.hashCode()))
                                .addField("target_idx", Integer.valueOf(trackTargetIdx))
                                .addField("ideal_idx", Integer.valueOf(trackIdealIdx))
                                .addField("target_bps", Long.valueOf(trackTargetBps))
                                .addField("selected",
                                        Boolean.valueOf(trackBitrateAllocation.selected))
                                .addField("oversending",
                                        Boolean.valueOf(trackBitrateAllocation.oversending))
                                .addField("preferred_idx",
                                        Integer.valueOf(trackBitrateAllocation.ratedPreferredIdx))
                                .addField("remote_endpoint_id", trackBitrateAllocation
                                        .endpointID)
                                .addField("ideal_bps", Long.valueOf(trackIdealBps)));
                    }
                }
                if (trackTargetIdx > -1) {
                    newForwardedEndpointIds
                            .add(trackBitrateAllocation.endpointID);
                    if (!oldForwardedEndpointIds.contains(trackBitrateAllocation.endpointID))
                        endpointsEnteringLastNIds
                                .add(trackBitrateAllocation.endpointID);
                }
            }
        } else {
            for (AdaptiveTrackProjection adaptiveTrackProjection : this.adaptiveTrackProjectionMap.values()) {
                if (this.enableVideoQualityTracing) {
                    totalIdealIdx--;
                    totalTargetIdx--;
                }
                adaptiveTrackProjection
                        .setTargetIndex(-1);
                adaptiveTrackProjection
                        .setIdealIndex(-1);
            }
        }
        if (this.enableVideoQualityTracing) {
            DiagnosticContext diagnosticContext = destStream.getDiagnosticContext();
            this.timeSeriesLogger.trace((Map)diagnosticContext
                    .makeTimeSeriesPoint("did_update", nowMs)
                    .addField("total_target_idx", Integer.valueOf(totalTargetIdx))
                    .addField("total_ideal_idx", Integer.valueOf(totalIdealIdx))
                    .addField("bwe_bps", Long.valueOf(bweBps))
                    .addField("total_target_bps", Long.valueOf(totalTargetBps))
                    .addField("total_ideal_bps", Long.valueOf(totalIdealBps)));
        }
        this
                .adaptiveTrackProjections = Collections.unmodifiableList(adaptiveTrackProjections);
        if (!newForwardedEndpointIds.equals(oldForwardedEndpointIds))
            this.dest.sendLastNEndpointsChangeEvent(newForwardedEndpointIds, endpointsEnteringLastNIds, conferenceEndpointIds);
        this.forwardedEndpointIds = newForwardedEndpointIds;
    }

    private AdaptiveTrackProjection lookupOrCreateAdaptiveTrackProjection(TrackBitrateAllocation trackBitrateAllocation) {
        synchronized (this.adaptiveTrackProjectionMap) {
            int ssrc = trackBitrateAllocation.targetSSRC;
            AdaptiveTrackProjection adaptiveTrackProjection = this.adaptiveTrackProjectionMap.get(Long.valueOf(ssrc & 0xFFFFFFFFL));
            if (adaptiveTrackProjection != null || trackBitrateAllocation
                    .track == null)
                return adaptiveTrackProjection;
            RTPEncodingDesc[] rtpEncodings = trackBitrateAllocation.track.getRTPEncodings();
            if (ArrayUtils.isNullOrEmpty((Object[])rtpEncodings))
                return adaptiveTrackProjection;
            adaptiveTrackProjection = new AdaptiveTrackProjection(trackBitrateAllocation.track);
            this.logger.info("new track projection for " + trackBitrateAllocation
                    .track);
            for (RTPEncodingDesc rtpEncoding : rtpEncodings) {
                this.adaptiveTrackProjectionMap.put(
                        Long.valueOf(rtpEncoding.getPrimarySSRC()), adaptiveTrackProjection);
                long rtxSsrc = rtpEncoding.getSecondarySsrc("rtx");
                if (rtxSsrc != -1L)
                    this.adaptiveTrackProjectionMap.put(
                            Long.valueOf(rtxSsrc), adaptiveTrackProjection);
            }
            return adaptiveTrackProjection;
        }
    }

    private TrackBitrateAllocation[] allocate(long maxBandwidth, List<AbstractEndpoint> conferenceEndpoints) {
        TrackBitrateAllocation[] trackBitrateAllocations = prioritize(conferenceEndpoints);
        if (ArrayUtils.isNullOrEmpty((Object[])trackBitrateAllocations))
            return trackBitrateAllocations;
        long oldMaxBandwidth = 0L;
        int oldStateLen = 0;
        int[] oldRatedTargetIndices = new int[trackBitrateAllocations.length];
        int[] newRatedTargetIndicies = new int[trackBitrateAllocations.length];
        Arrays.fill(newRatedTargetIndicies, -1);
        while (oldMaxBandwidth != maxBandwidth) {
            oldMaxBandwidth = maxBandwidth;
            System.arraycopy(newRatedTargetIndicies, 0, oldRatedTargetIndices, 0, oldRatedTargetIndices.length);
            int newStateLen = 0;
            int i;
            for (i = 0; i < trackBitrateAllocations.length; i++) {
                TrackBitrateAllocation trackBitrateAllocation = trackBitrateAllocations[i];
                if (!trackBitrateAllocation.fitsInLastN)
                    break;
                maxBandwidth += trackBitrateAllocation.getTargetBitrate();
                trackBitrateAllocation.improve(maxBandwidth);
                maxBandwidth -= trackBitrateAllocation.getTargetBitrate();
                newRatedTargetIndicies[i] = trackBitrateAllocation
                        .ratedTargetIdx;
                if (trackBitrateAllocation.getTargetIndex() > -1)
                    newStateLen++;
                if (trackBitrateAllocation.ratedTargetIdx < trackBitrateAllocation
                        .ratedPreferredIdx)
                    break;
            }
            if (oldStateLen > newStateLen) {
                for (i = 0; i < trackBitrateAllocations.length; i++)
                    (trackBitrateAllocations[i]).ratedTargetIdx = oldRatedTargetIndices[i];
                break;
            }
            oldStateLen = newStateLen;
        }
        return trackBitrateAllocations;
    }

    private TrackBitrateAllocation[] prioritize(List<AbstractEndpoint> conferenceEndpoints) {
        if (this.dest.isExpired())
            return null;
        AbstractEndpoint destEndpoint = this.dest.getEndpoint();
        if (destEndpoint == null || destEndpoint.isExpired())
            return null;
        List<TrackBitrateAllocation> trackBitrateAllocations = new ArrayList<TrackBitrateAllocation>();
        int lastN = this.dest.getLastN();
        if (lastN < 0) {
            lastN = conferenceEndpoints.size() - 1;
        } else {
            lastN = Math.min(lastN, conferenceEndpoints.size() - 1);
        }
        int endpointPriority = 0;
        Set<String> selectedEndpoints = destEndpoint.getSelectedEndpoints();
        Iterator<AbstractEndpoint> it = conferenceEndpoints.iterator();
        while (it.hasNext() && endpointPriority < lastN) {
            AbstractEndpoint sourceEndpoint = it.next();
            if (sourceEndpoint.isExpired() || sourceEndpoint
                    .getID().equals(destEndpoint.getID()) ||
                    !selectedEndpoints.contains(sourceEndpoint.getID()))
                continue;
            MediaStreamTrackDesc[] tracks = sourceEndpoint.getMediaStreamTracks(MediaType.VIDEO);
            if (!ArrayUtils.isNullOrEmpty((Object[])tracks)) {
                for (MediaStreamTrackDesc track : tracks)
                    trackBitrateAllocations.add(endpointPriority, new TrackBitrateAllocation(sourceEndpoint, track, true, true,

                            getVideoChannel().getMaxFrameHeight()));
                endpointPriority++;
            }
            it.remove();
        }
        Set<String> pinnedEndpoints = destEndpoint.getPinnedEndpoints();
        if (!pinnedEndpoints.isEmpty()) {
            Iterator<AbstractEndpoint> iterator = conferenceEndpoints.iterator();
            while (iterator.hasNext() && endpointPriority < lastN) {
                AbstractEndpoint sourceEndpoint = iterator.next();
                if (sourceEndpoint.isExpired() || sourceEndpoint
                        .getID().equals(destEndpoint.getID()) ||
                        !pinnedEndpoints.contains(sourceEndpoint.getID()))
                    continue;
                MediaStreamTrackDesc[] tracks = sourceEndpoint.getMediaStreamTracks(MediaType.VIDEO);
                if (!ArrayUtils.isNullOrEmpty((Object[])tracks)) {
                    for (MediaStreamTrackDesc track : tracks)
                        trackBitrateAllocations.add(endpointPriority, new TrackBitrateAllocation(sourceEndpoint, track, true, false,

                                getVideoChannel().getMaxFrameHeight()));
                    endpointPriority++;
                }
                iterator.remove();
            }
        }
        if (!conferenceEndpoints.isEmpty())
            for (AbstractEndpoint sourceEndpoint : conferenceEndpoints) {
                if (sourceEndpoint.isExpired() || sourceEndpoint
                        .getID().equals(destEndpoint.getID()))
                    continue;
                boolean forwarded = (endpointPriority < lastN);
                MediaStreamTrackDesc[] tracks = sourceEndpoint.getMediaStreamTracks(MediaType.VIDEO);
                if (!ArrayUtils.isNullOrEmpty((Object[])tracks)) {
                    for (MediaStreamTrackDesc track : tracks)
                        trackBitrateAllocations.add(endpointPriority, new TrackBitrateAllocation(sourceEndpoint, track, forwarded, false,

                                getVideoChannel().getMaxFrameHeight()));
                    endpointPriority++;
                }
            }
        return trackBitrateAllocations.<TrackBitrateAllocation>toArray(
                new TrackBitrateAllocation[trackBitrateAllocations.size()]);
    }

    public Collection<String> getForwardedEndpoints() {
        return this.forwardedEndpointIds;
    }

    static class RateSnapshot {
        final long bps;

        final RTPEncodingDesc encoding;

        private RateSnapshot(long bps, RTPEncodingDesc encoding) {
            this.bps = bps;
            this.encoding = encoding;
        }
    }

    private class TrackBitrateAllocation {
        private final String endpointID;

        private final boolean fitsInLastN;

        private final boolean selected;

        private final int targetSSRC;

        private final int maxFrameHeight;

        private final MediaStreamTrackDesc track;

        private final BitrateController.RateSnapshot[] ratedIndices;

        private final int ratedPreferredIdx;

        private int ratedTargetIdx;

        private boolean oversending;

        private TrackBitrateAllocation(AbstractEndpoint endpoint, MediaStreamTrackDesc track, boolean fitsInLastN, boolean selected, int maxFrameHeight) {
            RTPEncodingDesc[] encodings;
            this.ratedTargetIdx = -1;
            this.oversending = false;
            this.endpointID = endpoint.getID();
            this.selected = selected;
            this.fitsInLastN = fitsInLastN;
            this.track = track;
            this.maxFrameHeight = maxFrameHeight;
            if (track == null) {
                this.targetSSRC = -1;
                encodings = null;
            } else {
                encodings = track.getRTPEncodings();
                if (ArrayUtils.isNullOrEmpty((Object[])encodings)) {
                    this.targetSSRC = -1;
                } else {
                    this.targetSSRC = (int)encodings[0].getPrimarySSRC();
                }
            }
            if (this.targetSSRC == -1 || !fitsInLastN) {
                this.ratedPreferredIdx = -1;
                this.ratedIndices = BitrateController.EMPTY_RATE_SNAPSHOT_ARRAY;
                return;
            }
            List<BitrateController.RateSnapshot> ratesList = new ArrayList<RateSnapshot>();
            int ratedPreferredIdx = 0;
            for (RTPEncodingDesc encoding : encodings) {
                if (encoding.getHeight() <= this.maxFrameHeight)
                    if (selected) {
                        if (encoding.getHeight() < BitrateController.ONSTAGE_PREFERRED_HEIGHT || encoding
                                .getFrameRate() >= BitrateController.ONSTAGE_PREFERRED_FRAME_RATE)
                            ratesList.add(new BitrateController.RateSnapshot(encoding
                                    .getLastStableBitrateBps(System.currentTimeMillis()), encoding));
                        if (encoding.getHeight() <= BitrateController.ONSTAGE_PREFERRED_HEIGHT)
                            ratedPreferredIdx = ratesList.size() - 1;
                    } else if (encoding.getHeight() <= BitrateController.THUMBNAIL_MAX_HEIGHT) {
                        ratesList.add(new BitrateController.RateSnapshot(encoding
                                .getLastStableBitrateBps(System.currentTimeMillis()), encoding));
                    }
            }
            this.ratedPreferredIdx = ratedPreferredIdx;
            this.ratedIndices = ratesList.<BitrateController.RateSnapshot>toArray(new BitrateController.RateSnapshot[ratesList.size()]);
        }

        void improve(long maxBps) {
            if (this.ratedIndices.length == 0)
                return;
            if (this.ratedTargetIdx == -1 && this.selected) {
                if (!BitrateController.ENABLE_ONSTAGE_VIDEO_SUSPEND) {
                    this.ratedTargetIdx = 0;
                    this.oversending = ((this.ratedIndices[0]).bps > maxBps);
                }
                for (int i = this.ratedTargetIdx + 1; i < this.ratedIndices.length; i++) {
                    if (i > this.ratedPreferredIdx || maxBps < (this.ratedIndices[i]).bps)
                        break;
                    this.ratedTargetIdx = i;
                }
            } else if (this.ratedTargetIdx + 1 < this.ratedIndices.length && (this.ratedIndices[this.ratedTargetIdx + 1]).bps < maxBps) {
                this.ratedTargetIdx++;
            }
        }

        long getTargetBitrate() {
            return (this.ratedTargetIdx != -1) ? (this.ratedIndices[this.ratedTargetIdx]).bps : 0L;
        }

        long getIdealBitrate() {
            return (this.ratedIndices.length != 0) ? (this.ratedIndices[this.ratedIndices.length - 1]).bps : 0L;
        }

        int getTargetIndex() {
            return (this.ratedTargetIdx != -1) ? (this.ratedIndices[this.ratedTargetIdx]).encoding
                    .getIndex() : -1;
        }

        int getIdealIndex() {
            return (this.ratedIndices.length != 0) ? (this.ratedIndices[this.ratedIndices.length - 1]).encoding
                    .getIndex() : -1;
        }
    }

    private class RTPTransformer implements PacketTransformer {
        private RTPTransformer() {}

        public void close() {}

        public RawPacket[] reverseTransform(RawPacket[] pkts) {
            return pkts;
        }

        public RawPacket[] transform(RawPacket[] pkts) {
            if (ArrayUtils.isNullOrEmpty((Object[])pkts))
                return pkts;
            if (BitrateController.this.firstMediaMs == -1L)
                BitrateController.this.firstMediaMs = System.currentTimeMillis();
            RawPacket[] extras = null;
            for (int i = 0; i < pkts.length; i++) {
                if (RTPPacketPredicate.INSTANCE.test((ByteArrayBuffer)pkts[i])) {
                    long ssrc = pkts[i].getSSRCAsLong();
                    AdaptiveTrackProjection adaptiveTrackProjection = (AdaptiveTrackProjection)BitrateController.this.adaptiveTrackProjectionMap.get(Long.valueOf(ssrc));
                    if (adaptiveTrackProjection == null) {
                        pkts[i] = null;
                    } else {
                        RawPacket[] ret = new RawPacket[0];
                        try {
                            ret = adaptiveTrackProjection.rewriteRtp(pkts[i]);
                        } catch (RewriteException ex) {
                            pkts[i] = null;
                        }
                        if (!ArrayUtils.isNullOrEmpty((Object[])ret)) {
                            int extrasLen = ArrayUtils.isNullOrEmpty((Object[])extras) ? 0 : extras.length;
                            RawPacket[] newExtras = new RawPacket[extrasLen + ret.length];
                            System.arraycopy(ret, 0, newExtras, extrasLen, ret.length);
                            if (extrasLen > 0)
                                System.arraycopy(extras, 0, newExtras, 0, extrasLen);
                            extras = newExtras;
                        }
                    }
                }
            }
            return (RawPacket[])ArrayUtils.concat((Object[])pkts, (Object[])extras);
        }
    }

    private class RTCPTransformer extends SinglePacketTransformerAdapter {
        RTCPTransformer() {
            super((Predicate)RTCPPacketPredicate.INSTANCE);
        }

        public RawPacket transform(RawPacket pkt) {
            long ssrc = pkt.getRTCPSSRC();
            if (ssrc < 0L)
                return pkt;
            AdaptiveTrackProjection adaptiveTrackProjection = (AdaptiveTrackProjection)BitrateController.this.adaptiveTrackProjectionMap.get(Long.valueOf(ssrc));
            if (adaptiveTrackProjection != null)
                if (!adaptiveTrackProjection.rewriteRtcp(pkt))
                    return null;
            return pkt;
        }
    }
}
