package org.jitsi.videobridge.octo;


import java.util.List;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.AbstractEndpointMessageTransport;
import org.jitsi.videobridge.Conference;
import org.json.simple.JSONObject;

public class OctoEndpointMessageTransport extends AbstractEndpointMessageTransport {
    private static final Logger logger = Logger.getLogger(OctoEndpointMessageTransport.class);

    private final OctoEndpoints octoEndpoints;

    public OctoEndpointMessageTransport(OctoEndpoints octoEndpoints) {
        super(null);
        this.octoEndpoints = octoEndpoints;
    }

    protected Conference getConference() {
        return this.octoEndpoints.getConference();
    }

    protected String getId(Object id) {
        if (id == null || !(id instanceof String))
            return null;
        return (String)id;
    }

    protected void sendMessageToEndpoints(String msg, List<AbstractEndpoint> endpoints) {
        Conference conference = getConference();
        if (conference != null)
            conference.sendMessage(msg, endpoints, false);
    }

    protected void onSelectedEndpointChangedEvent(Object src, JSONObject jsonObject) {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    protected void onSelectedEndpointsChangedEvent(Object src, JSONObject jsonObject) {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    protected void onPinnedEndpointChangedEvent(Object src, JSONObject jsonObject) {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    protected void onPinnedEndpointsChangedEvent(Object src, JSONObject jsonObject) {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    protected void onClientHello(Object src, JSONObject jsonObject) {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    protected void onReceiverVideoConstraintEvent(Object src, JSONObject jsonObject) {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    private void logUnexpectedMessage(String msg) {
        logger.warn("Received an unexpected message type through Octo: " + msg);
    }
}
