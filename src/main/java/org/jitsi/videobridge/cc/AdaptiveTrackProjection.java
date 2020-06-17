package org.jitsi.videobridge.cc;


import java.lang.ref.WeakReference;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackReceiver;
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc;
import org.jitsi.impl.neomedia.rtp.RawPacketCache;
import org.jitsi.impl.neomedia.rtp.translator.RTPTranslatorImpl;
import org.jitsi.impl.neomedia.transform.CachingTransformer;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.cc.vp8.VP8AdaptiveTrackProjectionContext;

public class AdaptiveTrackProjection {
    private static final Logger logger = Logger.getLogger(AdaptiveTrackProjection.class);

    public static final RawPacket[] EMPTY_PACKET_ARR = AdaptiveTrackProjectionContext.EMPTY_PACKET_ARR;

    private final WeakReference<MediaStreamTrackDesc> weakSource;

    private final long targetSsrc;

    private AdaptiveTrackProjectionContext context;

    private int contextPayloadType = -1;

    private int idealIndex = -1;

    private int targetIndex = -1;

    AdaptiveTrackProjection(@NotNull MediaStreamTrackDesc source) {
        this.weakSource = new WeakReference<MediaStreamTrackDesc>(source);
        this.targetSsrc = source.getRTPEncodings()[0].getPrimarySSRC();
    }

    public MediaStreamTrackDesc getSource() {
        return this.weakSource.get();
    }

    int getIdealIndex() {
        return this.idealIndex;
    }

    void setIdealIndex(int value) {
        this.idealIndex = value;
    }

    int getTargetIndex() {
        return this.targetIndex;
    }

    void setTargetIndex(int value) {
        this.targetIndex = value;
    }

    public boolean accept(@NotNull RawPacket rtpPacket) {
        AdaptiveTrackProjectionContext contextCopy = getContext(rtpPacket);
        RTPEncodingDesc encoding = getSource().getMediaStreamTrackReceiver().findRTPEncodingDesc(rtpPacket);
        if (encoding == null) {
            MediaStreamTrackDesc sourceTrack = getSource();
            if ("VP8".equalsIgnoreCase(contextCopy.getFormat().getEncoding()))
                if (sourceTrack != null) {
                    MediaStreamTrackReceiver mediaStreamTrackReceiver = sourceTrack.getMediaStreamTrackReceiver();
                    logger.warn("Dropping an RTP packet, because egress was unable to find an encoding for raw packet " + mediaStreamTrackReceiver

                            .getStream().packetToString(rtpPacket) + ". Ingress is aware of these tracks: " +

                            Arrays.toString((Object[])mediaStreamTrackReceiver
                                    .getMediaStreamTracks()));
                } else {
                    logger.warn("Dropping an RTP packet, because egress was unable to find an encoding for raw packet " + rtpPacket);
                }
            return false;
        }
        int targetIndexCopy = this.targetIndex;
        boolean accept = contextCopy.accept(rtpPacket, encoding
                .getIndex(), targetIndexCopy);
        if (contextCopy.needsKeyframe() && targetIndexCopy > -1) {
            MediaStreamTrackDesc source = getSource();
            if (source != null)
                ((RTPTranslatorImpl)source
                        .getMediaStreamTrackReceiver()
                        .getStream()
                        .getRTPTranslator())
                        .getRtcpFeedbackMessageSender()
                        .requestKeyframe(this.targetSsrc);
        }
        return accept;
    }

    private synchronized AdaptiveTrackProjectionContext getContext(@NotNull RawPacket rtpPacket) {
        MediaFormat format;
        int payloadType = rtpPacket.getPayloadType();
        if (this.context == null || this.contextPayloadType != payloadType) {
            MediaStreamTrackDesc source = getSource();
            format = (MediaFormat)source.getMediaStreamTrackReceiver().getStream().getDynamicRTPPayloadTypes().get(Byte.valueOf((byte)payloadType));
        } else {
            format = this.context.getFormat();
        }
        if ("VP8".equalsIgnoreCase(format.getEncoding())) {
            byte[] buf = rtpPacket.getBuffer();
            int payloadOffset = rtpPacket.getPayloadOffset();
            int payloadLen = rtpPacket.getPayloadLength();
            boolean hasTemporalLayerIndex = (DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(buf, payloadOffset, payloadLen) > -1);
            if (hasTemporalLayerIndex && !(this.context instanceof VP8AdaptiveTrackProjectionContext)) {
                this.context = (AdaptiveTrackProjectionContext)new VP8AdaptiveTrackProjectionContext(format, getRtpState());
                this.contextPayloadType = payloadType;
            } else if (!hasTemporalLayerIndex && !(this.context instanceof GenericAdaptiveTrackProjectionContext)) {
                this.context = new GenericAdaptiveTrackProjectionContext(format, getRtpState());
                this.contextPayloadType = payloadType;
            }
            return this.context;
        }
        if (this.context == null || this.contextPayloadType != payloadType) {
            this.context = new GenericAdaptiveTrackProjectionContext(format, getRtpState());
            this.contextPayloadType = payloadType;
            return this.context;
        }
        return this.context;
    }

    private RtpState getRtpState() {
        if (this.context == null) {
            MediaStreamTrackDesc track = getSource();
            long ssrc = track.getRTPEncodings()[0].getPrimarySSRC();
            return new RtpState(0L, 0L, ssrc, 1, 1L);
        }
        return this.context.getRtpState();
    }

    RawPacket[] rewriteRtp(@NotNull RawPacket rtpPacket) throws RewriteException {
        AdaptiveTrackProjectionContext contextCopy = this.context;
        if (contextCopy == null)
            return EMPTY_PACKET_ARR;
        RawPacketCache incomingRawPacketCache = null;
        MediaStreamTrackDesc source = getSource();
        if (source != null) {
            MediaStreamImpl stream = source.getMediaStreamTrackReceiver().getStream();
            if (stream != null) {
                CachingTransformer cachingTransformer = stream.getCachingTransformer();
                if (cachingTransformer != null) {
                    incomingRawPacketCache = cachingTransformer.getIncomingRawPacketCache();
                } else {
                    logger.warn("incoming packet cache is null.");
                }
            } else {
                logger.warn("stream is null.");
            }
        }
        return contextCopy.rewriteRtp(rtpPacket, incomingRawPacketCache);
    }

    public boolean rewriteRtcp(@NotNull RawPacket rtcpPacket) {
        AdaptiveTrackProjectionContext contextCopy = this.context;
        if (contextCopy == null)
            return true;
        return contextCopy.rewriteRtcp(rtcpPacket);
    }

    public long getSSRC() {
        return this.targetSsrc;
    }
}

