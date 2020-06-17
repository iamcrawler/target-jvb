package org.jitsi.videobridge.pubsub;

import org.jivesoftware.smack.packet.IQ;

public interface PubSubResponseListener {
    void onCreateNodeResponse(Response paramResponse);

    void onPublishResponse(Response paramResponse, IQ paramIQ);

    public enum Response {
        SUCCESS, FAIL;
    }
}
