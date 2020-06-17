package org.jitsi.videobridge.cc.vp8;


import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer;
import org.jitsi.impl.neomedia.rtp.RawPacketCache;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.util.RTPUtils;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.cc.AdaptiveTrackProjection;

public class VP8FrameProjection {
    private static final Logger logger = Logger.getLogger(VP8FrameProjection.class);

    private final long WAIT_MS = 5000L;

    private final long createdMs;

    private final VP8Frame vp8Frame;

    private final long ssrc;

    private final long timestamp;

    private final int startingSequenceNumber;

    private final int extendedPictureId;

    private final int tl0PICIDX;

    private boolean isLast = true;

    VP8FrameProjection(long ssrc, int startingSequenceNumber, long timestamp) {
        this(null, ssrc, timestamp, startingSequenceNumber, 0, 0, 0L);
    }

    private VP8FrameProjection(VP8Frame vp8Frame, long ssrc, long timestamp, int startingSequenceNumber, int extendedPictureId, int tl0PICIDX, long createdMs) {
        this.ssrc = ssrc;
        this.timestamp = timestamp;
        this.startingSequenceNumber = startingSequenceNumber;
        this.extendedPictureId = extendedPictureId;
        this.tl0PICIDX = tl0PICIDX;
        this.vp8Frame = vp8Frame;
        this.createdMs = createdMs;
    }

    VP8FrameProjection makeNext(@NotNull RawPacket firstPacketOfFrame, int maxSequenceNumberSeenBeforeFirstPacket, long nowMs) {
        VP8Frame nextVP8Frame = new VP8Frame(firstPacketOfFrame, maxSequenceNumberSeenBeforeFirstPacket);
        if (this.vp8Frame == null) {
            if (nextVP8Frame.isKeyframe()) {
                close();
                return new VP8FrameProjection(nextVP8Frame, this.ssrc, this.timestamp, this.startingSequenceNumber, this.extendedPictureId, this.tl0PICIDX, nowMs);
            }
            return null;
        }
        if (!this.vp8Frame.decodes(nextVP8Frame))
            return null;
        close();
        return new VP8FrameProjection(nextVP8Frame, this.ssrc,
                nextTimestamp(nextVP8Frame, nowMs),
                nextStartingSequenceNumber(), nextExtendedPictureId(),
                nextTL0PICIDX(nextVP8Frame), nowMs);
    }

    private int nextTL0PICIDX(@NotNull VP8Frame nextVP8Frame) {
        return nextVP8Frame.isTL0() ?
                VP8Frame.nextTL0PICIDX(this.tl0PICIDX) : this.tl0PICIDX;
    }

    private long nextTimestamp(@NotNull VP8Frame nextVP8Frame, long nowMs) {
        long delta;
        if (!this.vp8Frame.matchesSSRC(nextVP8Frame)) {
            delta = 3000L * Math.max(1L, (nowMs - this.createdMs) / 33L);
        } else {
            delta = RTPUtils.rtpTimestampDiff(nextVP8Frame
                    .getTimestamp(), this.vp8Frame.getTimestamp());
        }
        long nextTimestamp = this.timestamp + delta;
        return nextTimestamp & 0xFFFFFFFFL;
    }

    private int nextStartingSequenceNumber() {
        return maxSequenceNumber() + 1 & 0xFFFF;
    }

    int maxSequenceNumber() {
        if (this.vp8Frame != null) {
            int vp8FrameLength = RTPUtils.getSequenceNumberDelta(this.vp8Frame
                    .getMaxSequenceNumber(), this.vp8Frame
                    .getStartingSequenceNumber());
            int maxSequenceNumber = this.startingSequenceNumber + vp8FrameLength;
            return maxSequenceNumber & 0xFFFF;
        }
        return this.startingSequenceNumber - 1 & 0xFFFF;
    }

    private int nextExtendedPictureId() {
        return this.extendedPictureId + 1 & 0x7FFF;
    }

