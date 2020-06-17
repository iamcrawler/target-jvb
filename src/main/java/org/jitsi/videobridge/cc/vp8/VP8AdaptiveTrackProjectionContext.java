package org.jitsi.videobridge.cc.vp8;


import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer;
import org.jitsi.impl.neomedia.rtcp.RTCPIterator;
import org.jitsi.impl.neomedia.rtcp.RTCPSenderInfoUtils;
import org.jitsi.impl.neomedia.rtp.RawPacketCache;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.util.RTCPUtils;
import org.jitsi.util.RTPUtils;
import org.jitsi.utils.ArrayUtils;
import org.jitsi.utils.ByteArrayBuffer;
import org.jitsi.utils.LRUCache;
import org.jitsi.videobridge.cc.AdaptiveTrackProjectionContext;
import org.jitsi.videobridge.cc.RewriteException;
import org.jitsi.videobridge.cc.RtpState;

public class VP8AdaptiveTrackProjectionContext implements AdaptiveTrackProjectionContext {
    private final Map<Long, VP8FrameProjection> vp8FrameProjectionMap = new ConcurrentHashMap<>();

    private final Map<Long, LRUCache<Long, Integer>> ssrcToFrameToMaxSequenceNumberMap = new HashMap<>();

    private final VP8QualityFilter vp8QualityFilter = new VP8QualityFilter();

    private VP8FrameProjection lastVP8FrameProjection;

    private final Object transmittedSyncRoot = new Object();

    private long transmittedBytes = 0L;

    private long transmittedPackets = 0L;

    private final MediaFormat format;

    public VP8AdaptiveTrackProjectionContext(@NotNull MediaFormat format, @NotNull RtpState rtpState) {
        this.format = format;
        int startingSequenceNumber = rtpState.maxSequenceNumber + 1 & 0xFFFF;
        long timestamp = rtpState.maxTimestamp + 3000L & 0xFFFFFFFFL;
        this.lastVP8FrameProjection = new VP8FrameProjection(rtpState.ssrc, startingSequenceNumber, timestamp);
    }

    private VP8FrameProjection lookupVP8FrameProjection(@NotNull RawPacket rtpPacket) {
        VP8FrameProjection lastVP8FrameProjectionCopy = this.lastVP8FrameProjection;
        VP8Frame lastVP8Frame = lastVP8FrameProjectionCopy.getVP8Frame();
        if (lastVP8Frame != null && lastVP8Frame.matchesFrame(rtpPacket))
            return lastVP8FrameProjectionCopy;
        VP8FrameProjection cachedVP8FrameProjection = this.vp8FrameProjectionMap.get(Long.valueOf(rtpPacket.getTimestamp()));
        if (cachedVP8FrameProjection != null) {
            VP8Frame cachedVP8Frame = cachedVP8FrameProjection.getVP8Frame();
            if (cachedVP8Frame != null && cachedVP8Frame.matchesFrame(rtpPacket))
                return cachedVP8FrameProjection;
        }
        return null;
    }

