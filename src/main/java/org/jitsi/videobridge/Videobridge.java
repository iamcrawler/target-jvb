package org.jitsi.videobridge;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.java.sip.communicator.service.shutdown.ShutdownService;
import net.java.sip.communicator.util.ServiceUtils;
import org.ice4j.ice.harvest.HostCandidateHarvester;
import org.ice4j.ice.harvest.MappingCandidateHarvesters;
import org.ice4j.stack.StunStack;
import org.jitsi.eventadmin.EventAdmin;
import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.MediaDirection;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.StringUtils;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.health.Health;
import org.jitsi.videobridge.octo.OctoChannel;
import org.jitsi.videobridge.pubsub.PubSubPublisher;
import org.jitsi.videobridge.util.UlimitCheck;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jitsi.xmpp.extensions.DefaultPacketExtensionProvider;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.ColibriIQProvider;
import org.jitsi.xmpp.extensions.colibri.ShutdownIQ;
import org.jitsi.xmpp.extensions.health.HealthCheckIQ;
import org.jitsi.xmpp.extensions.health.HealthCheckIQProvider;
import org.jitsi.xmpp.extensions.jingle.CandidatePacketExtension;
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.RawUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.RtcpmuxPacketExtension;
import org.jitsi.xmpp.util.IQUtils;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.pubsub.PubSubElementType;
import org.jivesoftware.smackx.pubsub.provider.PubSubProvider;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Localpart;
import org.osgi.framework.BundleContext;

public class Videobridge {
    public static final String COLIBRI_CLASS = "colibriClass";

    public static final String DEFAULT_OPTIONS_PROPERTY_NAME = "org.jitsi.videobridge.defaultOptions";

    private static String defaultTransportManager;

    public static final String ENABLE_MEDIA_RECORDING_PNAME = "org.jitsi.videobridge.ENABLE_MEDIA_RECORDING";

    private static final Logger logger = Logger.getLogger(Videobridge.class);

    public static final String MEDIA_RECORDING_PATH_PNAME = "org.jitsi.videobridge.MEDIA_RECORDING_PATH";

    public static final String MEDIA_RECORDING_TOKEN_PNAME = "org.jitsi.videobridge.MEDIA_RECORDING_TOKEN";

    public static final int OPTION_ALLOW_ANY_FOCUS = 2;

    public static final int OPTION_ALLOW_NO_FOCUS = 1;

    public static final Random RANDOM = new Random();

    public static final String REST_API = "rest";

    public static final String REST_API_PNAME = "org.jitsi.videobridge.rest";

    public static final String SHUTDOWN_ALLOWED_SOURCE_REGEXP_PNAME = "org.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP";

    public static final String AUTHORIZED_SOURCE_REGEXP_PNAME = "org.jitsi.videobridge.AUTHORIZED_SOURCE_REGEXP";

    public static final String XMPP_API = "xmpp";

    public static final String XMPP_API_PNAME = "org.jitsi.videobridge.xmpp";

    private Pattern authorizedSourcePattern;

    private BundleContext bundleContext;

    public static Collection<Videobridge> getVideobridges(BundleContext bundleContext) {
        return ServiceUtils2.getServices(bundleContext, Videobridge.class);
    }

    private final Map<String, Conference> conferences = new HashMap<>();

    private int defaultProcessingOptions;

    private boolean shutdownInProgress;

    private Pattern shutdownSourcePattern;

    private final Statistics statistics = new Statistics();

    private VideobridgeExpireThread videobridgeExpireThread;

    private Health health;

    public Videobridge() {
        this.videobridgeExpireThread = new VideobridgeExpireThread(this);
    }

    public Conference createConference(Jid focus, Localpart name, String gid) {
        return createConference(focus, name, true, gid);
    }

