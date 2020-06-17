package org.jitsi.videobridge;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.jitsi.eventadmin.EventAdmin;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.rest.ColibriWebSocket;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

class EndpointMessageTransport extends AbstractEndpointMessageTransport implements WebRtcDataStream.DataCallback {
    private static final Logger classLogger = Logger.getLogger(EndpointMessageTransport.class);

    private final Endpoint endpoint;

    private final Logger logger;

    private ColibriWebSocket webSocket;

    private final Object webSocketSyncRoot = new Object();

    private boolean webSocketLastActive = false;

    private WeakReference<SctpConnection> sctpConnection = new WeakReference<>(null);

    private WebRtcDataStream writableWebRtcDataStream;

    private final WebRtcDataStreamListener webRtcDataStreamListener = new WebRtcDataStreamListener() {
        public void onChannelOpened(SctpConnection source, WebRtcDataStream channel) {
            SctpConnection currentConnection = EndpointMessageTransport.this.getSctpConnection();
            if (source.equals(currentConnection))
                EndpointMessageTransport.this.hookUpDefaultWebRtcDataChannel(currentConnection);
        }
    };

    EndpointMessageTransport(Endpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
        this
                .logger = Logger.getLogger(classLogger, endpoint
                .getConference().getLogger());
    }

    private void hookUpDefaultWebRtcDataChannel(SctpConnection connection) {
        WebRtcDataStream _defaultStream = (connection != null) ? connection.getDefaultDataStream() : null;
        if (_defaultStream != null) {
            WebRtcDataStream oldDataStream = this.writableWebRtcDataStream;
            this.writableWebRtcDataStream = _defaultStream;
            _defaultStream.setDataCallback(this);
            if (oldDataStream == null) {
                this.logger.info(
                        String.format("WebRTC data channel established for %s", new Object[] { connection.getLoggingId() }));
                notifyTransportChannelConnected();
            }
        }
    }

    protected void notifyTransportChannelConnected() {
        EventAdmin eventAdmin = this.endpoint.getConference().getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.postEvent(
                    EventFactory.endpointMessageTransportReady(this.endpoint));
        this.endpoint.getConference().endpointMessageTransportConnected(this.endpoint);
        for (RtpChannel channel : this.endpoint.getChannels())
            channel.endpointMessageTransportConnected();
    }

    protected void onClientHello(Object src, JSONObject jsonObject) {
        sendMessage(src, EndpointMessageBuilder.createServerHelloEvent(), "response to ClientHello");
    }

    private void sendMessage(Object dst, String message) {
        sendMessage(dst, message, "");
    }

    private void sendMessage(Object dst, String message, String errorMessage) {
        if (dst instanceof WebRtcDataStream) {
            sendMessage((WebRtcDataStream)dst, message, errorMessage);
        } else if (dst instanceof ColibriWebSocket) {
            sendMessage((ColibriWebSocket)dst, message, errorMessage);
        } else {
            throw new IllegalArgumentException("unknown transport:" + dst);
        }
    }

    private void sendMessage(WebRtcDataStream dst, String message, String errorMessage) {
        try {
            dst.sendString(message);
            (this.endpoint.getConference().getVideobridge().getStatistics()).totalDataChannelMessagesSent
                    .incrementAndGet();
        } catch (IOException ioe) {
            this.logger.error("Failed to send a message over a WebRTC data channel (endpoint=" + this.endpoint

                    .getID() + "): " + errorMessage, ioe);
        }
    }

    private void sendMessage(ColibriWebSocket dst, String message, String errorMessage) {
        dst.getRemote().sendStringByFuture(message);
        (this.endpoint.getConference().getVideobridge().getStatistics()).totalColibriWebSocketMessagesSent
                .incrementAndGet();
    }

    protected void onPinnedEndpointChangedEvent(Object src, JSONObject jsonObject) {
        String newPinnedEndpointID = (String)jsonObject.get("pinnedEndpoint");
        Set<String> newPinnedIDs = Collections.EMPTY_SET;
        if (newPinnedEndpointID != null && !"".equals(newPinnedEndpointID))
            newPinnedIDs = Collections.singleton(newPinnedEndpointID);
        this.endpoint.pinnedEndpointsChanged(newPinnedIDs);
    }

    protected void onPinnedEndpointsChangedEvent(Object src, JSONObject jsonObject) {
        Object o = jsonObject.get("pinnedEndpoints");
        if (!(o instanceof JSONArray)) {
            this.logger.warn("Received invalid or unexpected JSON (" + this.endpoint
                    .getLoggingId() + "):" + jsonObject);
            return;
        }
        JSONArray jsonArray = (JSONArray)o;
        Set<String> newPinnedEndpoints = new HashSet<>();
        for (Object endpointId : jsonArray) {
            if (endpointId != null && endpointId instanceof String)
                newPinnedEndpoints.add((String)endpointId);
        }
        if (this.logger.isDebugEnabled())
            this.logger.debug(Logger.Category.STATISTICS, "pinned," + this.endpoint
                    .getLoggingId() + " pinned=" + newPinnedEndpoints);
        this.endpoint.pinnedEndpointsChanged(newPinnedEndpoints);
    }

