package org.jitsi.videobridge;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.rest.ColibriWebSocket;

public class Endpoint extends AbstractEndpoint {
    private static final Logger classLogger = Logger.getLogger(Endpoint.class);

    public static final String PINNED_ENDPOINTS_PROPERTY_NAME = Endpoint.class
            .getName() + ".pinnedEndpoints";

    public static final String SELECTED_ENDPOINTS_PROPERTY_NAME = Endpoint.class
            .getName() + ".selectedEndpoints";

    private Set<String> pinnedEndpoints = new HashSet<>();

    private Set<String> selectedEndpoints = new HashSet<>();

    private final Logger logger;

    private String icePassword;

    private final EndpointMessageTransport messageTransport;

    private AtomicInteger selectedCount = new AtomicInteger(0);

    public Endpoint(String id, Conference conference) {
        super(conference, id);
        this.messageTransport = new EndpointMessageTransport(this);
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    public EndpointMessageTransport getMessageTransport() {
        return this.messageTransport;
    }

    public SctpConnection getSctpConnection() {
        return getMessageTransport().getSctpConnection();
    }

    public Set<String> getSelectedEndpoints() {
        return this.selectedEndpoints;
    }

    public Set<String> getPinnedEndpoints() {
        return this.pinnedEndpoints;
    }

    void pinnedEndpointsChanged(Set<String> newPinnedEndpoints) {
        Set<String> oldPinnedEndpoints = this.pinnedEndpoints;
        if (!oldPinnedEndpoints.equals(newPinnedEndpoints)) {
            this.pinnedEndpoints = newPinnedEndpoints;
            if (this.logger.isDebugEnabled())
                this.logger.debug(getID() + " pinned " +
                        Arrays.toString(this.pinnedEndpoints.toArray()));
            firePropertyChange(PINNED_ENDPOINTS_PROPERTY_NAME, oldPinnedEndpoints, this.pinnedEndpoints);
        }
    }

    void selectedEndpointsChanged(Set<String> newSelectedEndpoints) {
        Set<String> oldSelectedEndpoints = this.selectedEndpoints;
        if (!oldSelectedEndpoints.equals(newSelectedEndpoints)) {
            this.selectedEndpoints = newSelectedEndpoints;
            if (this.logger.isDebugEnabled())
                this.logger.debug(getID() + " selected " +
                        Arrays.toString(this.selectedEndpoints.toArray()));
            firePropertyChange(SELECTED_ENDPOINTS_PROPERTY_NAME, oldSelectedEndpoints, this.selectedEndpoints);
        }
    }

    public void sendMessage(String msg) throws IOException {
        EndpointMessageTransport messageTransport = getMessageTransport();
        if (messageTransport != null)
            messageTransport.sendMessage(msg);
    }

    protected void maybeExpire() {
        if (getSctpConnection() == null && getChannelCount((MediaType)null) == 0)
            expire();
    }

    public void expire() {
        super.expire();
        AbstractEndpointMessageTransport messageTransport = getMessageTransport();
        if (messageTransport != null)
            messageTransport.close();
    }

    void setSctpConnection(SctpConnection sctpConnection) {
        EndpointMessageTransport messageTransport = getMessageTransport();
        if (messageTransport != null)
            messageTransport.setSctpConnection(sctpConnection);
        if (getSctpConnection() == null)
            maybeExpire();
    }

    public boolean acceptWebSocket(String password) {
        String icePassword = getIcePassword();
        if (icePassword == null || !icePassword.equals(password)) {
            this.logger.warn("Incoming web socket request with an invalid password.Expected: " + icePassword + ", received " + password);
            return false;
        }
        return true;
    }

    public void onWebSocketConnect(ColibriWebSocket ws) {
        EndpointMessageTransport messageTransport = getMessageTransport();
        if (messageTransport != null)
            messageTransport.onWebSocketConnect(ws);
    }

    public void onWebSocketClose(ColibriWebSocket ws, int statusCode, String reason) {
        EndpointMessageTransport messageTransport = getMessageTransport();
        if (messageTransport != null)
            messageTransport.onWebSocketClose(ws, statusCode, reason);
    }

    public void onWebSocketText(ColibriWebSocket ws, String message) {
        EndpointMessageTransport messageTransport = getMessageTransport();
        if (messageTransport != null)
            messageTransport.onWebSocketText(ws, message);
    }

    private String getIcePassword() {
        if (this.icePassword != null)
            return this.icePassword;
        List<RtpChannel> channels = getChannels();
        if (channels == null || channels.isEmpty())
            return null;
        TransportManager tm = ((RtpChannel)channels.get(0)).getTransportManager();
        if (tm instanceof IceUdpTransportManager) {
            String password = ((IceUdpTransportManager)tm).getIcePassword();
            if (password != null) {
                this.icePassword = password;
                return password;
            }
        }
        return null;
    }

    public void incrementSelectedCount() {
        int newValue = this.selectedCount.incrementAndGet();
        if (newValue == 1) {
            String selectedUpdate = EndpointMessageBuilder.createSelectedUpdateMessage(true);
            if (this.logger.isDebugEnabled())
                this.logger.debug("Endpoint " + getID() + " is now selected, sending message: " + selectedUpdate);
            try {
                sendMessage(selectedUpdate);
            } catch (IOException e) {
                this.logger.error("Error sending SelectedUpdate message: " + e);
            }
        }
    }

    public void decrementSelectedCount() {
        int newValue = this.selectedCount.decrementAndGet();
        if (newValue == 0) {
            String selectedUpdate = EndpointMessageBuilder.createSelectedUpdateMessage(false);
            if (this.logger.isDebugEnabled())
                this.logger.debug("Endpoint " + getID() + " is no longer selected, sending message: " + selectedUpdate);
            try {
                sendMessage(selectedUpdate);
            } catch (IOException e) {
                this.logger.error("Error sending SelectedUpdate message: " + e);
            }
        }
    }
}