    public Conference createConference(Jid focus, Localpart name, boolean enableLogging, String gid) {
        Conference conference = null;
        do {
            String id = generateConferenceID();
            synchronized (this.conferences) {
                if (!this.conferences.containsKey(id)) {
                    conference = new Conference(this, id, focus, name, enableLogging, gid);
                    this.conferences.put(id, conference);
                }
            }
        } while (conference == null);
        if (logger.isInfoEnabled())
            logger.info(Logger.Category.STATISTICS, "create_conf," + conference
                    .getLoggingId() + " conf_name=" + name + ",logging=" + enableLogging + "," +

                    getConferenceCountString());
        return conference;
    }

    private void enableGracefulShutdownMode() {
        if (!this.shutdownInProgress)
            logger.info("Entered graceful shutdown mode");
        this.shutdownInProgress = true;
        maybeDoShutdown();
    }

    public void expireConference(Conference conference) {
        boolean expireConference;
        String id = conference.getID();
        synchronized (this.conferences) {
            if (conference.equals(this.conferences.get(id))) {
                this.conferences.remove(id);
                expireConference = true;
            } else {
                expireConference = false;
            }
        }
        if (expireConference)
            conference.expire();
        maybeDoShutdown();
    }

    private String generateConferenceID() {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    public BundleContext getBundleContext() {
        return this.bundleContext;
    }

    public Statistics getStatistics() {
        return this.statistics;
    }

    public int getChannelCount() {
        int channelCount = 0;
        for (Conference conference : getConferences()) {
            if (conference != null && !conference.isExpired())
                for (Content content : conference.getContents()) {
                    if (content != null && !content.isExpired())
                        channelCount += content.getChannelCount();
                }
        }
        return channelCount;
    }

    public Collection<ComponentImpl> getComponents() {
        return ComponentImpl.getComponents(getBundleContext());
    }

    public Conference getConference(String id, Jid focus) {
        Conference conference;
        synchronized (this.conferences) {
            conference = this.conferences.get(id);
        }
        if (conference != null) {
            Jid conferenceFocus = conference.getFocus();
            if (focus == null || conferenceFocus == null || focus
                    .equals((CharSequence)conferenceFocus)) {
                conference.touch();
            } else {
                conference = null;
            }
        }
        return conference;
    }

    public int getConferenceCount() {
        int sz = 0;
        Conference[] cs = getConferences();
        if (cs != null && cs.length != 0)
            for (Conference c : cs) {
                if (c != null && !c.isExpired())
                    sz++;
            }
        return sz;
    }

    public Conference[] getConferences() {
        synchronized (this.conferences) {
            Collection<Conference> values = this.conferences.values();
            return values.<Conference>toArray(new Conference[values.size()]);
        }
    }

    public ConfigurationService getConfigurationService() {
        BundleContext bundleContext = getBundleContext();
        if (bundleContext == null)
            return null;
        return
                (ConfigurationService)ServiceUtils2.getService(bundleContext, ConfigurationService.class);
    }

    public String getDefaultTransportManager() {
        synchronized (Videobridge.class) {
            if (defaultTransportManager == null) {
                BundleContext bundleContext = getBundleContext();
                if (bundleContext != null) {
                    ConfigurationService cfg = (ConfigurationService)ServiceUtils2.getService(bundleContext, ConfigurationService.class);
                    if (cfg != null)
                        defaultTransportManager = cfg.getString(Videobridge.class
                                .getName() + ".defaultTransportManager");
                }
                if (!"urn:xmpp:jingle:transports:ice-udp:1".equals(defaultTransportManager) &&

                        !"urn:xmpp:jingle:transports:raw-udp:1".equals(defaultTransportManager))
                    defaultTransportManager = "urn:xmpp:jingle:transports:ice-udp:1";
            }
            return defaultTransportManager;
        }
    }

    public EventAdmin getEventAdmin() {
        BundleContext bundleContext = getBundleContext();
        if (bundleContext == null)
            return null;
        return (EventAdmin)ServiceUtils2.getService(bundleContext, EventAdmin.class);
    }

    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIQ) {
        return
                handleColibriConferenceIQ(conferenceIQ, this.defaultProcessingOptions);
    }

