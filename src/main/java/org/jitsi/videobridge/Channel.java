package org.jitsi.videobridge;


import java.io.IOException;
import java.util.Objects;
import org.jitsi.eventadmin.EventAdmin;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.SrtpControl;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.util.concurrent.MonotonicAtomicLong;
import org.jitsi.utils.StringUtils;
import org.jitsi.utils.event.PropertyChangeNotifier;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.octo.OctoTransportManager;
import org.jitsi.videobridge.util.Expireable;
import org.jitsi.videobridge.util.ExpireableImpl;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
//import org.jivesoftware.smack.util.StringUtils;
import org.osgi.framework.BundleContext;

public abstract class Channel extends PropertyChangeNotifier implements Expireable {
    public static final int DEFAULT_EXPIRE = 60;

    public static final String INITIATOR_PROPERTY = "initiator";

    public static final String ENDPOINT_PROPERTY_NAME = ".endpoint";

    private static final Logger classLogger = Logger.getLogger(Channel.class);

    private final String channelBundleId;

    public static String getLoggingId(Channel channel) {
        String id = (channel == null) ? "null" : channel.getID();
        Content content = (channel == null) ? null : channel.getContent();
        AbstractEndpoint endpoint = (channel == null) ? null : channel.getEndpoint();
        return Content.getLoggingId(content) + ",ch_id=" + id + ",endp_id=" + ((endpoint == null) ? "null" : endpoint

                .getID());
    }

    private final long creationTimestamp = System.currentTimeMillis();

    private final Content content;

    private AbstractEndpoint endpoint;

    private int expire = 60;

    private boolean expired = false;

    private final String id;

    private boolean initiator = true;

    private final MonotonicAtomicLong lastActivityTime = new MonotonicAtomicLong();

    private final MonotonicAtomicLong lastTransportActivityTime = new MonotonicAtomicLong();

    private final MonotonicAtomicLong lastPayloadActivityTime = new MonotonicAtomicLong();

    private StreamConnector streamConnector;

    private TransportManager transportManager;

    protected final String transportNamespace;

    private final Object transportManagerSyncRoot = new Object();

    private final Logger logger;

    private final ExpireableImpl expireableImpl;

    public Channel(Content content, String id, String channelBundleId, String transportNamespace, Boolean initiator) {
        Objects.requireNonNull(content, "content");
//        StringUtils.requireNotNullOrEmpty(id, "id");
        this.id = id;
        this.content = content;
        this.channelBundleId = channelBundleId;
        if (initiator != null)
            this.initiator = initiator.booleanValue();
        this
                .logger = Logger.getLogger(classLogger, content
                .getConference().getLogger());
        if (StringUtils.isNullOrEmpty(transportNamespace))
            transportNamespace = getContent().getConference().getVideobridge().getDefaultTransportManager();
        this.transportNamespace = transportNamespace;
        this.expireableImpl = new ExpireableImpl(getLoggingId(), this::expire);
        touch();
    }

    protected abstract void closeStream() throws IOException;

    protected StreamConnector createStreamConnector() {
        TransportManager transportManager = getTransportManager();
        return (transportManager != null) ? transportManager

                .getStreamConnector(this) : null;
    }

    protected MediaStreamTarget createStreamTarget() {
        TransportManager transportManager = getTransportManager();
        return (transportManager != null) ? transportManager

                .getStreamTarget(this) : null;
    }

    protected TransportManager createTransportManager(String xmlNamespace) throws IOException {
        if ("urn:xmpp:jingle:transports:ice-udp:1".equals(xmlNamespace)) {
            Content content = getContent();
            return new IceUdpTransportManager(content

                    .getConference(),
                    isInitiator(), 2, content

                    .getName());
        }
        if ("urn:xmpp:jingle:transports:raw-udp:1".equals(xmlNamespace))
            return new RawUdpTransportManager(this);
        if ("http://jitsi.org/octo".equals(xmlNamespace))
            return (TransportManager)new OctoTransportManager(this);
        throw new IllegalArgumentException("Unsupported Jingle transport " + xmlNamespace);
    }

    public void describe(ColibriConferenceIQ.ChannelCommon iq) {
        AbstractEndpoint endpoint = getEndpoint();
        if (endpoint != null)
            iq.setEndpoint(endpoint.getID());
        iq.setID(this.id);
        iq.setExpire(getExpire());
        iq.setInitiator(Boolean.valueOf(isInitiator()));
        if (this.channelBundleId != null) {
            iq.setChannelBundleId(this.channelBundleId);
        } else {
            describeTransportManager(iq);
        }
    }

    private void describeTransportManager(ColibriConferenceIQ.ChannelCommon iq) {
        TransportManager transportManager = getTransportManager();
        if (transportManager != null)
            transportManager.describe(iq);
    }

