package org.jitsi.videobridge;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.eventadmin.Event;
import org.jitsi.osgi.EventHandlerActivator;
import org.jitsi.service.configuration.ConfigurationService;
import org.osgi.framework.BundleContext;

public class EndpointConnectionStatus extends EventHandlerActivator {
    private static final String CFG_PNAME_BASE = "org.jitsi.videobridge.EndpointConnectionStatus";

    public static final String CFG_PNAME_FIRST_TRANSFER_TIMEOUT = "org.jitsi.videobridge.EndpointConnectionStatus.FIRST_TRANSFER_TIMEOUT";

    public static final String CFG_PNAME_MAX_INACTIVITY_LIMIT = "org.jitsi.videobridge.EndpointConnectionStatus.MAX_INACTIVITY_LIMIT";

    private static final long DEFAULT_FIRST_TRANSFER_TIMEOUT = 15000L;

    private static final long DEFAULT_MAX_INACTIVITY_LIMIT = 3000L;

    private static final Logger logger = Logger.getLogger(EndpointConnectionStatus.class);

    private long firstTransferTimeout;

    private long maxInactivityLimit;

    private static final long PROBE_INTERVAL = 500L;

    private BundleContext bundleContext;

    private List<Endpoint> inactiveEndpoints = new LinkedList<>();

    private Timer timer;

    public EndpointConnectionStatus() {
        super(new String[] { "org/jitsi/videobridge/Endpoint/MSG_TRANSPORT_READY_TOPIC" });
    }

    public void start(BundleContext bundleContext) throws Exception {
        this.bundleContext = bundleContext;
        if (this.timer == null) {
            this.timer = new Timer("EndpointConnectionStatusMonitoring", true);
            this.timer.schedule(new TimerTask() {
                public void run() {
                    EndpointConnectionStatus.this.doMonitor();
                }
            },  500L, 500L);
        } else {
            logger.error("Endpoint connection monitoring is already running");
        }
        ConfigurationService config = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        this.firstTransferTimeout = config.getLong("org.jitsi.videobridge.EndpointConnectionStatus.FIRST_TRANSFER_TIMEOUT", 15000L);
        this.maxInactivityLimit = config.getLong("org.jitsi.videobridge.EndpointConnectionStatus.MAX_INACTIVITY_LIMIT", 3000L);
        if (this.firstTransferTimeout <= this.maxInactivityLimit)
            throw new IllegalArgumentException(
                    String.format("FIRST_TRANSFER_TIMEOUT(%s) must be greater than MAX_INACTIVITY_LIMIT(%s)", new Object[] { Long.valueOf(this.firstTransferTimeout), Long.valueOf(this.maxInactivityLimit) }));
        super.start(bundleContext);
    }

    public void stop(BundleContext bundleContext) throws Exception {
        super.stop(bundleContext);
        if (this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }
        this.inactiveEndpoints.clear();
        this.bundleContext = null;
    }

    private void doMonitor() {
        BundleContext bundleContext = this.bundleContext;
        if (bundleContext != null) {
            Collection<Videobridge> jvbs = Videobridge.getVideobridges(bundleContext);
            for (Videobridge videobridge : jvbs) {
                cleanupExpiredEndpointsStatus();
                Conference[] conferences = videobridge.getConferences();
                Arrays.<Conference>stream(conferences)
                        .forEachOrdered(conference -> conference.getEndpoints().forEach(this::monitorEndpointActivity));
            }
        }
    }