    private boolean accept(Jid focus, int options) {
        if ((options & 0x2) > 0)
            return true;
        if (focus == null)
            return ((options & 0x1) != 0);
        if (this.authorizedSourcePattern != null)
            return this.authorizedSourcePattern.matcher((CharSequence)focus).matches();
        return true;
    }

    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIQ, int options) {
        Conference conference;
        Jid focus = conferenceIQ.getFrom();
        if (!accept(focus, options))
            return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.not_authorized);
        String id = conferenceIQ.getID();
        if (id == null) {
            if (isShutdownInProgress())
                return
                        ColibriConferenceIQ.createGracefulShutdownErrorResponse((IQ)conferenceIQ);
            conference = createConference(focus, conferenceIQ

                    .getName(), conferenceIQ
                    .getGID());
            if (conference == null)
                return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.internal_server_error, "Failed to create new conference");
        } else {
            conference = getConference(id, focus);
            if (conference == null)
                return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "Conference not found for ID: " + id);
        }
        conference.setLastKnownFocus(conferenceIQ.getFrom());
        ColibriConferenceIQ responseConferenceIQ = new ColibriConferenceIQ();
        conference.describeShallow(responseConferenceIQ);
        Set<String> channelBundleIdsToDescribe = new HashSet<>();
        responseConferenceIQ.setGracefulShutdown(isShutdownInProgress());
        ColibriConferenceIQ.Recording recordingIQ = conferenceIQ.getRecording();
        if (recordingIQ != null) {
            String tokenIQ = recordingIQ.getToken();
            if (tokenIQ != null) {
                String tokenConfig = getConfigurationService().getString("org.jitsi.videobridge.MEDIA_RECORDING_TOKEN");
                if (tokenIQ.equals(tokenConfig)) {
                    ColibriConferenceIQ.Recording.State recState = recordingIQ.getState();
                    boolean recording = conference.setRecording((ColibriConferenceIQ.Recording.State.ON

                            .equals(recState) || ColibriConferenceIQ.Recording.State.PENDING

                            .equals(recState)));
                    ColibriConferenceIQ.Recording responseRecordingIq = new ColibriConferenceIQ.Recording(recState);
                    if (recording)
                        responseRecordingIq.setDirectory(conference
                                .getRecordingDirectory());
                    responseConferenceIQ.setRecording(responseRecordingIq);
                }
            }
        }
        for (ColibriConferenceIQ.Content contentIQ : conferenceIQ.getContents()) {
            String contentName = contentIQ.getName();
            Content content = conference.getOrCreateContent(contentName);
            if (content == null)
                return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.internal_server_error, "Failed to create new content for name: " + contentName);
            ColibriConferenceIQ.Content responseContentIQ = new ColibriConferenceIQ.Content(content.getName());
            responseConferenceIQ.addContent(responseContentIQ);
            for (ColibriConferenceIQ.Channel channelIQ : contentIQ.getChannels()) {
                RtpChannel channel;
                ColibriConferenceIQ.OctoChannel octoChannelIQ = (channelIQ instanceof ColibriConferenceIQ.OctoChannel) ? (ColibriConferenceIQ.OctoChannel)channelIQ : null;
                String channelID = channelIQ.getID();
                int channelExpire = channelIQ.getExpire();
                String channelBundleId = channelIQ.getChannelBundleId();
                channelBundleIdsToDescribe.add(channelBundleId);
                boolean channelCreated = false;
                String transportNamespace = (channelIQ.getTransport() != null) ? channelIQ.getTransport().getNamespace() : null;
                if (channelID == null) {
                    if (channelExpire == 0)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "Channel expire request for empty ID");
                    try {
                        channel = content.createRtpChannel(channelBundleId, transportNamespace, channelIQ

                                .isInitiator(), channelIQ
                                .getRTPLevelRelayType(), (octoChannelIQ != null));
                    } catch (IOException ioe) {
                        logger.error("Failed to create RtpChannel:", ioe);
                        channel = null;
                    }
                    if (channel == null)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.internal_server_error, "Failed to allocate new RTP Channel");
                    channelCreated = true;
                } else {
                    channel = (RtpChannel)content.getChannel(channelID);
                    if (channel == null) {
                        if (channelExpire == 0)
                            continue;
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "No RTP channel found for ID: " + channelID);
                    }
                }
                if (channelExpire != -1) {
                    if (channelExpire < 0)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "Invalid 'expire' value: " + channelExpire);
                    channel.setExpire(channelExpire);
                    if (channelExpire == 0 && channel.isExpired())
                        continue;
                }
                String endpoint = channelIQ.getEndpoint();
                if (endpoint != null)
                    channel.setEndpoint(endpoint);
                Integer lastN = channelIQ.getLastN();
                if (lastN != null)
                    channel.setLastN(lastN.intValue());
                Integer packetDelay = channelIQ.getPacketDelay();
                if (packetDelay != null)
                    channel.setPacketDelay(packetDelay.intValue());
                Boolean initiator = channelIQ.isInitiator();
                if (initiator != null) {
                    channel.setInitiator(initiator.booleanValue());
                } else {
                    initiator = Boolean.valueOf(true);
                }
                channel.setPayloadTypes(channelIQ.getPayloadTypes());
                channel.setRtpHeaderExtensions(channelIQ
                        .getRtpHeaderExtensions());
                channel.setDirection(
                        MediaDirection.parseString(channelIQ.getDirection()));
                channel.setRtpEncodingParameters(channelIQ
                        .getSources(), channelIQ.getSourceGroups());
                if (channelBundleId != null) {
                    TransportManager transportManager = conference.getTransportManager(channelBundleId, true, initiator

                            .booleanValue());
                    transportManager.addChannel(channel);
                }
                channel.setTransport(channelIQ.getTransport());
                if (octoChannelIQ != null)
                    if (channel instanceof OctoChannel) {
                        ((OctoChannel)channel)
                                .setRelayIds(octoChannelIQ.getRelays());
                    } else {
                        logger.warn("Channel type mismatch: requested Octo, found " + channel

                                .getClass().getSimpleName());
                    }
                ColibriConferenceIQ.Channel responseChannelIQ = new ColibriConferenceIQ.Channel();
                channel.describe((ColibriConferenceIQ.ChannelCommon)responseChannelIQ);
                responseContentIQ.addChannel(responseChannelIQ);
                EventAdmin eventAdmin;
                if (channelCreated && (eventAdmin = getEventAdmin()) != null)
                    eventAdmin.sendEvent(EventFactory.channelCreated(channel));
                content.fireChannelChanged(channel);
            }
            for (ColibriConferenceIQ.SctpConnection sctpConnIq : contentIQ.getSctpConnections()) {
                SctpConnection sctpConn;
                String str1 = sctpConnIq.getID();
                String endpointID = sctpConnIq.getEndpoint();
                int expire = sctpConnIq.getExpire();
                String channelBundleId = sctpConnIq.getChannelBundleId();
                channelBundleIdsToDescribe.add(channelBundleId);
                if (str1 == null) {
                    if (expire == 0)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "SCTP connection expire request for empty ID");
                    if (endpointID == null)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "No endpoint ID specified for the new SCTP connection");
                    AbstractEndpoint endpoint = conference.getOrCreateEndpoint(endpointID);
                    if (endpoint == null)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.internal_server_error, "Failed to create new endpoint for ID: " + endpointID);
                    int sctpPort = sctpConnIq.getPort();
                    try {
                        sctpConn = content.createSctpConnection(endpoint, sctpPort, channelBundleId, sctpConnIq

                                .isInitiator());
                    } catch (IOException ioe) {
                        logger.error("Failed to create SctpConnection:", ioe);
                        sctpConn = null;
                    }
                    if (sctpConn == null)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.internal_server_error, "Failed to create new SCTP connection");
                } else {
                    sctpConn = content.getSctpConnection(str1);
                    if (sctpConn == null && expire == 0)
                        continue;
                    if (sctpConn == null)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "No SCTP connection found for ID: " + str1);
                }
                if (expire != -1) {
                    if (expire < 0)
                        return IQUtils.createError((IQ)conferenceIQ, XMPPError.Condition.bad_request, "Invalid 'expire' value: " + expire);
                    sctpConn.setExpire(expire);
                    if (expire == 0 && sctpConn.isExpired())
                        continue;
                }
                if (endpointID != null)
                    sctpConn.setEndpoint(endpointID);
                Boolean initiator = sctpConnIq.isInitiator();
                if (initiator != null) {
                    sctpConn.setInitiator(initiator.booleanValue());
                } else {
                    initiator = Boolean.valueOf(true);
                }
                sctpConn.setTransport(sctpConnIq.getTransport());
                if (channelBundleId != null) {
                    TransportManager transportManager = conference.getTransportManager(channelBundleId, true, initiator

                            .booleanValue());
                    transportManager.addChannel(sctpConn);
                }
                ColibriConferenceIQ.SctpConnection responseSctpIq = new ColibriConferenceIQ.SctpConnection();
                sctpConn.describe((ColibriConferenceIQ.ChannelCommon)responseSctpIq);
                responseContentIQ.addSctpConnection(responseSctpIq);
            }
        }
        for (ColibriConferenceIQ.ChannelBundle channelBundleIq : conferenceIQ.getChannelBundles()) {
            channelBundleIdsToDescribe.add(channelBundleIq.getId());
            TransportManager transportManager = conference.getTransportManager(channelBundleIq.getId());
            IceUdpTransportPacketExtension transportIq = channelBundleIq.getTransport();
            if (transportManager != null && transportIq != null)
                transportManager.startConnectivityEstablishment(transportIq);
        }
        for (ColibriConferenceIQ.Endpoint colibriEndpoint : conferenceIQ.getEndpoints())
            conference.updateEndpoint(colibriEndpoint);
        conference.describeChannelBundles(responseConferenceIQ, channelBundleIdsToDescribe);
        conference.describeEndpoints(responseConferenceIQ);
        responseConferenceIQ.setType(IQ.Type.result);
        return (IQ)responseConferenceIQ;
    }

    public IQ handleHealthCheckIQ(HealthCheckIQ healthCheckIQ) {
        if (this.authorizedSourcePattern != null &&

                !this.authorizedSourcePattern.matcher((CharSequence)healthCheckIQ.getFrom()).matches())
            return
                    IQUtils.createError((IQ)healthCheckIQ, XMPPError.Condition.not_authorized);
        try {
            healthCheck();
            return IQ.createResultIQ((IQ)healthCheckIQ);
        } catch (Exception e) {
            e.printStackTrace();
            return
                    IQUtils.createError((IQ)healthCheckIQ, XMPPError.Condition.internal_server_error, e

                            .getMessage());
        }
    }

    public void healthCheck() throws Exception {
        if (this.health == null)
            throw new Exception("No health checks running");
        this.health.check();
    }

    public IQ handleShutdownIQ(ShutdownIQ shutdownIQ) {
        if (this.shutdownSourcePattern == null)
            return IQUtils.createError((IQ)shutdownIQ, XMPPError.Condition.service_unavailable);
        Jid from = shutdownIQ.getFrom();
        if (from != null && this.shutdownSourcePattern.matcher((CharSequence)from).matches()) {
            logger.info("Accepted shutdown request from: " + from);
            if (shutdownIQ.isGracefulShutdown()) {
                if (!isShutdownInProgress())
                    enableGracefulShutdownMode();
            } else {
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            Thread.sleep(1000);

                            logger.warn("JVB force shutdown - now");

                            System.exit(0);
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                    }
                }, "ForceShutdownThread").start();
            }
            return IQ.createResultIQ((IQ)shutdownIQ);
        }
        logger.error("Rejected shutdown request from: " + from);
        return IQUtils.createError((IQ)shutdownIQ, XMPPError.Condition.not_authorized);
    }

    public void handleIQResponse(IQ response) {
        PubSubPublisher.handleIQResponse(response);
    }

    public boolean isShutdownInProgress() {
        return this.shutdownInProgress;
    }

    public boolean isXmppApiEnabled() {
        ConfigurationService config = (ConfigurationService)ServiceUtils.getService(
                getBundleContext(), ConfigurationService.class);
        return (config != null && config
                .getBoolean("org.jitsi.videobridge.xmpp", false));
    }

    private void maybeDoShutdown() {
        if (!this.shutdownInProgress)
            return;
        synchronized (this.conferences) {
            if (this.conferences.isEmpty()) {
                ShutdownService shutdownService = (ShutdownService)ServiceUtils.getService(this.bundleContext, ShutdownService.class);
                logger.info("Videobridge is shutting down NOW");
                shutdownService.beginShutdown();
            }
        }
    }

    public void setAuthorizedSourceRegExp(String authorizedSourceRegExp) {
        if (!StringUtils.isNullOrEmpty(authorizedSourceRegExp)) {
            this
                    .authorizedSourcePattern = Pattern.compile(authorizedSourceRegExp);
            if (this.shutdownSourcePattern == null)
                this.shutdownSourcePattern = this.authorizedSourcePattern;
        } else {
            if (this.shutdownSourcePattern == this.authorizedSourcePattern)
                this.shutdownSourcePattern = null;
            this.authorizedSourcePattern = null;
        }
    }

    void start(BundleContext bundleContext) throws Exception {
        UlimitCheck.printUlimits();
        ConfigurationService cfg = (ConfigurationService)ServiceUtils2.getService(bundleContext, ConfigurationService.class);
        this.videobridgeExpireThread.start(bundleContext);
        if (this.health != null)
            this.health.stop();
        this.health = new Health(this, cfg);
        this

                .defaultProcessingOptions = (cfg == null) ? 0 : cfg.getInt("org.jitsi.videobridge.defaultOptions", 0);
        if (logger.isDebugEnabled())
            logger.debug("Default videobridge processing options: 0x" +

                    Integer.toHexString(this.defaultProcessingOptions));
        String shutdownSourcesRegexp = (cfg == null) ? null : cfg.getString("org.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP");
        if (!StringUtils.isNullOrEmpty(shutdownSourcesRegexp))
            try {
                this.shutdownSourcePattern = Pattern.compile(shutdownSourcesRegexp);
            } catch (PatternSyntaxException exc) {
                logger.error("Error parsing enableGracefulShutdownMode sources reg expr: " + shutdownSourcesRegexp, exc);
            }
        String authorizedSourceRegexp = (cfg == null) ? null : cfg.getString("org.jitsi.videobridge.AUTHORIZED_SOURCE_REGEXP");
        if (!StringUtils.isNullOrEmpty(authorizedSourceRegexp)) {
            try {
                logger.info("Authorized source regexp: " + authorizedSourceRegexp);
                setAuthorizedSourceRegExp(authorizedSourceRegexp);
            } catch (PatternSyntaxException exc) {
                logger.error("Error parsing authorized sources regexp: " + shutdownSourcesRegexp, exc);
            }
        } else {
            logger.warn("No authorized source regexp configured. Will accept requests from any source.");
        }
        ProviderManager.addIQProvider("conference", "http://jitsi.org/protocol/colibri", new ColibriIQProvider());
        ProviderManager.addExtensionProvider("transport", "urn:xmpp:jingle:transports:ice-udp:1", new DefaultPacketExtensionProvider(IceUdpTransportPacketExtension.class));
        ProviderManager.addExtensionProvider("transport", "urn:xmpp:jingle:transports:raw-udp:1", new DefaultPacketExtensionProvider(RawUdpTransportPacketExtension.class));
        DefaultPacketExtensionProvider<CandidatePacketExtension> candidatePacketExtensionProvider = new DefaultPacketExtensionProvider(CandidatePacketExtension.class);
        ProviderManager.addExtensionProvider("candidate", "urn:xmpp:jingle:transports:ice-udp:1", candidatePacketExtensionProvider);
        ProviderManager.addExtensionProvider("candidate", "urn:xmpp:jingle:transports:raw-udp:1", candidatePacketExtensionProvider);
        ProviderManager.addExtensionProvider("rtcp-mux", "urn:xmpp:jingle:transports:ice-udp:1", new DefaultPacketExtensionProvider(RtcpmuxPacketExtension.class));
        ProviderManager.addExtensionProvider("fingerprint", "urn:xmpp:jingle:apps:dtls:0", new DefaultPacketExtensionProvider(DtlsFingerprintPacketExtension.class));
        ProviderManager.addIQProvider(PubSubElementType.PUBLISH
                .getElementName(), PubSubElementType.PUBLISH
                .getNamespace().getXmlns(), new PubSubProvider());
        ProviderManager.addIQProvider("healthcheck", "http://jitsi.org/protocol/healthcheck", new HealthCheckIQProvider());
        this.bundleContext = bundleContext;
        startIce4j(bundleContext, cfg);
        LibJitsi.getMediaService();
    }

    private void startIce4j(BundleContext bundleContext, ConfigurationService cfg) {
        StunStack.setPacketLogger(null);
        if (cfg != null) {
            List<String> ice4jPropertyNames = cfg.getPropertyNamesByPrefix("org.ice4j.", false);
            if (ice4jPropertyNames != null && !ice4jPropertyNames.isEmpty())
                for (String propertyName : ice4jPropertyNames) {
                    String propertyValue = cfg.getString(propertyName);
                    if (propertyValue != null)
                        System.setProperty(propertyName, propertyValue);
                }
            String oldPrefix = "org.jitsi.videobridge";
            String newPrefix = "org.ice4j.ice.harvest";
            for (String propertyName : new String[] { "org.jitsi.videobridge.NAT_HARVESTER_LOCAL_ADDRESS", "org.jitsi.videobridge.NAT_HARVESTER_PUBLIC_ADDRESS", "org.jitsi.videobridge.DISABLE_AWS_HARVESTER", "org.jitsi.videobridge.FORCE_AWS_HARVESTER", "org.jitsi.videobridge.STUN_MAPPING_HARVESTER_ADDRESSES" }) {
                String propertyValue = cfg.getString(propertyName);
                if (propertyValue != null) {
                    String newPropertyName = newPrefix + propertyName.substring(oldPrefix.length());
                    System.setProperty(newPropertyName, propertyValue);
                }
            }
            String disableNackTerminaton = cfg.getString("org.jitsi.videobridge.DISABLE_NACK_TERMINATION");
            if (disableNackTerminaton != null)
                System.setProperty("org.jitsi.impl.neomedia.rtcp.DISABLE_NACK_TERMINATION", disableNackTerminaton);
        }
        try {
            HostCandidateHarvester.initializeInterfaceFilters();
        } catch (Exception e) {
            logger.warn("There were errors during host candidate interface filters initialization.", e);
        }
        (new Thread(MappingCandidateHarvesters::initialize)).start();
    }

    void stop(BundleContext bundleContext) throws Exception {
        try {
            if (this.health != null) {
                this.health.stop();
                this.health = null;
            }
            ConfigurationService cfg = (ConfigurationService)ServiceUtils2.getService(bundleContext, ConfigurationService.class);
            stopIce4j(bundleContext, cfg);
        } finally {
            this.videobridgeExpireThread.stop(bundleContext);
            this.bundleContext = null;
        }
    }

    private void stopIce4j(BundleContext bundleContext, ConfigurationService cfg) {
        IceUdpTransportManager.closeStaticConfiguration(cfg);
        if (cfg != null) {
            List<String> ice4jPropertyNames = cfg.getPropertyNamesByPrefix("org.ice4j.", false);
            if (ice4jPropertyNames != null && !ice4jPropertyNames.isEmpty())
                for (String propertyName : ice4jPropertyNames)
                    System.clearProperty(propertyName);
            String oldPrefix = "org.jitsi.videobridge";
            String newPrefix = "org.ice4j.ice.harvest";
            for (String propertyName : new String[] { "org.jitsi.videobridge.NAT_HARVESTER_LOCAL_ADDRESS", "org.jitsi.videobridge.NAT_HARVESTER_PUBLIC_ADDRESS", "org.jitsi.videobridge.DISABLE_AWS_HARVESTER", "org.jitsi.videobridge.FORCE_AWS_HARVESTER", "org.jitsi.videobridge.STUN_MAPPING_HARVESTER_ADDRESSES" }) {
                String propertyValue = cfg.getString(propertyName);
                if (propertyValue != null) {
                    String newPropertyName = newPrefix + propertyName.substring(oldPrefix.length());
                    System.clearProperty(newPropertyName);
                }
            }
            System.clearProperty(VideoChannel.ENABLE_LIPSYNC_HACK_PNAME);
            System.clearProperty("org.jitsi.impl.neomedia.rtcp.DISABLE_NACK_TERMINATION");
        }
    }

    public int[] getConferenceChannelAndStreamCount() {
        Conference[] conferences = getConferences();
        int conferenceCount = 0, channelCount = 0, streamCount = 0;
        if (conferences != null && conferences.length != 0)
            for (Conference conference : conferences) {
                if (conference != null && !conference.isExpired()) {
                    conferenceCount++;
                    for (Content content : conference.getContents()) {
                        if (content != null && !content.isExpired()) {
                            int contentChannelCount = content.getChannelCount();
                            channelCount += contentChannelCount;
                            if (MediaType.VIDEO.equals(content.getMediaType()))
                                streamCount +=
                                        getContentStreamCount(content, contentChannelCount);
                        }
                    }
                }
            }
        return new int[] { conferenceCount, channelCount, streamCount };
    }

    String getConferenceCountString() {
        int[] metrics = getConferenceChannelAndStreamCount();
        StringBuilder sb = new StringBuilder();
        sb.append("conf_count=").append(metrics[0])
                .append(",ch_count=").append(metrics[1])
                .append(",v_streams=").append(metrics[2]);
        return sb.toString();
    }

    private int getContentStreamCount(Content content, int contentChannelCount) {
        return content.getChannels().stream()
                .filter(c ->
                        (c != null && !c.isExpired() && c instanceof VideoChannel))
                .mapToInt(c -> {
                    int lastN = ((VideoChannel)c).getLastN();
                    int lastNSteams = (lastN == -1) ? (contentChannelCount - 1) : Math.min(lastN, contentChannelCount - 1);
                    return lastNSteams + 1;
                }).sum();
    }

    public static class Statistics {
        public AtomicInteger totalChannels = new AtomicInteger(0);

        public AtomicInteger totalNoTransportChannels = new AtomicInteger(0);

        public AtomicInteger totalNoPayloadChannels = new AtomicInteger(0);

        public AtomicInteger totalFailedConferences = new AtomicInteger(0);

        public AtomicInteger totalPartiallyFailedConferences = new AtomicInteger(0);

        public AtomicInteger totalConferencesCompleted = new AtomicInteger(0);

        public AtomicInteger totalConferencesCreated = new AtomicInteger(0);

        public AtomicLong totalConferenceSeconds = new AtomicLong();

        public AtomicLong totalLossControlledParticipantMs = new AtomicLong();

        public AtomicLong totalLossLimitedParticipantMs = new AtomicLong();

        public AtomicLong totalLossDegradedParticipantMs = new AtomicLong();

        public AtomicInteger totalUdpTransportManagers = new AtomicInteger();

        public AtomicInteger totalTcpTransportManagers = new AtomicInteger();

        public AtomicLong totalDataChannelMessagesReceived = new AtomicLong();

        public AtomicLong totalDataChannelMessagesSent = new AtomicLong();

        public AtomicLong totalColibriWebSocketMessagesReceived = new AtomicLong();

        public AtomicLong totalColibriWebSocketMessagesSent = new AtomicLong();

        public AtomicLong totalBytesReceived = new AtomicLong();

        public AtomicLong totalBytesSent = new AtomicLong();

        public AtomicLong totalPacketsReceived = new AtomicLong();

        public AtomicLong totalPacketsSent = new AtomicLong();

        public AtomicLong totalBytesReceivedOcto = new AtomicLong();

        public AtomicLong totalBytesSentOcto = new AtomicLong();

        public AtomicLong totalPacketsReceivedOcto = new AtomicLong();

        public AtomicLong totalPacketsSentOcto = new AtomicLong();
    }
}
