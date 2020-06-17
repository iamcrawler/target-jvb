package org.jitsi.videobridge.health;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import org.ice4j.ice.harvest.MappingCandidateHarvesters;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.concurrent.PeriodicRunnableWithObject;
import org.jitsi.utils.concurrent.RecurringRunnable;
import org.jitsi.utils.concurrent.RecurringRunnableExecutor;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.Channel;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.Content;
import org.jitsi.videobridge.Endpoint;
import org.jitsi.videobridge.IceUdpTransportManager;
import org.jitsi.videobridge.RtpChannel;
import org.jitsi.videobridge.SctpConnection;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;

public class Health extends PeriodicRunnableWithObject<Videobridge> {
    private static final Logger logger = Logger.getLogger(Health.class);

    private static final MediaType[] MEDIA_TYPES = new MediaType[] { MediaType.AUDIO, MediaType.VIDEO };

    private static Random RANDOM = Videobridge.RANDOM;

    private static final RecurringRunnableExecutor executor = new RecurringRunnableExecutor(Health.class
            .getName());

    private static final int PERIOD_DEFAULT = 10000;

    public static final String PERIOD_PNAME = "org.jitsi.videobridge.health.INTERVAL";

    private static final int TIMEOUT_DEFAULT = 30000;

    public static final String TIMEOUT_PNAME = "org.jitsi.videobridge.health.TIMEOUT";

    public static final String STICKY_FAILURES_PNAME = "org.jitsi.videobridge.health.STICKY_FAILURES";

    private static final boolean STICKY_FAILURES_DEFAULT = false;

    private static final long STICKY_FAILURES_GRACE_PERIOD = 300000L;

    private static void check(Conference conference) throws Exception {
        Endpoint[] endpoints = new Endpoint[2];
        for (int i = 0; i < endpoints.length; i++) {
            Endpoint endpoint = (Endpoint)conference.getOrCreateEndpoint(generateEndpointID());
            if (endpoint == null)
                throw new NullPointerException("Failed to create an endpoint.");
            endpoints[i] = endpoint;
            String channelBundleId = null;
            Boolean initiator = Boolean.valueOf((i % 2 == 0));
            for (MediaType mediaType : MEDIA_TYPES) {
                Content content = conference.getOrCreateContent(mediaType.toString());
                RtpChannel rtpChannel = content.createRtpChannel(channelBundleId, null, initiator, null);
                if (rtpChannel == null)
                    throw new NullPointerException("Failed to create a channel.");
            }
            Content dataContent = conference.getOrCreateContent("data");
            SctpConnection sctpConnection = dataContent.createSctpConnection((AbstractEndpoint)endpoint, RANDOM

                    .nextInt(), channelBundleId, initiator);
            if (sctpConnection == null)
                throw new NullPointerException("Failed to create SCTP connection.");
        }
        interconnect(endpoints);
    }

    private static void doCheck(Videobridge videobridge) throws Exception {
        if (MappingCandidateHarvesters.stunDiscoveryFailed)
            throw new Exception("Address discovery through STUN failed");
        if (!IceUdpTransportManager.healthy)
            throw new Exception("Failed to bind single-port");
        checkXmppConnection(videobridge);
        Conference conference = videobridge.createConference(null, null, false, null);
        if (conference == null)
            throw new NullPointerException("Failed to create a conference");
        try {
            check(conference);
        } finally {
            conference.expire();
        }
    }

    private static void checkXmppConnection(Videobridge videobridge) throws Exception {
        Collection<ComponentImpl> components = videobridge.getComponents();
        if (videobridge.isXmppApiEnabled() && components.size() == 0)
            throw new Exception("No XMPP components");
        for (ComponentImpl component : components) {
            if (!component.isConnectionAlive())
                throw new Exception("XMPP component not connected: " + component);
        }
    }

