package org.jitsi.videobridge.octo;


import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.service.neomedia.AudioMediaStream;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.event.CsrcAudioLevelListener;
import org.jitsi.util.RTCPUtils;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.AudioChannelAudioLevelListener;
import org.jitsi.videobridge.Channel;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.Content;
import org.jitsi.videobridge.RtpChannel;
import org.jitsi.videobridge.RtpChannelDatagramFilter;
import org.jitsi.videobridge.xmpp.MediaStreamTrackFactory;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension;

public class OctoChannel extends RtpChannel {
    private static final Logger classLogger = Logger.getLogger(OctoChannel.class);

    private static final int OCTO_EXPIRE = 7200000;

    private final String conferenceId;

    private final MediaType mediaType;

    private OctoTransportManager transportManager;

    private RtpChannelDatagramFilter rtpFilter = new OctoDatagramPacketFilter(false);

    private RtpChannelDatagramFilter rtcpFilter = new OctoDatagramPacketFilter(true);

    private final Logger logger;

    private final OctoEndpoints octoEndpoints;

    private final boolean handleData;

    public OctoChannel(Content content, String id) {
        super(content, id, null, "http://jitsi.org/octo",

                Boolean.valueOf(false));
        Conference conference = content.getConference();
        this.conferenceId = conference.getGid();
        this.mediaType = content.getMediaType();
        this.octoEndpoints = conference.getOctoEndpoints();
        this.octoEndpoints.setChannel(this.mediaType, this);
        this.handleData = MediaType.VIDEO.equals(this.mediaType);
        this
                .logger = Logger.getLogger(classLogger, content.getConference().getLogger());
        setExpire(7200000);
    }

    public void describe(ColibriConferenceIQ.ChannelCommon commonIq) {
        super.describe(commonIq);
        commonIq.setType("octo");
    }

    public String getConferenceId() {
        return this.conferenceId;
    }

    public void setRelayIds(List<String> relayIds) {
        OctoTransportManager transportManager = getOctoTransportManager();
        transportManager.setRelayIds(relayIds);
    }

    public boolean setRtpEncodingParameters(List<SourcePacketExtension> sources, List<SourceGroupPacketExtension> sourceGroups) {
        boolean changed = super.setRtpEncodingParameters(sources, sourceGroups);
        if (changed && this.octoEndpoints != null) {
            this.octoEndpoints.updateEndpoints(
                    (Set<String>)Arrays.<MediaStreamTrackDesc>stream(
                            getStream().getMediaStreamTrackReceiver()
                                    .getMediaStreamTracks())
                            .map(MediaStreamTrackDesc::getOwner)
                            .collect(Collectors.toSet()));
            for (SourcePacketExtension s : sources) {
                if (MediaStreamTrackFactory.getOwner(s) == null)
                    this.logger.warn("Received a source without an owner tag.");
            }
        }
        return changed;
    }

    private OctoTransportManager getOctoTransportManager() {
        if (this.transportManager == null)
            this.transportManager = (OctoTransportManager)getTransportManager();
        return this.transportManager;
    }

    public MediaType getMediaType() {
        return this.mediaType;
    }

    public RtpChannelDatagramFilter getDatagramFilter(boolean rtcp) {
        return rtcp ? this.rtcpFilter : this.rtpFilter;
    }

    protected void configureStream(MediaStream stream) {
        if (stream != null && stream instanceof AudioMediaStream)
            ((AudioMediaStream)stream)
                    .setCsrcAudioLevelListener((CsrcAudioLevelListener)new AudioChannelAudioLevelListener(this));
    }

    protected void removeStreamListeners() {
        MediaStream stream = getStream();
        if (stream != null && stream instanceof AudioMediaStream)
            ((AudioMediaStream)stream).setCsrcAudioLevelListener(null);
    }

    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p) {
        super.acceptDataInputStreamDatagramPacket(p);
        touch(Channel.ActivityType.PAYLOAD);
        return true;
    }

    protected boolean acceptControlInputStreamDatagramPacket(DatagramPacket p) {
        super.acceptControlInputStreamDatagramPacket(p);
        touch(Channel.ActivityType.PAYLOAD);
        return true;
    }

    private class OctoDatagramPacketFilter extends RtpChannelDatagramFilter {
        private boolean rtcp;

        private OctoDatagramPacketFilter(boolean rtcp) {
            super(OctoChannel.this, rtcp);
            this.rtcp = rtcp;
        }

        public boolean accept(DatagramPacket p) {
            String packetCid = OctoPacket.readConferenceId(p
                    .getData(), p.getOffset(), p.getLength());
            if (!packetCid.equals(OctoChannel.this.conferenceId))
                return false;
            MediaType packetMediaType = OctoPacket.readMediaType(p
                    .getData(), p.getOffset(), p.getLength());
            if (OctoChannel.this.mediaType.equals(packetMediaType)) {
                boolean packetIsRtcp = RTCPUtils.isRtcp(p
                        .getData(), p
                        .getOffset() + 8, p
                        .getLength() - 8);
                return (this.rtcp == packetIsRtcp);
            }
            if (MediaType.DATA.equals(packetMediaType) && OctoChannel.this.handleData)
                OctoChannel.this.handleDataPacket(p);
            return false;
        }
    }

    private void handleDataPacket(DatagramPacket p) {
        byte[] msgBytes = new byte[p.getLength() - 8];
        System.arraycopy(p
                .getData(), p.getOffset() + 8, msgBytes, 0, msgBytes.length);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);
        if (this.logger.isDebugEnabled())
            this.logger.debug("Received a message in an Octo data packet: " + msg);
        this.octoEndpoints.messageTransport.onMessage(this, msg);
    }

    public AbstractEndpoint getEndpoint(long ssrc) {
        return (this.octoEndpoints == null) ? null : this.octoEndpoints.findEndpoint(ssrc);
    }

    void sendMessage(String msg, String sourceEndpointId) {
        getOctoTransportManager()
                .sendMessage(msg, sourceEndpointId, getConferenceId());
    }

    protected void updatePacketsAndBytes(Conference.Statistics conferenceStatistics) {
        if (conferenceStatistics != null) {
            conferenceStatistics.totalBytesReceivedOcto
                    .addAndGet(this.statistics.bytesReceived);
            conferenceStatistics.totalBytesSentOcto
                    .addAndGet(this.statistics.bytesSent);
            conferenceStatistics.totalPacketsReceivedOcto
                    .addAndGet(this.statistics.packetsReceived);
            conferenceStatistics.totalPacketsSentOcto
                    .addAndGet(this.statistics.packetsSent);
        }
    }

    public boolean expire() {
        if (super.expire()) {
            this.octoEndpoints.setChannel(getMediaType(), null);
            return true;
        }
        return false;
    }

    public void setExpire(int expire) {
        if (expire > 0)
            expire = Math.max(expire, 7200000);
        super.setExpire(expire);
    }
}

