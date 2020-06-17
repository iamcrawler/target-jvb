package org.jitsi.videobridge.cc.vp8;


import org.jetbrains.annotations.NotNull;
import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.util.RTPUtils;

class VP8Frame {
    private final long ssrc;

    private final long timestamp;

    private final int startingSequenceNumber;

    private final boolean isTL0;

    private final int tl0PICIDX;

    private final boolean isKeyframe;

    private final boolean isReference;

    private boolean needsKeyframe = false;

    private boolean endingSequenceNumberIsKnown = false;

    private final int maxSequenceNumberSeenBeforeFirstPacket;

    private int maxSequenceNumber = -1;

    VP8Frame(@NotNull RawPacket firstPacketOfFrame, int maxSequenceNumberSeenBeforeFirstPacket) {
        this.ssrc = firstPacketOfFrame.getSSRCAsLong();
        this.timestamp = firstPacketOfFrame.getTimestamp();
        this.startingSequenceNumber = firstPacketOfFrame.getSequenceNumber();
        this.maxSequenceNumberSeenBeforeFirstPacket = maxSequenceNumberSeenBeforeFirstPacket;
        byte[] buf = firstPacketOfFrame.getBuffer();
        int payloadOffset = firstPacketOfFrame.getPayloadOffset();
        int payloadLen = firstPacketOfFrame.getPayloadLength();
        this
                .tl0PICIDX = DePacketizer.VP8PayloadDescriptor.getTL0PICIDX(buf, payloadOffset, payloadLen);
        this
                .isKeyframe = DePacketizer.isKeyFrame(buf, payloadOffset, payloadLen);
        this
                .isReference = DePacketizer.VP8PayloadDescriptor.isReference(buf, payloadOffset, payloadLen);
        this
                .isTL0 = (DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(buf, payloadOffset, payloadLen) == 0);
    }

    int getStartingSequenceNumber() {
        return this.startingSequenceNumber;
    }

    boolean needsKeyframe() {
        return this.needsKeyframe;
    }

    boolean isKeyframe() {
        return this.isKeyframe;
    }

    boolean isTL0() {
        return this.isTL0;
    }

    long getSSRCAsLong() {
        return this.ssrc;
    }

    long getTimestamp() {
        return this.timestamp;
    }

    int getMaxSequenceNumber() {
        return this.maxSequenceNumber;
    }

    private boolean endingSequenceNumberIsKnown() {
        return this.endingSequenceNumberIsKnown;
    }

    static int nextTL0PICIDX(int tl0picidx) {
        return tl0picidx + 1 & 0xFF;
    }

    private boolean isNextTemporalBaseLayerFrame(@NotNull VP8Frame vp8Frame) {
        return (matchesSSRC(vp8Frame) && vp8Frame.isTL0 &&
                nextTL0PICIDX(this.tl0PICIDX) == vp8Frame.tl0PICIDX);
    }

    private boolean dependsOnSameTemporalBaseLayerFrame(@NotNull VP8Frame vp8Frame) {
        return (matchesSSRC(vp8Frame) && vp8Frame.tl0PICIDX == this.tl0PICIDX);
    }

    boolean matchesSSRC(@NotNull VP8Frame vp8Frame) {
        return (this.ssrc == vp8Frame.ssrc);
    }

    private boolean matchesSSRC(@NotNull RawPacket pkt) {
        return (this.ssrc == pkt.getSSRCAsLong());
    }

    boolean matchesOlderFrame(@NotNull RawPacket pkt) {
        if (!matchesSSRC(pkt))
            return false;
        return (RTPUtils.rtpTimestampDiff(pkt.getTimestamp(), this.timestamp) < 0L);
    }

    boolean matchesFrame(@NotNull RawPacket pkt) {
        return (matchesSSRC(pkt) && this.timestamp == pkt.getTimestamp());
    }

    boolean decodes(@NotNull VP8Frame vp8Frame) {
        if (vp8Frame.isKeyframe) {
            this.needsKeyframe = false;
            return true;
        }
        if (!matchesSSRC(vp8Frame))
            return false;
        if (!this.isReference)
            return (isNextTemporalBaseLayerFrame(vp8Frame) ||
                    dependsOnSameTemporalBaseLayerFrame(vp8Frame));
        if (this.isTL0) {
            boolean accept = (endingSequenceNumberIsKnown() && (isNextTemporalBaseLayerFrame(vp8Frame) || dependsOnSameTemporalBaseLayerFrame(vp8Frame)));
            if (!accept && isNextTemporalBaseLayerFrame(vp8Frame))
                this.needsKeyframe = true;
            return accept;
        }
        return (isNextTemporalBaseLayerFrame(vp8Frame) || (
                endingSequenceNumberIsKnown() &&
                        dependsOnSameTemporalBaseLayerFrame(vp8Frame)));
    }

    void setMaxSequenceNumber(int sequenceNumber, boolean isEndingSequenceNumber) {
        this.maxSequenceNumber = sequenceNumber;
        if (isEndingSequenceNumber)
            this.endingSequenceNumberIsKnown = true;
    }

    int getMaxSequenceNumberSeenBeforeFirstPacket() {
        return this.maxSequenceNumberSeenBeforeFirstPacket;
    }
}
