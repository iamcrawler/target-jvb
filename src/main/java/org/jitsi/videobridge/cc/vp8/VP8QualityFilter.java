package org.jitsi.videobridge.cc.vp8;


import org.jetbrains.annotations.NotNull;
import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.utils.logging.Logger;

class VP8QualityFilter {
    private static final Logger logger = Logger.getLogger(VP8QualityFilter.class);

    private static final int MIN_KEY_FRAME_WAIT_MS = 300;

    private static final int SUSPENDED_LAYER_ID = -1;

    private long mostRecentKeyframeGroupArrivalTimeMs = -1L;

    private boolean needsKeyframe = false;

    private int internalSpatialLayerIdTarget = -1;

    private int currentSpatialLayerId = -1;

    boolean needsKeyframe() {
        return this.needsKeyframe;
    }

    synchronized boolean acceptFrame(@NotNull RawPacket firstPacketOfFrame, int incomingIndex, int externalTargetIndex, long nowMs) {
        int externalTemporalLayerIdTarget = getTemporalLayerId(externalTargetIndex);
        int externalSpatialLayerIdTarget = getSpatialLayerId(externalTargetIndex);
        if (externalSpatialLayerIdTarget != this.internalSpatialLayerIdTarget) {
            this.internalSpatialLayerIdTarget = externalSpatialLayerIdTarget;
            if (externalSpatialLayerIdTarget > -1)
                this.needsKeyframe = true;
        }
        if (externalSpatialLayerIdTarget < 0 || externalTemporalLayerIdTarget < 0) {
            this.currentSpatialLayerId = -1;
            return false;
        }
        byte[] buf = firstPacketOfFrame.getBuffer();
        int payloadOff = firstPacketOfFrame.getPayloadOffset();
        int payloadLen = firstPacketOfFrame.getPayloadLength();
        int temporalLayerIdOfFrame = DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(buf, payloadOff, payloadLen);
        if (temporalLayerIdOfFrame < 0)
            temporalLayerIdOfFrame = 0;
        int spatialLayerId = getSpatialLayerId(incomingIndex);
        if (DePacketizer.isKeyFrame(buf, payloadOff, payloadLen))
            return acceptKeyframe(spatialLayerId, nowMs);
        if (this.currentSpatialLayerId > -1) {
            if (!isInSwitchingPhase(nowMs) &&
                    isPossibleToSwitch(firstPacketOfFrame, spatialLayerId))
                this.needsKeyframe = true;
            if (this.currentSpatialLayerId > externalSpatialLayerIdTarget)
                return (temporalLayerIdOfFrame < 1);
            if (this.currentSpatialLayerId < externalSpatialLayerIdTarget)
                return true;
            return (temporalLayerIdOfFrame <= externalTemporalLayerIdTarget);
        }
        return false;
    }

    private synchronized boolean isInSwitchingPhase(long nowMs) {
        long deltaMs = nowMs - this.mostRecentKeyframeGroupArrivalTimeMs;
        return (deltaMs <= 300L);
    }

    private synchronized boolean isPossibleToSwitch(@NotNull RawPacket firstPacketOfFrame, int spatialLayerId) {
        if (spatialLayerId == -1)
            return false;
        if (spatialLayerId > this.currentSpatialLayerId && this.currentSpatialLayerId < this.internalSpatialLayerIdTarget)
            return true;
        if (spatialLayerId < this.currentSpatialLayerId && this.currentSpatialLayerId > this.internalSpatialLayerIdTarget)
            return true;
        return false;
    }

    private synchronized boolean acceptKeyframe(int spatialLayerIdOfKeyframe, long nowMs) {
        if (spatialLayerIdOfKeyframe < 0)
            return false;
        if (logger.isDebugEnabled())
            logger.debug("Received a keyframe of spatial layer: " + spatialLayerIdOfKeyframe);
        this.needsKeyframe = false;
        if (!isInSwitchingPhase(nowMs)) {
            this.mostRecentKeyframeGroupArrivalTimeMs = nowMs;
            if (logger.isDebugEnabled())
                logger.debug("First keyframe in this kf group currentSpatialLayerId: " + spatialLayerIdOfKeyframe + ". Target is " + this.internalSpatialLayerIdTarget);
            if (spatialLayerIdOfKeyframe <= this.internalSpatialLayerIdTarget) {
                this.currentSpatialLayerId = spatialLayerIdOfKeyframe;
                return true;
            }
            return false;
        }
        if (this.currentSpatialLayerId <= spatialLayerIdOfKeyframe && spatialLayerIdOfKeyframe <= this.internalSpatialLayerIdTarget) {
            this.currentSpatialLayerId = spatialLayerIdOfKeyframe;
            if (logger.isDebugEnabled())
                logger.debug("Upscaling to spatial layer " + spatialLayerIdOfKeyframe + ". The target is " + this.internalSpatialLayerIdTarget);
            return true;
        }
        if (spatialLayerIdOfKeyframe <= this.internalSpatialLayerIdTarget && this.internalSpatialLayerIdTarget < this.currentSpatialLayerId) {
            this.currentSpatialLayerId = spatialLayerIdOfKeyframe;
            if (logger.isDebugEnabled())
                logger.debug("Downscaling to spatial layer " + spatialLayerIdOfKeyframe + ". The target is + " + this.internalSpatialLayerIdTarget);
            return true;
        }
        return false;
    }

    private static int getTemporalLayerId(int index) {
        return (index > -1) ? (index % 3) : -1;
    }

    private static int getSpatialLayerId(int index) {
        return (index > -1) ? (index / 3) : -1;
    }
}
