package org.jitsi.videobridge.cc;


import org.jetbrains.annotations.NotNull;
import org.jitsi.impl.neomedia.codec.video.h264.DePacketizer;
//import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer;
//import org.jitsi.impl.neomedia.codec.video.vp9.DePacketizer;
import org.jitsi.impl.neomedia.rtcp.RTCPSenderInfoUtils;
import org.jitsi.impl.neomedia.rtp.RawPacketCache;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.util.RTCPUtils;
import org.jitsi.util.RTPUtils;
import org.jitsi.utils.ByteArrayBuffer;
import org.jitsi.utils.logging.Logger;

class GenericAdaptiveTrackProjectionContext implements AdaptiveTrackProjectionContext {
    private static final Logger logger = Logger.getLogger(GenericAdaptiveTrackProjectionContext.class);

    private final long ssrc;

    private boolean needsKeyframe = true;

    private final MediaFormat format;

    private int maxDestinationSequenceNumber;

    private long timestampDelta;

    private boolean timestampDeltaInitialized = false;

    private long maxDestinationTimestamp;

    private int sequenceNumberDelta;

    private final Object transmittedSyncRoot = new Object();

    private long transmittedBytes;

    private long transmittedPackets;

    GenericAdaptiveTrackProjectionContext(@NotNull MediaFormat format, @NotNull RtpState rtpState) {
        this.format = format;
        this.ssrc = rtpState.ssrc;
        this.transmittedBytes = rtpState.transmittedBytes;
        this.transmittedPackets = rtpState.transmittedPackets;
        this.maxDestinationSequenceNumber = rtpState.maxSequenceNumber;
        this.maxDestinationTimestamp = rtpState.maxTimestamp;
    }

    public synchronized boolean accept(@NotNull RawPacket rtpPacket, int incomingIndex, int targetIndex) {
        boolean accept;
        if (targetIndex == -1) {
            this.needsKeyframe = true;
            return false;
        }
        int sourceSequenceNumber = rtpPacket.getSequenceNumber();
        if (this.needsKeyframe) {
            if (isKeyframe(rtpPacket, this.format)) {
                this.needsKeyframe = false;
                int destinationSequenceNumber = this.maxDestinationSequenceNumber + 1;
                this.sequenceNumberDelta = RTPUtils.getSequenceNumberDelta(destinationSequenceNumber, sourceSequenceNumber);
                if (logger.isDebugEnabled())
                    logger.debug("delta ssrc=" + rtpPacket.getSSRCAsLong() + ",src_sequence=" + sourceSequenceNumber + ",dst_sequence=" + destinationSequenceNumber + ",max_sequence=" + this.maxDestinationSequenceNumber + ",delta=" + this.sequenceNumberDelta);
                accept = true;
            } else {
                accept = false;
            }
        } else {
            accept = true;
        }
        if (accept) {
            maybeInitializeTimestampDelta(rtpPacket.getTimestamp());
            int destinationSequenceNumber = computeDestinationSequenceNumber(sourceSequenceNumber);
            long destinationTimestamp = computeDestinationTimestamp(rtpPacket.getTimestamp());
            if (RTPUtils.isOlderSequenceNumberThan(this.maxDestinationSequenceNumber, destinationSequenceNumber))
                this.maxDestinationSequenceNumber = destinationSequenceNumber;
            if (RTPUtils.isNewerTimestampThan(destinationSequenceNumber, this.maxDestinationTimestamp))
                this.maxDestinationTimestamp = destinationTimestamp;
            if (logger.isDebugEnabled())
                logger.debug("accept ssrc=" + rtpPacket.getSSRCAsLong() + ",src_sequence=" + sourceSequenceNumber + ",dst_sequence=" + destinationSequenceNumber + ",max_sequence=" + this.maxDestinationSequenceNumber);
        } else if (logger.isDebugEnabled()) {
            logger.debug("reject ssrc=" + rtpPacket.getSSRCAsLong() + ",src_sequence=" + sourceSequenceNumber);
        }
        return accept;
    }

