package org.jitsi.videobridge.cc;


import org.jitsi.impl.neomedia.rtp.RawPacketCache;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.format.MediaFormat;

public interface AdaptiveTrackProjectionContext {
    public static final RawPacket[] EMPTY_PACKET_ARR = new RawPacket[0];

    boolean accept(RawPacket paramRawPacket, int paramInt1, int paramInt2);

    boolean needsKeyframe();

    RawPacket[] rewriteRtp(RawPacket paramRawPacket, RawPacketCache paramRawPacketCache) throws RewriteException;

    boolean rewriteRtcp(RawPacket paramRawPacket);

    RtpState getRtpState();

    MediaFormat getFormat();
}
