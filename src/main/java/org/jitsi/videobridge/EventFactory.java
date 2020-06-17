package org.jitsi.videobridge;

import java.util.Dictionary;
import java.util.Hashtable;
import org.ice4j.ice.IceProcessingState;
import org.jitsi.eventadmin.AbstractEventFactory;
import org.jitsi.eventadmin.Event;

public class EventFactory extends AbstractEventFactory {
    public static final String CHANNEL_CREATED_TOPIC = "org/jitsi/videobridge/Channel/CREATED";

    public static final String CHANNEL_EXPIRED_TOPIC = "org/jitsi/videobridge/Channel/EXPIRED";

    public static final String CONFERENCE_CREATED_TOPIC = "org/jitsi/videobridge/Conference/CREATED";

    public static final String CONFERENCE_EXPIRED_TOPIC = "org/jitsi/videobridge/Conference/EXPIRED";

    public static final String CONTENT_CREATED_TOPIC = "org/jitsi/videobridge/Content/CREATED";

    public static final String CONTENT_EXPIRED_TOPIC = "org/jitsi/videobridge/Content/EXPIRED";

    public static final String ENDPOINT_CREATED_TOPIC = "org/jitsi/videobridge/Endpoint/CREATED";

    public static final String MSG_TRANSPORT_READY_TOPIC = "org/jitsi/videobridge/Endpoint/MSG_TRANSPORT_READY_TOPIC";

    public static final String STREAM_STARTED_TOPIC = "org/jitsi/videobridge/Endpoint/STREAM_STARTED";

    public static final String TRANSPORT_CHANNEL_ADDED_TOPIC = "org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANNEL_ADDED";

    public static final String TRANSPORT_CHANNEL_REMOVED_TOPIC = "org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANNEL_REMOVED";

    public static final String TRANSPORT_CONNECTED_TOPIC = "org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANNEL_CONNECTED";

    public static final String TRANSPORT_CREATED_TOPIC = "org/jitsi/videobridge/IceUdpTransportManager/CREATED";

    public static final String TRANSPORT_STATE_CHANGED_TOPIC = "org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANGED";

    public static Event channelCreated(Channel channel) {
        return new Event("org/jitsi/videobridge/Channel/CREATED", makeProperties(channel));
    }

    public static Event channelExpired(Channel channel) {
        return new Event("org/jitsi/videobridge/Channel/EXPIRED", makeProperties(channel));
    }

    public static Event conferenceCreated(Conference conference) {
        return new Event("org/jitsi/videobridge/Conference/CREATED", makeProperties(conference));
    }

    public static Event conferenceExpired(Conference conference) {
        return new Event("org/jitsi/videobridge/Conference/EXPIRED", makeProperties(conference));
    }

    public static Event contentCreated(Content content) {
        return new Event("org/jitsi/videobridge/Content/CREATED", makeProperties(content));
    }

    public static Event contentExpired(Content content) {
        return new Event("org/jitsi/videobridge/Content/EXPIRED", makeProperties(content));
    }

    public static Event endpointCreated(AbstractEndpoint endpoint) {
        return new Event("org/jitsi/videobridge/Endpoint/CREATED", makeProperties(endpoint));
    }

    public static Event endpointDisplayNameChanged(AbstractEndpoint endpoint) {
        return new Event("org/jitsi/videobridge/Endpoint/NAME_CHANGED",

                makeProperties(endpoint));
    }

    public static Event endpointMessageTransportReady(AbstractEndpoint endpoint) {
        Dictionary<String, Object> properties = new Hashtable<>(1);
        properties.put("event.source", endpoint);
        return new Event("org/jitsi/videobridge/Endpoint/MSG_TRANSPORT_READY_TOPIC", properties);
    }

    public static Event streamStarted(RtpChannel rtpChannel) {
        return new Event("org/jitsi/videobridge/Endpoint/STREAM_STARTED", makeProperties(rtpChannel));
    }

    public static Event transportChannelAdded(Channel channel) {
        return new Event("org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANNEL_ADDED",

                makeProperties(channel));
    }

    public static Event transportChannelRemoved(Channel channel) {
        return new Event("org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANNEL_REMOVED",

                makeProperties(channel));
    }

    public static Event transportConnected(IceUdpTransportManager transportManager) {
        return new Event("org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANNEL_CONNECTED",

                makeProperties(transportManager));
    }

    public static Event transportCreated(IceUdpTransportManager transportManager) {
        return new Event("org/jitsi/videobridge/IceUdpTransportManager/CREATED",

                makeProperties(transportManager));
    }

    public static Event transportStateChanged(IceUdpTransportManager transportManager, IceProcessingState oldState, IceProcessingState newState) {
        Dictionary<String, Object> properties = new Hashtable<>(3);
        properties.put("event.source", transportManager);
        properties.put("oldState", oldState);
        properties.put("newState", newState);
        return new Event("org/jitsi/videobridge/IceUdpTransportManager/TRANSPORT_CHANGED", properties);
    }
}
