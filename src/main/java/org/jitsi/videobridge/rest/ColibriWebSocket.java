package org.jitsi.videobridge.rest;

import java.util.Objects;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.Endpoint;

public class ColibriWebSocket extends WebSocketAdapter {
    private static final Logger logger = Logger.getLogger(ColibriWebSocket.class);

    private ColibriWebSocketServlet servlet;

    private final Endpoint endpoint;

    ColibriWebSocket(ColibriWebSocketServlet servlet, Endpoint endpoint) {
        this.servlet = servlet;
        this.endpoint = Objects.<Endpoint>requireNonNull(endpoint, "endpoint");
    }

    public void onWebSocketText(String message) {
        if (logger.isDebugEnabled())
            logger.debug("Received text from " + this.endpoint.getID() + ": " + message);
        this.endpoint.onWebSocketText(this, message);
    }

    public void onWebSocketConnect(Session sess) {
        super.onWebSocketConnect(sess);
        this.endpoint.onWebSocketConnect(this);
    }

    public void onWebSocketClose(int statusCode, String reason) {
        this.endpoint.onWebSocketClose(this, statusCode, reason);
    }
}
