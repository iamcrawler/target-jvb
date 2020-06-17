package org.jitsi.videobridge;


import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public abstract class AbstractEndpointMessageTransport {
    private static final Logger classLogger = Logger.getLogger(AbstractEndpointMessageTransport.class);

    protected final AbstractEndpoint endpoint;

    private final Logger logger;

    public AbstractEndpointMessageTransport(AbstractEndpoint endpoint) {
        this.endpoint = endpoint;
        this
                .logger = Logger.getLogger(classLogger, (endpoint == null) ? null : endpoint

                .getConference().getLogger());
    }

    protected void notifyTransportChannelConnected() {}

    protected void onClientHello(Object src, JSONObject jsonObject) {}

    private void onJSONData(Object src, JSONObject jsonObject, String colibriClass) {
        switch (colibriClass) {
            case "SelectedEndpointChangedEvent":
                onSelectedEndpointChangedEvent(src, jsonObject);
                return;
            case "SelectedEndpointsChangedEvent":
                onSelectedEndpointsChangedEvent(src, jsonObject);
                return;
            case "PinnedEndpointChangedEvent":
                onPinnedEndpointChangedEvent(src, jsonObject);
                return;
            case "PinnedEndpointsChangedEvent":
                onPinnedEndpointsChangedEvent(src, jsonObject);
                return;
            case "ClientHello":
                onClientHello(src, jsonObject);
                return;
            case "EndpointMessage":
                onClientEndpointMessage(src, jsonObject);
                return;
            case "LastNChangedEvent":
                onLastNChangedEvent(src, jsonObject);
                return;
            case "ReceiverVideoConstraint":
                onReceiverVideoConstraintEvent(src, jsonObject);
                return;
        }
        this.logger.info("Received a message with unknown colibri class: " + colibriClass);
    }

    private void onClientEndpointMessage(Object src, JSONObject jsonObject) {
        List<AbstractEndpoint> endpointSubset;
        String to = (String)jsonObject.get("to");
        jsonObject.put("from", getId(jsonObject.get("from")));
        Conference conference = getConference();
        if (conference == null || conference.isExpired()) {
            this.logger.warn("Unable to send EndpointMessage, conference is null or expired");
            return;
        }
        if ("".equals(to)) {
            endpointSubset = new LinkedList<>(conference.getEndpoints());
            endpointSubset.removeIf(e -> e.getID().equalsIgnoreCase(getId()));
        } else {
            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint != null) {
                endpointSubset = Collections.singletonList(targetEndpoint);
            } else {
                endpointSubset = Collections.emptyList();
                this.logger.warn("Unable to find endpoint " + to + " to send EndpointMessage");
            }
        }
        sendMessageToEndpoints(jsonObject.toString(), endpointSubset);
    }

    protected void sendMessageToEndpoints(String msg, List<AbstractEndpoint> endpoints) {
        Conference conference = getConference();
        if (conference != null)
            conference.sendMessage(msg, endpoints, true);
    }

    protected Conference getConference() {
        return (this.endpoint != null) ? this.endpoint.getConference() : null;
    }

    private String getId() {
        return getId(null);
    }

    protected String getId(Object id) {
        return (this.endpoint != null) ? this.endpoint.getID() : null;
    }

    protected void onLastNChangedEvent(Object src, JSONObject jsonObject) {
        Object o = jsonObject.get("lastN");
        if (!(o instanceof Number))
            return;
        int lastN = ((Number)o).intValue();
        if (this.endpoint != null)
            for (RtpChannel channel : this.endpoint.getChannels(MediaType.VIDEO))
                channel.setLastN(lastN);
    }

    protected void onReceiverVideoConstraintEvent(Object src, JSONObject jsonObject) {
        Object o = jsonObject.get("maxFrameHeight");
        if (!(o instanceof Number)) {
            this.logger.warn("Received a non-number maxFrameHeight video constraint from " +

                    getId() + ": " + o.toString());
            return;
        }
        int maxFrameHeight = ((Number)o).intValue();
        if (this.logger.isDebugEnabled())
            this.logger.debug("Received a maxFrameHeight video constraint from " +

                    getId() + ": " + maxFrameHeight);
        if (this.endpoint != null)
            for (RtpChannel channel : this.endpoint.getChannels(MediaType.VIDEO)) {
                if (channel instanceof VideoChannel)
                    ((VideoChannel)channel).setMaxFrameHeight(maxFrameHeight);
            }
    }

    public void onMessage(Object src, String msg) {
        Object obj;
        JSONParser parser = new JSONParser();
        try {
            obj = parser.parse(msg);
        } catch (ParseException ex) {
            this.logger.warn("Malformed JSON received from endpoint " +
                    getId(), (Throwable)ex);
            obj = null;
        }
        if (obj instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject)obj;
            String colibriClass = (String)jsonObject.get("colibriClass");
            if (colibriClass != null) {
                onJSONData(src, jsonObject, colibriClass);
            } else {
                this.logger.warn("Malformed JSON received from endpoint " +
                        getId() + ". JSON object does not contain the colibriClass field.");
            }
        }
    }

    protected void sendMessage(String msg) throws IOException {}

    protected void close() {}

    protected abstract void onPinnedEndpointChangedEvent(Object paramObject, JSONObject paramJSONObject);

    protected abstract void onPinnedEndpointsChangedEvent(Object paramObject, JSONObject paramJSONObject);

    protected abstract void onSelectedEndpointChangedEvent(Object paramObject, JSONObject paramJSONObject);

    protected abstract void onSelectedEndpointsChangedEvent(Object paramObject, JSONObject paramJSONObject);
}