    public boolean expire() {
        synchronized (this) {
            if (this.expired)
                return false;
            this.expired = true;
        }
        Content content = getContent();
        Conference conference = content.getConference();
        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.channelExpired(this));
        try {
            content.expireChannel(this);
        } finally {
            try {
                closeStream();
            } catch (Throwable t) {
                this.logger.warn("Failed to close the MediaStream/stream of channel " +

                        getID() + " of content " + content.getName() + " of conference " + conference
                        .getID() + "!", t);
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath)t;
            }
            try {
                synchronized (this.transportManagerSyncRoot) {
                    if (this.transportManager != null)
                        this.transportManager.close(this);
                }
            } catch (Throwable t) {
                this.logger.warn("Failed to close the TransportManager/transportManager of channel " +

                        getID() + " of content " + content
                        .getName() + " of conference " + conference
                        .getID() + "!", t);
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath)t;
            }
            try {
                onEndpointChanged(getEndpoint(), null);
            } catch (Throwable t) {
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath)t;
            }
            if (this.logger.isInfoEnabled())
                this.logger.info(Logger.Category.STATISTICS, "expire_ch," +
                        getLoggingId());
        }
        return true;
    }

    public BundleContext getBundleContext() {
        return getContent().getBundleContext();
    }

    public Content getContent() {
        return this.content;
    }

    public long getCreationTimestamp() {
        return this.creationTimestamp;
    }

    protected SrtpControl getSrtpControl() {
        TransportManager transportManager = getTransportManager();
        return (transportManager == null) ? null : transportManager

                .getSrtpControl(this);
    }

    public AbstractEndpoint getEndpoint() {
        return this.endpoint;
    }

    public AbstractEndpoint getEndpoint(long ssrc) {
        return getEndpoint();
    }

    public int getExpire() {
        return this.expire;
    }

    public final String getID() {
        return this.id;
    }

    public long getLastActivityTime() {
        return this.lastActivityTime.get();
    }

    public long getLastPayloadActivityTime() {
        return this.lastPayloadActivityTime.get();
    }

    public long getLastTransportActivityTime() {
        return this.lastTransportActivityTime.get();
    }

    StreamConnector getStreamConnector() {
        if (this.streamConnector == null)
            this.streamConnector = createStreamConnector();
        return this.streamConnector;
    }

    public TransportManager getTransportManager() {
        return this.transportManager;
    }

    void initialize() throws IOException {
        synchronized (this.transportManagerSyncRoot) {
            if (this.channelBundleId == null) {
                this
                        .transportManager = createTransportManager(this.transportNamespace);
            } else {
                this

                        .transportManager = getContent().getConference().getTransportManager(this.channelBundleId, true, isInitiator());
            }
            if (this.transportManager == null)
                throw new IOException("Failed to get transport manager.");
            this.transportManager.addChannel(this);
        }
    }

    public boolean isExpired() {
        return this.expired;
    }

    public boolean isInitiator() {
        return this.initiator;
    }

    protected abstract void maybeStartStream() throws IOException;

    protected void onEndpointChanged(AbstractEndpoint oldValue, AbstractEndpoint newValue) {
        firePropertyChange(".endpoint", oldValue, newValue);
    }

    public void setEndpoint(String newEndpointId) {
        try {
            AbstractEndpoint oldValue = this.endpoint;
            if (oldValue == null) {
                if (newEndpointId == null)
                    return;
            } else if (oldValue.getID().equals(newEndpointId)) {
                return;
            }
            AbstractEndpoint newValue = getContent().getConference().getOrCreateEndpoint(newEndpointId);
            setEndpoint(newValue);
        } finally {
            touch();
        }
    }

    public void setEndpoint(AbstractEndpoint endpoint) {
        AbstractEndpoint oldEndpoint = this.endpoint;
        if (oldEndpoint != endpoint) {
            this.endpoint = endpoint;
            onEndpointChanged(oldEndpoint, endpoint);
        }
    }

    public void setExpire(int expire) {
        if (expire < 0)
            throw new IllegalArgumentException("expire");
        this.expire = expire;
        if (this.expire == 0) {
            expire();
        } else {
            touch();
        }
    }

    public void setInitiator(boolean initiator) {
        boolean oldValue = this.initiator;
        this.initiator = initiator;
        boolean newValue = this.initiator;
        touch();
        if (oldValue != newValue)
            firePropertyChange("initiator", Boolean.valueOf(oldValue), Boolean.valueOf(newValue));
    }

    public void setTransport(IceUdpTransportPacketExtension transport) {
        if (transport != null) {
            TransportManager transportManager = getTransportManager();
            if (transportManager != null) {
                transportManager.startConnectivityEstablishment(transport);
            } else {
                this.logger.warn("Failed to start connectivity establishment: transport manager is null.");
            }
        }
        touch();
    }

    public void touch(ActivityType activityType) {
        long now = System.currentTimeMillis();
        switch (activityType) {
            case PAYLOAD:
                this.lastPayloadActivityTime.increase(now);
            case TRANSPORT:
                this.lastTransportActivityTime.increase(now);
                break;
        }
        this.lastActivityTime.increase(now);
    }

    public void touch() {
        touch(ActivityType.OTHER);
    }

    void transportClosed() {
        expire();
    }

    void transportConnected() {
        this.logger.info(Logger.Category.STATISTICS, "transport_connected," +
                getLoggingId());
        touch(ActivityType.TRANSPORT);
        try {
            maybeStartStream();
        } catch (IOException ioe) {
            this.logger.warn("Failed to start stream for channel: " + getID() + ": " + ioe);
        }
    }

    public String getChannelBundleId() {
        return this.channelBundleId;
    }

    public String getLoggingId() {
        return getLoggingId(this);
    }

    public boolean shouldExpire() {
        return

                (getLastActivityTime() + 1000L * getExpire() < System.currentTimeMillis());
    }

    public void safeExpire() {
        this.expireableImpl.safeExpire();
    }

    public enum ActivityType {
        TRANSPORT, PAYLOAD, OTHER;
    }
}