    private void maybeInitializeTimestampDelta(long sourceTimestamp) {
        if (this.timestampDeltaInitialized)
            return;
        if (RTPUtils.isNewerTimestampThan(this.maxDestinationSequenceNumber, sourceTimestamp)) {
            long destinationTimestamp = this.maxDestinationTimestamp + 3000L & 0xFFFFFFFFL;
            this
                    .timestampDelta = RTPUtils.rtpTimestampDiff(destinationTimestamp, sourceTimestamp);
        }
        this.timestampDeltaInitialized = true;
    }

    private static boolean isKeyframe(@NotNull RawPacket rtpPacket, @NotNull MediaFormat format) {
        byte[] buf = rtpPacket.getBuffer();
        int payloadOff = rtpPacket.getPayloadOffset();
        int payloadLen = rtpPacket.getPayloadLength();
        if ("VP8".equalsIgnoreCase(format.getEncoding()))
            return
                    DePacketizer.isKeyFrame(buf, payloadOff, payloadLen);
        if ("h264".equalsIgnoreCase(format.getEncoding()))
            return
                    DePacketizer.isKeyFrame(buf, payloadOff, payloadLen);
        if ("VP9".equalsIgnoreCase(format.getEncoding()))
            return
                    DePacketizer.isKeyFrame(buf, payloadOff, payloadLen);
        return false;
    }

    public boolean needsKeyframe() {
        return this.needsKeyframe;
    }

    public RawPacket[] rewriteRtp(@NotNull RawPacket rtpPacket, RawPacketCache incomingRawPacketCache) {
        int sourceSequenceNumber = rtpPacket.getSequenceNumber();
        int destinationSequenceNumber = computeDestinationSequenceNumber(sourceSequenceNumber);
        if (sourceSequenceNumber != destinationSequenceNumber)
            rtpPacket.setSequenceNumber(destinationSequenceNumber);
        long sourceTimestamp = rtpPacket.getTimestamp();
        long destinationTimestamp = computeDestinationTimestamp(sourceTimestamp);
        if (sourceTimestamp != destinationTimestamp)
            rtpPacket.setTimestamp(destinationTimestamp);
        if (logger.isDebugEnabled())
            logger.debug("rewrite ssrc=" + rtpPacket.getSSRCAsLong() + ",src_sequence=" + sourceSequenceNumber + ",dst_sequence=" + destinationSequenceNumber + ",max_sequence=" + this.maxDestinationSequenceNumber);
        synchronized (this.transmittedSyncRoot) {
            this.transmittedBytes += rtpPacket.getLength();
            this.transmittedPackets++;
        }
        return EMPTY_PACKET_ARR;
    }

    private int computeDestinationSequenceNumber(int sourceSequenceNumber) {
        return (this.sequenceNumberDelta != 0) ? (sourceSequenceNumber + this.sequenceNumberDelta & 0xFFFF) : sourceSequenceNumber;
    }

    private long computeDestinationTimestamp(long sourceTimestamp) {
        return (this.timestampDelta != 0L) ? (sourceTimestamp + this.timestampDelta & 0xFFFFFFFFL) : sourceTimestamp;
    }

    public boolean rewriteRtcp(@NotNull RawPacket rtcpPacket) {
        if (RTCPUtils.getPacketType((ByteArrayBuffer)rtcpPacket) == 200)
            synchronized (this.transmittedSyncRoot) {
                RTCPSenderInfoUtils.setOctetCount((ByteArrayBuffer)rtcpPacket, (int)this.transmittedBytes);
                RTCPSenderInfoUtils.setPacketCount((ByteArrayBuffer)rtcpPacket, (int)this.transmittedPackets);
            }
        return true;
    }

    public RtpState getRtpState() {
        synchronized (this.transmittedSyncRoot) {
            return new RtpState(this.transmittedBytes, this.transmittedPackets, this.ssrc, this.maxDestinationSequenceNumber, this.maxDestinationTimestamp);
        }
    }

    public MediaFormat getFormat() {
        return this.format;
    }
}