    protected void onSelectedEndpointChangedEvent(Object src, JSONObject jsonObject) {
        String newSelectedEndpointID = (String)jsonObject.get("selectedEndpoint");
        Set<String> newSelectedIDs = Collections.EMPTY_SET;
        if (newSelectedEndpointID != null && !"".equals(newSelectedEndpointID))
            newSelectedIDs = Collections.singleton(newSelectedEndpointID);
        this.endpoint.selectedEndpointsChanged(newSelectedIDs);
    }

    protected void onSelectedEndpointsChangedEvent(Object src, JSONObject jsonObject) {
        Object o = jsonObject.get("selectedEndpoints");
        if (!(o instanceof JSONArray)) {
            this.logger.warn("Received invalid or unexpected JSON: " + jsonObject);
            return;
        }
        JSONArray jsonArray = (JSONArray)o;
        Set<String> newSelectedEndpoints = new HashSet<>();
        for (Object endpointId : jsonArray) {
            if (endpointId != null && endpointId instanceof String)
                newSelectedEndpoints.add((String)endpointId);
        }
        this.endpoint.selectedEndpointsChanged(newSelectedEndpoints);
    }

    public void onStringData(WebRtcDataStream src, String msg) {
        this.webSocketLastActive = false;
        (this.endpoint.getConference().getVideobridge().getStatistics()).totalDataChannelMessagesReceived
                .incrementAndGet();
        onMessage(src, msg);
    }

    protected void sendMessage(String msg) throws IOException {
        Object dst = getActiveTransportChannel();
        if (dst == null) {
            this.logger.warn("No available transport channel, can't send a message");
        } else {
            sendMessage(dst, msg);
        }
    }

    private Object getActiveTransportChannel() {
        SctpConnection sctpConnection = getSctpConnection();
        ColibriWebSocket webSocket = this.webSocket;
        String endpointId = this.endpoint.getID();
        Object dst = null;
        if (this.webSocketLastActive)
            dst = webSocket;
        if (dst == null)
            if (sctpConnection != null && sctpConnection.isReady()) {
                dst = this.writableWebRtcDataStream;
                if (dst == null)
                    this.logger.warn("SCTP ready, but WebRtc data channel with " + endpointId + " not opened yet.");
            } else {
                this.logger.warn("SCTP connection with " + endpointId + " not ready yet.");
            }
        if (dst == null && webSocket != null)
            dst = webSocket;
        return dst;
    }

    void onWebSocketConnect(ColibriWebSocket ws) {
        synchronized (this.webSocketSyncRoot) {
            if (this.webSocket != null)
                this.webSocket.getSession().close(200, "replaced");
            this.webSocket = ws;
            this.webSocketLastActive = true;
            sendMessage(ws, EndpointMessageBuilder.createServerHelloEvent(), "initial ServerHello");
        }
        notifyTransportChannelConnected();
    }

    public void onWebSocketClose(ColibriWebSocket ws, int statusCode, String reason) {
        synchronized (this.webSocketSyncRoot) {
            if (ws != null && ws.equals(this.webSocket)) {
                this.webSocket = null;
                this.webSocketLastActive = false;
                if (this.logger.isDebugEnabled())
                    this.logger.debug("Web socket closed for endpoint " + this.endpoint
                            .getID() + ": " + statusCode + " " + reason);
            }
        }
    }

    protected void close() {
        synchronized (this.webSocketSyncRoot) {
            if (this.webSocket != null) {
                this.webSocket.getSession().close(410, "replaced");
                this.webSocket = null;
                if (this.logger.isDebugEnabled())
                    this.logger.debug("Endpoint expired, closed colibri web-socket.");
            }
        }
    }

    public void onWebSocketText(ColibriWebSocket ws, String message) {
        if (ws == null || !ws.equals(this.webSocket)) {
            this.logger.warn("Received text from an unknown web socket (endpoint=" + this.endpoint
                    .getID() + ").");
            return;
        }
        (this.endpoint.getConference().getVideobridge().getStatistics()).totalColibriWebSocketMessagesReceived
                .incrementAndGet();
        this.webSocketLastActive = true;
        onMessage(ws, message);
    }

    SctpConnection getSctpConnection() {
        return this.sctpConnection.get();
    }

    void setSctpConnection(SctpConnection sctpConnection) {
        SctpConnection oldValue = getSctpConnection();
        if (!Objects.equals(oldValue, sctpConnection)) {
            if (oldValue != null && sctpConnection != null)
                this.logger.warn("Replacing an Endpoint's SctpConnection.");
            this.sctpConnection = new WeakReference<>(sctpConnection);
            if (sctpConnection != null) {
                hookUpDefaultWebRtcDataChannel(sctpConnection);
                sctpConnection.addChannelListener(this.webRtcDataStreamListener);
            }
            if (oldValue != null) {
                oldValue.forEachDataStream(stream -> {
                    if (stream.getDataCallback() == this)
                        stream.setDataCallback(null);
                });
                if (this.writableWebRtcDataStream != null && this.writableWebRtcDataStream
                        .getSctpConnection() == oldValue)
                    this.writableWebRtcDataStream = null;
                oldValue.removeChannelListener(this.webRtcDataStreamListener);
            }
        }
    }
}