    private static void connect(Endpoint a, Endpoint b) throws Exception {
        for (MediaType mediaType : MEDIA_TYPES) {
            List<RtpChannel> aRtpChannels = a.getChannels(mediaType);
            int count = aRtpChannels.size();
            List<RtpChannel> bRtpChannels = b.getChannels(mediaType);
            if (count != bRtpChannels.size())
                throw new IllegalStateException("Endpoint#getChannels(MediaType)");
            for (int i = 0; i < count; i++)
                connect((Channel)aRtpChannels.get(i), (Channel)bRtpChannels.get(i));
        }
        SctpConnection aSctpConnection = a.getSctpConnection();
        if (aSctpConnection == null)
            throw new NullPointerException("aSctpConnection is null");
        SctpConnection bSctpConnection = b.getSctpConnection();
        if (bSctpConnection == null)
            throw new NullPointerException("bSctpConnection is null");
        connect((Channel)aSctpConnection, (Channel)bSctpConnection);
    }

    private static void connect(Channel a, Channel b) throws Exception {
        IceUdpTransportPacketExtension aTransport = describeTransportManager(a);
        if (aTransport == null)
            throw new NullPointerException("Failed to describe transport.");
        IceUdpTransportPacketExtension bTransport = describeTransportManager(b);
        if (bTransport == null)
            throw new NullPointerException("Failed to describe transport.");
        b.setTransport(aTransport);
        a.setTransport(bTransport);
    }

    private static IceUdpTransportPacketExtension describeTransportManager(Channel channel) {
        ColibriConferenceIQ.ChannelCommon iq = (channel instanceof SctpConnection) ? (ColibriConferenceIQ.ChannelCommon)new ColibriConferenceIQ.SctpConnection() : (ColibriConferenceIQ.ChannelCommon)new ColibriConferenceIQ.Channel();
        channel.getTransportManager().describe(iq);
        return iq.getTransport();
    }

    private static String generateEndpointID() {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    private static void interconnect(Endpoint[] endpoints) throws Exception {
        for (int i = 0; i < endpoints.length;)
            connect(endpoints[i++], endpoints[i++]);
    }

    private Exception lastResult = null;

    private long lastResultMs = -1L;

    private final int timeout;

    private final boolean stickyFailures;

    private final long startMs;

    private boolean hasFailed = false;

    public Health(Videobridge videobridge, ConfigurationService cfg) {
        super(videobridge, 10000L, true);
        int period = (cfg == null) ? 10000 : cfg.getInt("org.jitsi.videobridge.health.INTERVAL", 10000);
        setPeriod(period);
        this

                .timeout = (cfg == null) ? 30000 : cfg.getInt("org.jitsi.videobridge.health.TIMEOUT", 30000);
        this

                .stickyFailures = (cfg == null) ? false : cfg.getBoolean("org.jitsi.videobridge.health.STICKY_FAILURES", false);
        this.startMs = System.currentTimeMillis();
        executor.registerRecurringRunnable((RecurringRunnable)this);
    }

    public void stop() {
        executor.deRegisterRecurringRunnable((RecurringRunnable)this);
    }

    protected void doRun() {
        long start = System.currentTimeMillis();
        Exception exception = null;
        try {
            doCheck((Videobridge)this.o);
        } catch (Exception e) {
            exception = e;
            if (System.currentTimeMillis() - this.startMs > 300000L)
                this.hasFailed = true;
        }
        long duration = System.currentTimeMillis() - start;
        this.lastResultMs = start + duration;
        if (this.stickyFailures && this.hasFailed && exception == null) {
            this.lastResult = new Exception("Sticky failure.");
        } else {
            this.lastResult = exception;
        }
        if (exception == null) {
            logger.info("Performed a successful health check in " + duration + "ms. Sticky failure: " + ((this.stickyFailures && this.hasFailed) ? 1 : 0));
        } else {
            logger.error("Health check failed in " + duration + "ms:", exception);
        }
    }

    public void check() throws Exception {
        Exception lastResult = this.lastResult;
        long lastResultMs = this.lastResultMs;
        long timeSinceLastResult = System.currentTimeMillis() - lastResultMs;
        if (timeSinceLastResult > this.timeout)
            throw new Exception("No health checks performed recently, the last result was " + timeSinceLastResult + "ms ago.");
        if (lastResult != null)
            throw new Exception(lastResult);
    }
}