    private synchronized VP8FrameProjection createVP8FrameProjection(@NotNull RawPacket rtpPacket, int incomingIndex, int targetIndex) {
        VP8Frame lastVP8Frame = this.lastVP8FrameProjection.getVP8Frame();
        if (lastVP8Frame != null && lastVP8Frame.matchesOlderFrame(rtpPacket))
            return null;
        byte[] buf = rtpPacket.getBuffer();
        int payloadOff = rtpPacket.getPayloadOffset();
        if (!DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buf, payloadOff)) {
            maybeUpdateMaxSequenceNumberOfFrame(rtpPacket
                    .getSSRCAsLong(), rtpPacket
                    .getTimestamp(), rtpPacket
                    .getSequenceNumber());
            return null;
        }
        long nowMs = System.currentTimeMillis();
        if (!this.vp8QualityFilter.acceptFrame(rtpPacket, incomingIndex, targetIndex, nowMs))
            return null;
        int maxSequenceNumberSeenBeforeFirstPacket = getMaxSequenceNumberOfFrame(rtpPacket
                .getSSRCAsLong(), rtpPacket.getTimestamp());
        VP8FrameProjection nextVP8FrameProjection = this.lastVP8FrameProjection.makeNext(rtpPacket, maxSequenceNumberSeenBeforeFirstPacket, nowMs);
        if (nextVP8FrameProjection == null)
            return null;
        this.vp8FrameProjectionMap.put(Long.valueOf(rtpPacket.getTimestamp()), nextVP8FrameProjection);
        this.lastVP8FrameProjection = nextVP8FrameProjection;
        this.vp8FrameProjectionMap.entrySet().removeIf(e -> ((VP8FrameProjection)e.getValue()).isFullyProjected(nowMs));
        return nextVP8FrameProjection;
    }

    private int getMaxSequenceNumberOfFrame(long ssrc, long timestamp) {
        Map<Long, Integer> frameToMaxSequenceNumberMap = (Map<Long, Integer>)this.ssrcToFrameToMaxSequenceNumberMap.get(Long.valueOf(ssrc));
        if (frameToMaxSequenceNumberMap == null)
            return -1;
        return ((Integer)frameToMaxSequenceNumberMap
                .getOrDefault(Long.valueOf(timestamp), Integer.valueOf(-1))).intValue();
    }

    private void maybeUpdateMaxSequenceNumberOfFrame(long ssrc, long timestamp, int sequenceNumber) {
        Map<Long, Integer> frameToMaxSequenceNumberMap = (Map<Long, Integer>)this.ssrcToFrameToMaxSequenceNumberMap.computeIfAbsent(Long.valueOf(ssrc), k -> new LRUCache(5));
        if (frameToMaxSequenceNumberMap.containsKey(Long.valueOf(timestamp))) {
            int previousMaxSequenceNumber = getMaxSequenceNumberOfFrame(ssrc, timestamp);
            if (previousMaxSequenceNumber != -1 &&
                    RTPUtils.isOlderSequenceNumberThan(previousMaxSequenceNumber, sequenceNumber))
                frameToMaxSequenceNumberMap.put(Long.valueOf(timestamp), Integer.valueOf(sequenceNumber));
        } else {
            frameToMaxSequenceNumberMap.put(Long.valueOf(timestamp), Integer.valueOf(sequenceNumber));
        }
    }

    public boolean needsKeyframe() {
        if (this.vp8QualityFilter.needsKeyframe())
            return true;
        VP8Frame lastVP8Frame = this.lastVP8FrameProjection.getVP8Frame();
        return (lastVP8Frame == null || lastVP8Frame.needsKeyframe());
    }

    public boolean accept(@NotNull RawPacket rtpPacket, int incomingIndex, int targetIndex) {
        VP8FrameProjection vp8FrameProjection = lookupVP8FrameProjection(rtpPacket);
        if (vp8FrameProjection == null)
            vp8FrameProjection = createVP8FrameProjection(rtpPacket, incomingIndex, targetIndex);
        return (vp8FrameProjection != null && vp8FrameProjection
                .accept(rtpPacket));
    }

    public boolean rewriteRtcp(@NotNull RawPacket rtcpPacket) {
        boolean removed = false;
        RTCPIterator it = new RTCPIterator((ByteArrayBuffer)rtcpPacket);
        while (it.hasNext()) {
            VP8FrameProjection lastVP8FrameProjectionCopy;
            long srcTs, delta, dstTs;
            ByteArrayBuffer baf = it.next();
            switch (RTCPUtils.getPacketType(baf)) {
                case 202:
                    if (removed)
                        it.remove();
                case 200:
                    lastVP8FrameProjectionCopy = this.lastVP8FrameProjection;
                    if (lastVP8FrameProjectionCopy.getVP8Frame() == null ||
                            RawPacket.getRTCPSSRC(baf) != lastVP8FrameProjectionCopy
                                    .getSSRC()) {
                        removed = true;
                        it.remove();
                        continue;
                    }
                    srcTs = RTCPSenderInfoUtils.getTimestamp(baf);
                    delta = RTPUtils.rtpTimestampDiff(lastVP8FrameProjectionCopy
                            .getTimestamp(), lastVP8FrameProjectionCopy
                            .getVP8Frame().getTimestamp());
                    dstTs = RTPUtils.as32Bits(srcTs + delta);
                    if (srcTs != dstTs)
                        RTCPSenderInfoUtils.setTimestamp(baf, (int)dstTs);
                    synchronized (this.transmittedSyncRoot) {
                        RTCPSenderInfoUtils.setOctetCount(baf, (int)this.transmittedBytes);
                        RTCPSenderInfoUtils.setPacketCount(baf, (int)this.transmittedPackets);
                    }
            }
        }
        return (rtcpPacket.getLength() > 0);
    }

    public RtpState getRtpState() {
        synchronized (this) {
            this.lastVP8FrameProjection.close();
        }
        return new RtpState(this.transmittedBytes, this.transmittedPackets, this.lastVP8FrameProjection
                .getSSRC(), this.lastVP8FrameProjection
                .maxSequenceNumber(), this.lastVP8FrameProjection
                .getTimestamp());
    }

    public MediaFormat getFormat() {
        return this.format;
    }

    public RawPacket[] rewriteRtp(@NotNull RawPacket rtpPacket, RawPacketCache incomingRawPacketCache) throws RewriteException {
        VP8FrameProjection vp8FrameProjection = lookupVP8FrameProjection(rtpPacket);
        if (vp8FrameProjection == null)
            throw new RewriteException();
        RawPacket[] ret = vp8FrameProjection.rewriteRtp(rtpPacket, incomingRawPacketCache);
        synchronized (this.transmittedSyncRoot) {
            this.transmittedBytes += rtpPacket.getLength();
            this.transmittedPackets++;
            if (!ArrayUtils.isNullOrEmpty((Object[])ret))
                for (int i = 0; i < ret.length; i++) {
                    this.transmittedBytes += ret[i].getLength();
                    this.transmittedPackets++;
                }
        }
        return ret;
    }
}