    private void monitorEndpointActivity(AbstractEndpoint abstractEndpoint) {
        if (!(abstractEndpoint instanceof Endpoint))
            return;
        Endpoint endpoint = (Endpoint)abstractEndpoint;
        String endpointId = endpoint.getID();
        List<RtpChannel> rtpChannels = endpoint.getChannels();
        long lastActivity = rtpChannels.stream().mapToLong(Channel::getLastTransportActivityTime).max().orElse(0L);
        long mostRecentChannelCreated = rtpChannels.stream().mapToLong(Channel::getCreationTimestamp).max().orElse(0L);
        SctpConnection sctpConnection = endpoint.getSctpConnection();
        if (sctpConnection != null) {
            long lastSctpActivity = sctpConnection.getLastTransportActivityTime();
            if (lastSctpActivity > lastActivity)
                lastActivity = lastSctpActivity;
            long creationTimestamp = sctpConnection.getCreationTimestamp();
            if (creationTimestamp > mostRecentChannelCreated)
                mostRecentChannelCreated = creationTimestamp;
        }
        if (lastActivity == 0L)
            if (System.currentTimeMillis() - mostRecentChannelCreated > this.firstTransferTimeout) {
                if (logger.isDebugEnabled())
                    logger.debug(endpointId + " is having trouble establishing the connection and will be marked as inactive");
                lastActivity = mostRecentChannelCreated;
            } else {
                if (logger.isDebugEnabled())
                    logger.debug(endpointId + " not ready for activity checks yet");
                return;
            }
        long noActivityForMs = System.currentTimeMillis() - lastActivity;
        boolean inactive = (noActivityForMs > this.maxInactivityLimit);
        if (inactive && !this.inactiveEndpoints.contains(endpoint)) {
            logger.debug(endpointId + " is considered disconnected");
            this.inactiveEndpoints.add(endpoint);
            sendEndpointConnectionStatus(endpoint, false, null);
        } else if (!inactive && this.inactiveEndpoints.contains(endpoint)) {
            logger.debug(endpointId + " has reconnected");
            this.inactiveEndpoints.remove(endpoint);
            sendEndpointConnectionStatus(endpoint, true, null);
        }
        if (inactive && logger.isDebugEnabled())
            logger.debug(String.format("No activity on %s for %s", new Object[] { endpointId,

                    Double.valueOf(noActivityForMs / 1000.0D) }));
    }

    private void sendEndpointConnectionStatus(Endpoint subjectEndpoint, boolean isConnected, Endpoint msgReceiver) {
        Conference conference = subjectEndpoint.getConference();
        if (conference != null) {
            String msg = EndpointMessageBuilder.createEndpointConnectivityStatusChangeEvent(subjectEndpoint
                    .getID(), isConnected);
            if (msgReceiver == null) {
                conference.broadcastMessage(msg, true);
            } else {
                List<AbstractEndpoint> receivers = Collections.singletonList(msgReceiver);
                conference.sendMessage(msg, receivers);
            }
        } else {
            logger.warn("Attempt to send connectivity status update for endpoint " + subjectEndpoint

                    .getID() + " without parent conference instance (expired?)");
        }
    }

    private void cleanupExpiredEndpointsStatus() {
        this.inactiveEndpoints.removeIf(e -> {
            Conference conference = e.getConference();
            AbstractEndpoint replacement = conference.getEndpoint(e.getID());
            boolean endpointReplaced = (replacement != null && replacement != e);
            if (endpointReplaced)
                if (replacement instanceof Endpoint)
                    sendEndpointConnectionStatus((Endpoint)replacement, true, null);
            return (conference.isExpired() || endpointReplaced);
        });
        if (logger.isDebugEnabled())
            this.inactiveEndpoints.stream()
                    .filter(AbstractEndpoint::isExpired)
                    .forEach(e -> logger.debug("Endpoint has expired: " + e.getID() + ", but is still on the list"));
    }

    public void handleEvent(Event event) {
        String topic = event.getTopic();
        if (!"org/jitsi/videobridge/Endpoint/MSG_TRANSPORT_READY_TOPIC".equals(topic)) {
            logger.warn("Received event for unexpected topic: " + topic);
            return;
        }
        Endpoint endpoint = (Endpoint)event.getProperty("event.source");
        if (endpoint == null) {
            logger.error("Endpoint is null");
            return;
        }
        Conference conference = endpoint.getConference();
        if (conference == null || conference.isExpired())
            return;
        this.inactiveEndpoints.stream()
                .filter(e -> (e.getConference() == conference))
                .forEach(e -> sendEndpointConnectionStatus(e, false, endpoint));
    }
}