    RawPacket[] rewriteRtp(@NotNull RawPacket rtpPacket, RawPacketCache cache) {
        int originalSequenceNumber = rtpPacket.getSequenceNumber();
        rewriteRtpInternal(rtpPacket);
        int piggyBackUntilSequenceNumber = this.vp8Frame.getMaxSequenceNumberSeenBeforeFirstPacket();
        if (piggyBackUntilSequenceNumber < 0 || originalSequenceNumber != this.vp8Frame
                .getStartingSequenceNumber() || cache == null)
            return AdaptiveTrackProjection.EMPTY_PACKET_ARR;
        long vp8FrameSSRC = this.vp8Frame.getSSRCAsLong();
        List<RawPacket> piggyBackedPackets = new ArrayList<>();
        int len = RTPUtils.getSequenceNumberDelta(piggyBackUntilSequenceNumber, originalSequenceNumber) + 1;
        if (logger.isDebugEnabled())
            logger.debug("Piggybacking " + len + " missed packets from " + originalSequenceNumber + " until " + piggyBackUntilSequenceNumber);
        for (int i = 0; i < len; i++) {
            int piggyBackedPacketSequenceNumber = originalSequenceNumber + i & 0xFFFF;
            RawPacket lastPacket = cache.get(vp8FrameSSRC, piggyBackedPacketSequenceNumber);
            if (lastPacket != null && accept(lastPacket))
                piggyBackedPackets.add(lastPacket);
        }
        if (piggyBackedPackets.size() > 0) {
            for (RawPacket pktOut : piggyBackedPackets)
                rewriteRtpInternal(pktOut);
            return piggyBackedPackets.<RawPacket>toArray(new RawPacket[0]);
        }
        return AdaptiveTrackProjection.EMPTY_PACKET_ARR;
    }

    private void rewriteRtpInternal(@NotNull RawPacket pkt) {
        pkt.setSSRC((int)this.ssrc);
        pkt.setTimestamp(this.timestamp);
        int sequenceNumberDelta = RTPUtils.getSequenceNumberDelta(pkt
                .getSequenceNumber(), this.vp8Frame.getStartingSequenceNumber());
        int sequenceNumber = RTPUtils.applySequenceNumberDelta(this.startingSequenceNumber, sequenceNumberDelta);
        pkt.setSequenceNumber(sequenceNumber);
        byte[] buf = pkt.getBuffer();
        int payloadOff = pkt.getPayloadOffset();
        int payloadLen = pkt.getPayloadLength();
        if (!DePacketizer.VP8PayloadDescriptor.setTL0PICIDX(buf, payloadOff, payloadLen, this.tl0PICIDX))
            logger.warn("Failed to set the TL0PICIDX of a VP8 packet " + pkt + ", " +

                    DePacketizer.VP8PayloadDescriptor.toString(buf, payloadOff, payloadLen));
        if (!DePacketizer.VP8PayloadDescriptor.setExtendedPictureId(buf, payloadOff, payloadLen, this.extendedPictureId))
            logger.warn("Failed to set the picture id of a VP8 packet " + pkt + ", " +

                    DePacketizer.VP8PayloadDescriptor.toString(buf, payloadOff, payloadLen));
    }

    public boolean accept(@NotNull RawPacket rtpPacket) {
        if (this.vp8Frame == null || !this.vp8Frame.matchesFrame(rtpPacket))
            return false;
        synchronized (this.vp8Frame) {
            int sequenceNumber = rtpPacket.getSequenceNumber();
            int deltaFromMax = RTPUtils.getSequenceNumberDelta(this.vp8Frame
                    .getMaxSequenceNumber(), sequenceNumber);
            boolean isGreaterThanMax = (this.vp8Frame.getMaxSequenceNumber() == -1 || deltaFromMax < 0);
            if (this.isLast) {
                if (isGreaterThanMax)
                    this.vp8Frame.setMaxSequenceNumber(sequenceNumber, rtpPacket
                            .isPacketMarked());
                return true;
            }
            return !isGreaterThanMax;
        }
    }

    VP8Frame getVP8Frame() {
        return this.vp8Frame;
    }

    public long getSSRC() {
        return this.ssrc;
    }

    boolean isFullyProjected(long nowMs) {
        return (nowMs - this.createdMs > 5000L);
    }

    long getTimestamp() {
        return this.timestamp;
    }

    public void close() {
        if (this.vp8Frame != null) {
            synchronized (this.vp8Frame) {
                this.isLast = false;
            }
        } else {
            this.isLast = false;
        }
    }
}
