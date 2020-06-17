package org.jitsi.videobridge.octo;


import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.Conference;

public class OctoEndpoints {
    private static final Logger classLogger = Logger.getLogger(OctoEndpoints.class);

    private Conference conference;

    private OctoChannel audioChannel;

    private OctoChannel videoChannel;

    private final Object endpointsSyncRoot = new Object();

    final OctoEndpointMessageTransport messageTransport = new OctoEndpointMessageTransport(this);

    private final Logger logger;

    public OctoEndpoints(Conference conference) {
        this.conference = conference;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    Conference getConference() {
        return this.conference;
    }

    private void removeAll() {
        synchronized (this.endpointsSyncRoot) {
            List<OctoEndpoint> octoEndpoints = getOctoEndpoints();
            octoEndpoints.forEach(AbstractEndpoint::expire);
        }
    }

    private List<OctoEndpoint> getOctoEndpoints() {
        return (List<OctoEndpoint>)this.conference
                .getEndpoints().stream()
                .filter(e -> e instanceof OctoEndpoint)
                .map(e -> (OctoEndpoint)e)
                .collect(Collectors.toList());
    }

    void setChannel(MediaType mediaType, OctoChannel channel) {
        synchronized (this.endpointsSyncRoot) {
            List<OctoEndpoint> octoEndpoints = getOctoEndpoints();
            if (MediaType.VIDEO.equals(mediaType)) {
                if (this.videoChannel != null) {
                    this.logger.error("Replacing an existing video channel");
                    octoEndpoints.forEach(e -> e.removeChannel(this.videoChannel));
                }
                this.videoChannel = channel;
                if (channel != null)
                    octoEndpoints.forEach(e -> e.addChannel(this.videoChannel));
            } else if (MediaType.AUDIO.equals(mediaType)) {
                if (this.audioChannel != null) {
                    this.logger.error("Replacing an existing audio channel");
                    octoEndpoints.forEach(e -> e.removeChannel(this.audioChannel));
                }
                this.audioChannel = channel;
                if (channel != null)
                    octoEndpoints.forEach(e -> e.addChannel(this.audioChannel));
            } else {
                throw new IllegalArgumentException("mediaType: " + mediaType);
            }
            if (this.videoChannel == null && this.audioChannel == null)
                removeAll();
        }
    }

    void updateEndpoints(Set<String> endpointIds) {
        synchronized (this.endpointsSyncRoot) {
            List<OctoEndpoint> octoEndpoints = getOctoEndpoints();
            List<String> octoEndpointIds = (List<String>)octoEndpoints.stream().map(AbstractEndpoint::getID).collect(Collectors.toList());
            endpointIds.removeAll(octoEndpointIds);
            endpointIds.forEach(this::addEndpoint);
            octoEndpoints.forEach(OctoEndpoint::maybeExpire);
        }
    }

    private OctoEndpoint addEndpoint(String id) {
        OctoEndpoint endpoint;
        synchronized (this.endpointsSyncRoot) {
            endpoint = new OctoEndpoint(this.conference, id);
            if (this.audioChannel != null)
                endpoint.addChannel(this.audioChannel);
            if (this.videoChannel != null)
                endpoint.addChannel(this.videoChannel);
        }
        this.conference.addEndpoint(endpoint);
        return endpoint;
    }

    AbstractEndpoint findEndpoint(long ssrc)
    {
        synchronized (endpointsSyncRoot)
        {
            return
                    getOctoEndpoints()
                            .stream()
                            .filter(e -> e.getMediaStreamTracks().stream().anyMatch(
                                    track -> track.matches(ssrc)))
                            .findFirst().orElse(null);
        }
    }

    public void sendMessage(String msg) {
        OctoChannel channel = this.audioChannel;
        if (channel == null)
            channel = this.videoChannel;
        if (channel != null) {
            channel.sendMessage(msg, null);
        } else {
            this.logger.warn("Can not send a message, no channels.");
        }
    }
}