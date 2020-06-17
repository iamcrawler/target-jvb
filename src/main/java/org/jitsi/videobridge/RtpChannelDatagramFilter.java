package org.jitsi.videobridge;


import java.net.DatagramPacket;
import org.ice4j.socket.DTLSDatagramFilter;
import org.ice4j.socket.DatagramPacketFilter;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.util.RTCPUtils;
import org.jitsi.util.RTPUtils;
import org.jitsi.utils.logging.Logger;

public class RtpChannelDatagramFilter implements DatagramPacketFilter {
    private static final Logger logger = Logger.getLogger(RtpChannelDatagramFilter.class);

    private boolean acceptNonRtp = false;

    private final RtpChannel channel;

    private boolean missingPtsWarningLogged = false;

    private final boolean rtcp;

    protected RtpChannelDatagramFilter(RtpChannel channel, boolean rtcp) {
        this(channel, rtcp, false);
    }

    RtpChannelDatagramFilter(RtpChannel channel, boolean rtcp, boolean acceptNonRtp) {
        this.channel = channel;
        this.rtcp = rtcp;
        this.acceptNonRtp = acceptNonRtp;
    }

    public boolean accept(DatagramPacket p) {
        byte[] buf = p.getData();
        int off = p.getOffset();
        int len = p.getLength();
        if (!RawPacket.isRtpRtcp(buf, off, len))
            return (this.acceptNonRtp && DTLSDatagramFilter.isDTLS(p));
        if (RTCPUtils.isRtcp(buf, off, len))
            return (this.rtcp && acceptRTCP(buf, off, len));
        return (!this.rtcp && acceptRTP(RawPacket.getPayloadType(buf, off, len)));
    }

    private boolean acceptRTCP(byte[] data, int off, int len) {
        if (len >= 8) {
            int packetSenderSSRC = RTPUtils.readInt(data, off + 4);
            int[] channelSSRCs = this.channel.getDefaultReceiveSSRCs();
            for (int channelSSRC : channelSSRCs) {
                if (channelSSRC == packetSenderSSRC)
                    return true;
            }
            channelSSRCs = this.channel.getReceiveSSRCs();
            for (int channelSSRC : channelSSRCs) {
                if (channelSSRC == packetSenderSSRC)
                    return true;
            }
        }
        return false;
    }

    private boolean acceptRTP(int pt) {
        int[] channelPTs = this.channel.getReceivePTs();
        if (channelPTs == null || channelPTs.length == 0) {
            if (this.channel.getChannelBundleId() == null)
                return true;
            if (!this.missingPtsWarningLogged) {
                this.missingPtsWarningLogged = true;
                logger.warn("No payload-types specified for channel " + this.channel

                        .getID() + " while bundle is in use. Packets are dropped.");
            }
            return false;
        }
        for (int channelPT : channelPTs) {
            if (channelPT == pt)
                return true;
        }
        return false;
    }

    public void setAcceptNonRtp(boolean acceptNonRtp) {
        this.acceptNonRtp = acceptNonRtp;
    }
}