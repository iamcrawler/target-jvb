package org.jitsi.videobridge;

import java.util.Collection;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

public class EndpointMessageBuilder {
    public static final String COLIBRI_CLASS_CLIENT_HELLO = "ClientHello";

    public static final String COLIBRI_CLASS_DOMINANT_SPEAKER_CHANGE = "DominantSpeakerEndpointChangeEvent";

    public static final String COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS = "EndpointConnectivityStatusChangeEvent";

    public static final String COLIBRI_CLASS_ENDPOINT_MESSAGE = "EndpointMessage";

    public static final String COLIBRI_CLASS_LASTN_CHANGED = "LastNChangedEvent";

    public static final String COLIBRI_CLASS_LASTN_ENDPOINTS_CHANGED = "LastNEndpointsChangeEvent";

    public static final String COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED = "PinnedEndpointChangedEvent";

    public static final String COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED = "PinnedEndpointsChangedEvent";

    public static final String COLIBRI_CLASS_RECEIVER_VIDEO_CONSTRAINT = "ReceiverVideoConstraint";

    public static final String COLIBRI_CLASS_SELECTED_ENDPOINT_CHANGED = "SelectedEndpointChangedEvent";

    public static final String COLIBRI_CLASS_SELECTED_ENDPOINTS_CHANGED = "SelectedEndpointsChangedEvent";

    public static final String COLIBRI_CLASS_SELECTED_UPDATE = "SelectedUpdateEvent";

    public static final String COLIBRI_CLASS_SERVER_HELLO = "ServerHello";

    public static String createDominantSpeakerEndpointChangeEvent(String endpoint) {
        return "{\"colibriClass\":\"DominantSpeakerEndpointChangeEvent\",\"dominantSpeakerEndpoint\":\"" +

                JSONValue.escape(endpoint) + "\"}";
    }

    public static String createEndpointConnectivityStatusChangeEvent(String endpointId, boolean connected) {
        return "{\"colibriClass\":\"EndpointConnectivityStatusChangeEvent\",\"endpoint\":\"" +

                JSONValue.escape(endpointId) + "\", \"active\":\"" +
                String.valueOf(connected) + "\"}";
    }

    public static String createServerHelloEvent() {
        return "{\"colibriClass\":\"ServerHello\"}";
    }

    public static String createLastNEndpointsChangeEvent(Collection<String> forwardedEndpoints, Collection<String> endpointsEnteringLastN, Collection<String> conferenceEndpoints) {
        StringBuilder msg = new StringBuilder("{\"colibriClass\":\"LastNEndpointsChangeEvent\"");
        msg.append(",\"lastNEndpoints\":");
        msg.append(getJsonString(forwardedEndpoints));
        msg.append(",\"endpointsEnteringLastN\":");
        msg.append(getJsonString(endpointsEnteringLastN));
        msg.append(",\"conferenceEndpoints\":");
        msg.append(getJsonString(conferenceEndpoints));
        msg.append('}');
        return msg.toString();
    }

    public static String createSelectedUpdateMessage(boolean isSelected) {
        JSONObject selectedUpdate = new JSONObject();
        selectedUpdate.put("colibriClass", "SelectedUpdateEvent");
        selectedUpdate.put("isSelected", Boolean.valueOf(isSelected));
        return selectedUpdate.toJSONString();
    }

    private static String getJsonString(Collection<String> strings) {
        JSONArray array = new JSONArray();
        if (strings != null && !strings.isEmpty())
            array.addAll(strings);
        return array.toString();
    }
}
