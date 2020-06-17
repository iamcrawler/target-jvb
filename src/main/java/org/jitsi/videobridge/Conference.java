package org.jitsi.videobridge;


import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.java.sip.communicator.util.ServiceUtils;
import org.jetbrains.annotations.NotNull;
import org.jitsi.eventadmin.EventAdmin;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.recording.Recorder;
import org.jitsi.service.neomedia.recording.RecorderEventHandler;
import org.jitsi.service.neomedia.recording.Synchronizer;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.StringUtils;
import org.jitsi.utils.event.PropertyChangeNotifier;
import org.jitsi.utils.event.WeakReferencePropertyChangeListener;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.octo.OctoEndpoints;
import org.jitsi.videobridge.util.Expireable;
import org.jitsi.videobridge.util.ExpireableImpl;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Localpart;
import org.osgi.framework.BundleContext;

public class Conference extends PropertyChangeNotifier implements PropertyChangeListener, Expireable {
    public static final String ENDPOINTS_PROPERTY_NAME = Conference.class
            .getName() + ".endpoints";

    private static final Logger classLogger = Logger.getLogger(Conference.class);

    public static String getLoggingId(Conference conference) {
        return (conference == null) ? "conf_id=null" : conference
                .getLoggingId();
    }

    private final List<Content> contents = new LinkedList<>();

    private EndpointRecorder endpointRecorder = null;

    private final List<AbstractEndpoint> endpoints = new LinkedList<>();

    private OctoEndpoints octoEndpoints = null;

    private final EventAdmin eventAdmin;

    private boolean expired = false;

    private final Jid focus;

    private final String id;

    private final String gid;

    private final String loggingId;

    private Localpart name;

    private long lastActivityTime;

    private Jid lastKnownFocus;

    private final PropertyChangeListener propertyChangeListener = (PropertyChangeListener)new WeakReferencePropertyChangeListener(this);

    private RecorderEventHandlerImpl recorderEventHandler = null;

    private boolean recording = false;

    private String recordingDirectory = null;

    private String recordingPath = null;

    private final ConferenceSpeechActivity speechActivity;

    private final Map<String, IceUdpTransportManager> transportManagers = new HashMap<>();

    private final Videobridge videobridge;

    private final Statistics statistics = new Statistics();

    private final Logger logger = Logger.getLogger(classLogger, null);

    private final boolean includeInStatistics;

    private final long creationTime = System.currentTimeMillis();

    private final ExpireableImpl expireableImpl;

    public Conference(Videobridge videobridge, String id, Jid focus, Localpart name, boolean enableLogging, String gid) {
        this.videobridge = Objects.<Videobridge>requireNonNull(videobridge, "videobridge");
        this.id = Objects.<String>requireNonNull(id, "id");
        this.gid = gid;
        this.loggingId = "conf_id=" + id;
        this.focus = focus;
        this.eventAdmin = enableLogging ? videobridge.getEventAdmin() : null;
        this.includeInStatistics = enableLogging;
        this.name = name;
        if (!enableLogging)
            this.logger.setLevel(Level.WARNING);
        this.lastKnownFocus = focus;
        this.speechActivity = new ConferenceSpeechActivity(this);
        this.speechActivity.addPropertyChangeListener(this.propertyChangeListener);
        this.expireableImpl = new ExpireableImpl(this.loggingId, this::expire);
        if (enableLogging) {
            this.eventAdmin.sendEvent(EventFactory.conferenceCreated(this));
            Videobridge.Statistics videobridgeStatistics = videobridge.getStatistics();
            videobridgeStatistics.totalConferencesCreated.incrementAndGet();
        }
        touch();
    }

    public void appendDiagnosticInformation(DiagnosticContext diagnosticContext) {
        Objects.requireNonNull(diagnosticContext);
        if (this.name != null)
            diagnosticContext.put("conf_name", this.name.toString());
        diagnosticContext.put("conf_creation_time_ms", Long.valueOf(this.creationTime));
    }

    public Statistics getStatistics() {
        return this.statistics;
    }

    public boolean includeInStatistics() {
        return this.includeInStatistics;
    }

    public void sendMessage(String msg, List<AbstractEndpoint> endpoints, boolean sendToOcto) {
        for (AbstractEndpoint endpoint : endpoints) {
            try {
                endpoint.sendMessage(msg);
            } catch (IOException e) {
                this.logger.error("Failed to send message on data channel to: " + endpoint

                        .getID() + ", msg: " + msg, e);
            }
        }
        OctoEndpoints octoEndpoints = this.octoEndpoints;
        if (sendToOcto && octoEndpoints != null)
            octoEndpoints.sendMessage(msg);
    }

    public void sendMessage(String msg, List<AbstractEndpoint> endpoints) {
        sendMessage(msg, endpoints, false);
    }

    public void broadcastMessage(String msg, boolean sendToOcto) {
        sendMessage(msg, getEndpoints(), sendToOcto);
    }

    public void broadcastMessage(String msg) {
        broadcastMessage(msg, false);
    }

    @Deprecated
    private boolean checkRecordingDirectory(String path) {
        if (StringUtils.isNullOrEmpty(path))
            return false;
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdir();
            if (!dir.exists())
                return false;
        }
        if (!dir.isDirectory() || !dir.canWrite())
            return false;
        return true;
    }

    void closeTransportManager(TransportManager transportManager) {
        synchronized (this.transportManagers) {
            this.transportManagers.values().removeIf(tm -> (tm == transportManager));
            try {
                transportManager.close();
            } catch (Throwable t) {
                this.logger.warn("Failed to close an IceUdpTransportManager of conference " +

                        getID() + "!", t);
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                } else if (t instanceof ThreadDeath) {
                    throw (ThreadDeath)t;
                }
            }
        }
    }

    private void closeTransportManagers() {
        synchronized (this.transportManagers) {
            Collection<TransportManager> transportManagers = new LinkedList<>(this.transportManagers.values());
            transportManagers.forEach(this::closeTransportManager);
            this.transportManagers.clear();
        }
    }

    void describeChannelBundles(ColibriConferenceIQ iq, Set<String> channelBundleIdsToDescribe) {
        synchronized (this.transportManagers) {
            for (Map.Entry<String, IceUdpTransportManager> entry : this.transportManagers.entrySet()) {
                String id = entry.getKey();
                if (channelBundleIdsToDescribe == null || channelBundleIdsToDescribe
                        .contains(id)) {
                    ColibriConferenceIQ.ChannelBundle responseBundleIQ = new ColibriConferenceIQ.ChannelBundle(id);
                    ((IceUdpTransportManager)entry.getValue()).describe(responseBundleIQ);
                    iq.addChannelBundle(responseBundleIQ);
                }
            }
        }
    }

    void describeEndpoints(ColibriConferenceIQ iq) {
        getEndpoints().forEach(en -> iq.addEndpoint(new ColibriConferenceIQ.Endpoint(en.getID(), en.getStatsId(), en.getDisplayName())));
    }

    public void describeDeep(ColibriConferenceIQ iq) {
        describeShallow(iq);
        if (isRecording()) {
            ColibriConferenceIQ.Recording recordingIQ = new ColibriConferenceIQ.Recording(ColibriConferenceIQ.Recording.State.ON.toString());
            recordingIQ.setDirectory(getRecordingDirectory());
            iq.setRecording(recordingIQ);
        }
        for (Content content : getContents()) {
            ColibriConferenceIQ.Content contentIQ = iq.getOrCreateContent(content.getName());
            for (Channel channel : content.getChannels()) {
                if (channel instanceof SctpConnection) {
                    ColibriConferenceIQ.SctpConnection sctpConnectionIQ = new ColibriConferenceIQ.SctpConnection();
                    channel.describe((ColibriConferenceIQ.ChannelCommon)sctpConnectionIQ);
                    contentIQ.addSctpConnection(sctpConnectionIQ);
                    continue;
                }
                ColibriConferenceIQ.Channel channelIQ = new ColibriConferenceIQ.Channel();
                channel.describe((ColibriConferenceIQ.ChannelCommon)channelIQ);
                contentIQ.addChannel(channelIQ);
            }
        }
    }

    public void describeShallow(ColibriConferenceIQ iq) {
        iq.setID(getID());
        iq.setName(getName());
    }

    private void dominantSpeakerChanged() {
        AbstractEndpoint dominantSpeaker = this.speechActivity.getDominantEndpoint();
        if (this.logger.isInfoEnabled()) {
            String id = (dominantSpeaker == null) ? "null" : dominantSpeaker.getID();
            this.logger.info(Logger.Category.STATISTICS, "ds_change," +
                    getLoggingId() + " ds_id=" + id);
        }
        if (dominantSpeaker != null) {
            broadcastMessage(
                    EndpointMessageBuilder.createDominantSpeakerEndpointChangeEvent(dominantSpeaker
                            .getID()));
            if (isRecording() && this.recorderEventHandler != null)
                this.recorderEventHandler.dominantSpeakerChanged(dominantSpeaker);
        }
    }

    public void expire() {
        synchronized (this) {
            if (this.expired)
                return;
            this.expired = true;
        }
        EventAdmin eventAdmin = getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.conferenceExpired(this));
        setRecording(false);
        if (this.recorderEventHandler != null) {
            this.recorderEventHandler.close();
            this.recorderEventHandler = null;
        }
        Videobridge videobridge = getVideobridge();
        try {
            videobridge.expireConference(this);
        } finally {
            for (Content content : getContents()) {
                try {
                    content.expire();
                } catch (Throwable t) {
                    this.logger.warn("Failed to expire content " + content
                            .getName() + " of conference " +
                            getID() + "!", t);
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    } else if (t instanceof ThreadDeath) {
                        throw (ThreadDeath)t;
                    }
                }
            }
            closeTransportManagers();
            if (this.includeInStatistics)
                updateStatisticsOnExpire();
        }
    }

    private void updateStatisticsOnExpire() {
        long durationSeconds = Math.round((System.currentTimeMillis() - this.creationTime) / 1000.0D);
        Videobridge.Statistics videobridgeStatistics = getVideobridge().getStatistics();
        videobridgeStatistics.totalConferencesCompleted
                .incrementAndGet();
        videobridgeStatistics.totalConferenceSeconds.addAndGet(durationSeconds);
        videobridgeStatistics.totalUdpTransportManagers.addAndGet(this.statistics.totalUdpTransportManagers
                .get());
        videobridgeStatistics.totalTcpTransportManagers.addAndGet(this.statistics.totalTcpTransportManagers
                .get());
        videobridgeStatistics.totalNoPayloadChannels.addAndGet(this.statistics.totalNoPayloadChannels
                .get());
        videobridgeStatistics.totalNoTransportChannels.addAndGet(this.statistics.totalNoTransportChannels
                .get());
        videobridgeStatistics.totalChannels.addAndGet(this.statistics.totalChannels
                .get());
        videobridgeStatistics.totalBytesReceived.addAndGet(this.statistics.totalBytesReceived
                .get());
        videobridgeStatistics.totalBytesSent.addAndGet(this.statistics.totalBytesSent
                .get());
        videobridgeStatistics.totalPacketsReceived.addAndGet(this.statistics.totalPacketsReceived
                .get());
        videobridgeStatistics.totalPacketsSent.addAndGet(this.statistics.totalPacketsSent
                .get());
        videobridgeStatistics.totalBytesReceivedOcto.addAndGet(this.statistics.totalBytesReceivedOcto
                .get());
        videobridgeStatistics.totalBytesSentOcto.addAndGet(this.statistics.totalBytesSentOcto
                .get());
        videobridgeStatistics.totalPacketsReceivedOcto.addAndGet(this.statistics.totalPacketsReceivedOcto
                .get());
        videobridgeStatistics.totalPacketsSentOcto.addAndGet(this.statistics.totalPacketsSentOcto
                .get());
        boolean hasFailed = (this.statistics.totalNoPayloadChannels.get() >= this.statistics.totalChannels.get());
        boolean hasPartiallyFailed = (this.statistics.totalNoPayloadChannels.get() != 0);
        if (hasPartiallyFailed)
            videobridgeStatistics.totalPartiallyFailedConferences
                    .incrementAndGet();
        if (hasFailed)
            videobridgeStatistics.totalFailedConferences.incrementAndGet();
        if (this.logger.isInfoEnabled()) {
            int[] metrics = this.videobridge.getConferenceChannelAndStreamCount();
            StringBuilder sb = new StringBuilder("expire_conf,");
            sb.append(getLoggingId())
                    .append(" duration=").append(durationSeconds)
                    .append(",conf_count=").append(metrics[0])
                    .append(",ch_count=").append(metrics[1])
                    .append(",v_streams=").append(metrics[2])
                    .append(",conf_completed=")
                    .append(videobridgeStatistics.totalConferencesCompleted)
                    .append(",no_payload_ch=")
                    .append(videobridgeStatistics.totalNoPayloadChannels)
                    .append(",no_transport_ch=")
                    .append(videobridgeStatistics.totalNoTransportChannels)
                    .append(",total_ch=")
                    .append(videobridgeStatistics.totalChannels)
                    .append(",has_failed=").append(hasFailed)
                    .append(",has_partially_failed=").append(hasPartiallyFailed);
            this.logger.info(Logger.Category.STATISTICS, sb.toString());
        }
    }

    public void expireContent(Content content) {
        boolean expireContent;
        synchronized (this.contents) {
            expireContent = this.contents.contains(content);
            if (expireContent)
                this.contents.remove(content);
        }
        if (expireContent)
            content.expire();
    }

    public Channel findChannelByReceiveSSRC(long receiveSSRC, MediaType mediaType) {
        for (Content content : getContents()) {
            if (mediaType.equals(content.getMediaType())) {
                Channel channel = content.findChannelByReceiveSSRC(receiveSSRC);
                if (channel != null)
                    return channel;
            }
        }
        return null;
    }

    AbstractEndpoint findEndpointByReceiveSSRC(long receiveSSRC, MediaType mediaType) {
        Channel channel = findChannelByReceiveSSRC(receiveSSRC, mediaType);
        return (channel == null) ? null : channel.getEndpoint(receiveSSRC);
    }

    public BundleContext getBundleContext() {
        return getVideobridge().getBundleContext();
    }

    public Content[] getContents() {
        synchronized (this.contents) {
            return this.contents.<Content>toArray(new Content[this.contents.size()]);
        }
    }

    public AbstractEndpoint getEndpoint(String id) {
        return getEndpoint(id, false);
    }

    private AbstractEndpoint getEndpoint(String id, boolean create) {
        AbstractEndpoint endpoint;
        boolean changed;
        synchronized (this.endpoints) {
            changed = this.endpoints.removeIf(AbstractEndpoint::isExpired);
            endpoint = this.endpoints.stream().filter(e -> e.getID().equals(id)).findFirst().orElse(null);
            if (create && endpoint == null) {
                endpoint = new Endpoint(id, this);
                endpoint.addPropertyChangeListener(this.propertyChangeListener);
                this.endpoints.add(endpoint);
                changed = true;
                EventAdmin eventAdmin = getEventAdmin();
                if (eventAdmin != null)
                    eventAdmin.sendEvent(
                            EventFactory.endpointCreated(endpoint));
            }
        }
        if (changed)
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
        return endpoint;
    }

    public int getEndpointCount() {
        return getEndpoints().size();
    }

    @Deprecated
    private EndpointRecorder getEndpointRecorder() {
        if (this.endpointRecorder == null)
            try {
                this

                        .endpointRecorder = new EndpointRecorder(getRecordingPath() + "/endpoints.json");
            } catch (IOException ioe) {
                this.logger.warn("Could not create EndpointRecorder. " + ioe);
            }
        return this.endpointRecorder;
    }

    public List<AbstractEndpoint> getEndpoints() {
        boolean changed;
        List<AbstractEndpoint> copy;
        synchronized (this.endpoints) {
            changed = this.endpoints.removeIf(AbstractEndpoint::isExpired);
            copy = new ArrayList<>(this.endpoints);
        }
        if (changed)
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
        return copy;
    }

    public final Jid getFocus() {
        return this.focus;
    }

    public final String getID() {
        return this.id;
    }

    public long getLastActivityTime() {
        synchronized (this) {
            return this.lastActivityTime;
        }
    }

    public Jid getLastKnowFocus() {
        return this.lastKnownFocus;
    }

    MediaService getMediaService() {
        MediaService mediaService = (MediaService)ServiceUtils.getService(getBundleContext(), MediaService.class);
        if (mediaService == null)
            mediaService = LibJitsi.getMediaService();
        return mediaService;
    }

    public Content getOrCreateContent(String name) {
        Content content;
        synchronized (this.contents) {
            content = this.contents.stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
            if (content != null) {
                content.touch();
                return content;
            }
            content = new Content(this, name);
            if (isRecording())
                content.setRecording(true, getRecordingPath());
            this.contents.add(content);
        }
        if (this.logger.isInfoEnabled()) {
            Videobridge videobridge = getVideobridge();
            this.logger.info(Logger.Category.STATISTICS, "create_content," + content
                    .getLoggingId() + " " + videobridge
                    .getConferenceCountString());
        }
        return content;
    }

    public AbstractEndpoint getOrCreateEndpoint(String id) {
        return getEndpoint(id, true);
    }

    @Deprecated
    RecorderEventHandler getRecorderEventHandler() {
        if (this.recorderEventHandler == null) {
            Throwable t;
            try {
                this

                        .recorderEventHandler = new RecorderEventHandlerImpl(this, getMediaService().createRecorderEventHandlerJson(
                        getRecordingPath() + "/metadata.json"));
                t = null;
            } catch (IOException|IllegalArgumentException e) {
                t = e;
            }
            if (t != null)
                this.logger.warn("Could not create RecorderEventHandler. " + t);
        }
        return this.recorderEventHandler;
    }

    @Deprecated
    String getRecordingDirectory() {
        if (this.recordingDirectory == null) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd.HH-mm-ss.");
            this
                    .recordingDirectory = dateFormat.format(new Date()) + getID() + ((this.name != null) ? ("_" + this.name) : "");
        }
        return this.recordingDirectory;
    }

    @Deprecated
    private String getRecordingPath() {
        if (this.recordingPath == null) {
            ConfigurationService cfg = getVideobridge().getConfigurationService();
            if (cfg != null) {
                boolean recordingIsEnabled = cfg.getBoolean("org.jitsi.videobridge.ENABLE_MEDIA_RECORDING", false);
                if (recordingIsEnabled) {
                    String path = cfg.getString("org.jitsi.videobridge.MEDIA_RECORDING_PATH", null);
                    if (path != null)
                        this
                                .recordingPath = path + "/" + getRecordingDirectory();
                }
            }
        }
        return this.recordingPath;
    }

    public ConferenceSpeechActivity getSpeechActivity() {
        return this.speechActivity;
    }

    TransportManager getTransportManager(String channelBundleId) {
        return getTransportManager(channelBundleId, false, true);
    }

    IceUdpTransportManager getTransportManager(String channelBundleId, boolean create, boolean initiator) {
        IceUdpTransportManager transportManager;
        synchronized (this.transportManagers) {
            transportManager = this.transportManagers.get(channelBundleId);
            if (transportManager == null && create && !isExpired()) {
                try {
                    transportManager = new IceUdpTransportManager(this, initiator, 1, channelBundleId);
                } catch (IOException ioe) {
                    throw new UndeclaredThrowableException(ioe);
                }
                this.transportManagers.put(channelBundleId, transportManager);
                this.logger.info(Logger.Category.STATISTICS, "create_ice_tm," +
                        getLoggingId() + " ufrag=" + transportManager
                        .getLocalUfrag() + ",bundle=" + channelBundleId + ",initiator=" + initiator);
            }
        }
        return transportManager;
    }

    public final Videobridge getVideobridge() {
        return this.videobridge;
    }

    public boolean isExpired() {
        return this.expired;
    }

    @Deprecated
    public boolean isRecording() {
        boolean recording = this.recording;
        if (recording)
            synchronized (this.contents) {
                for (Content content : this.contents) {
                    MediaType mediaType = content.getMediaType();
                    if (!MediaType.VIDEO.equals(mediaType) &&
                            !MediaType.AUDIO.equals(mediaType))
                        continue;
                    if (!content.isRecording())
                        recording = false;
                }
            }
        if (this.recording != recording)
            setRecording(recording);
        return this.recording;
    }

    public void propertyChange(PropertyChangeEvent ev) {
        Object source = ev.getSource();
        if (isExpired()) {
            if (source instanceof PropertyChangeNotifier)
                ((PropertyChangeNotifier)source).removePropertyChangeListener(this.propertyChangeListener);
        } else if (source == this.speechActivity) {
            speechActivityPropertyChange(ev);
        } else if (Endpoint.SELECTED_ENDPOINTS_PROPERTY_NAME.equals(ev.getPropertyName())) {
            Set<String> oldSelectedEndpoints = (Set<String>)ev.getOldValue();
            Set<String> newSelectedEndpoints = (Set<String>)ev.getNewValue();
            oldSelectedEndpoints.stream()
                    .filter(oldSelectedEp -> !newSelectedEndpoints.contains(oldSelectedEp))
                    .map(this::getEndpoint)
                    .filter(Objects::nonNull)
                    .forEach(AbstractEndpoint::decrementSelectedCount);
            newSelectedEndpoints.stream()
                    .filter(newSelectedEp -> !oldSelectedEndpoints.contains(newSelectedEp))
                    .map(this::getEndpoint)
                    .filter(Objects::nonNull)
                    .forEach(AbstractEndpoint::incrementSelectedCount);
        }
    }

    void endpointExpired(AbstractEndpoint endpoint) {
        boolean removed;
        synchronized (this.endpoints) {
            removed = this.endpoints.removeIf(AbstractEndpoint::isExpired);
        }
        if (removed)
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
    }

    public void addEndpoint(AbstractEndpoint endpoint) {
        synchronized (this.endpoints) {
            this.endpoints.add(endpoint);
        }
        firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
    }

    public OctoEndpoints getOctoEndpoints() {
        synchronized (this.endpoints) {
            if (this.octoEndpoints == null)
                this.octoEndpoints = new OctoEndpoints(this);
            return this.octoEndpoints;
        }
    }

    void endpointMessageTransportConnected(@NotNull AbstractEndpoint endpoint) {
        if (!isExpired()) {
            AbstractEndpoint dominantSpeaker = this.speechActivity.getDominantEndpoint();
            if (dominantSpeaker != null)
                try {
                    endpoint.sendMessage(
                            EndpointMessageBuilder.createDominantSpeakerEndpointChangeEvent(dominantSpeaker
                                    .getID()));
                } catch (IOException e) {
                    this.logger.error("Failed to send dominant speaker update on data channel to " + endpoint

                            .getID(), e);
                }
        }
    }

    public void setLastKnownFocus(Jid jid) {
        this.lastKnownFocus = jid;
    }

    @Deprecated
    boolean setRecording(boolean recording) {
        if (recording != this.recording) {
            if (recording) {
                if (this.logger.isDebugEnabled())
                    this.logger.debug("Starting recording for conference with id=" +

                            getID());
                String path = getRecordingPath();
                boolean failedToStart = !checkRecordingDirectory(path);
                if (!failedToStart) {
                    RecorderEventHandler handler = getRecorderEventHandler();
                    if (handler == null)
                        failedToStart = true;
                }
                if (!failedToStart) {
                    EndpointRecorder endpointRecorder = getEndpointRecorder();
                    if (endpointRecorder == null) {
                        failedToStart = true;
                    } else {
                        for (AbstractEndpoint endpoint : getEndpoints())
                            endpointRecorder.updateEndpoint(endpoint);
                    }
                }
                boolean first = true;
                Synchronizer synchronizer = null;
                for (Content content : this.contents) {
                    MediaType mediaType = content.getMediaType();
                    if (!MediaType.VIDEO.equals(mediaType) &&
                            !MediaType.AUDIO.equals(mediaType))
                        continue;
                    if (!failedToStart)
                        failedToStart = !content.setRecording(true, path);
                    if (failedToStart)
                        break;
                    if (first) {
                        first = false;
                        synchronizer = content.getRecorder().getSynchronizer();
                    } else {
                        Recorder recorder = content.getRecorder();
                        if (recorder != null)
                            recorder.setSynchronizer(synchronizer);
                    }
                    content.feedKnownSsrcsToSynchronizer();
                }
                if (failedToStart) {
                    recording = false;
                    this.logger.warn("Failed to start media recording for conference " +

                            getID());
                }
            }
            if (!recording) {
                if (this.logger.isDebugEnabled())
                    this.logger.debug("Stopping recording for conference with id=" +

                            getID());
                for (Content content : this.contents) {
                    MediaType mediaType = content.getMediaType();
                    if (MediaType.AUDIO.equals(mediaType) || MediaType.VIDEO
                            .equals(mediaType))
                        content.setRecording(false, null);
                }
                if (this.recorderEventHandler != null)
                    this.recorderEventHandler.close();
                this.recorderEventHandler = null;
                this.recordingPath = null;
                this.recordingDirectory = null;
                if (this.endpointRecorder != null)
                    this.endpointRecorder.close();
                this.endpointRecorder = null;
            }
            this.recording = recording;
        }
        return this.recording;
    }

    private void speechActivityEndpointsChanged() {
        for (Content content : getContents()) {
            if (MediaType.VIDEO.equals(content.getMediaType())) {
                List<AbstractEndpoint> endpoints = Collections.unmodifiableList(this.speechActivity
                        .getEndpoints());
                content.getChannels().stream()
                        .filter(c -> c instanceof RtpChannel)
                        .forEach(c -> ((RtpChannel)c).speechActivityEndpointsChanged(endpoints));
            }
        }
    }

    private void speechActivityPropertyChange(PropertyChangeEvent ev) {
        String propertyName = ev.getPropertyName();
        if (ConferenceSpeechActivity.DOMINANT_ENDPOINT_PROPERTY_NAME.equals(propertyName)) {
            dominantSpeakerChanged();
            speechActivityEndpointsChanged();
        } else if (ConferenceSpeechActivity.ENDPOINTS_PROPERTY_NAME.equals(propertyName)) {
            speechActivityEndpointsChanged();
        }
    }

    public void touch() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (getLastActivityTime() < now)
                this.lastActivityTime = now;
        }
    }

    void updateEndpoint(ColibriConferenceIQ.Endpoint colibriEndpoint) {
        String id = colibriEndpoint.getId();
        if (id != null) {
            AbstractEndpoint endpoint = getEndpoint(id);
            if (endpoint != null) {
                String oldDisplayName = endpoint.getDisplayName();
                String newDisplayName = colibriEndpoint.getDisplayName();
                if ((oldDisplayName == null && newDisplayName != null) || (oldDisplayName != null &&

                        !oldDisplayName.equals(newDisplayName))) {
                    endpoint.setDisplayName(newDisplayName);
                    if (isRecording() && this.endpointRecorder != null)
                        this.endpointRecorder.updateEndpoint(endpoint);
                    EventAdmin eventAdmin = getEventAdmin();
                    if (eventAdmin != null)
                        eventAdmin.sendEvent(
                                EventFactory.endpointDisplayNameChanged(endpoint));
                }
                endpoint.setStatsId(colibriEndpoint.getStatsId());
            }
        }
    }

    public Localpart getName() {
        return this.name;
    }

    public EventAdmin getEventAdmin() {
        return this.eventAdmin;
    }

    public Logger getLogger() {
        return this.logger;
    }

    public String getLoggingId() {
        return this.loggingId;
    }

    public String getGid() {
        return this.gid;
    }

    public boolean shouldExpire() {
        return ((
                getContents()).length == 0 &&
                getLastActivityTime() + 60000L <
                        System.currentTimeMillis());
    }

    public void safeExpire() {
        this.expireableImpl.safeExpire();
    }

    public class Statistics {
        AtomicInteger totalNoTransportChannels = new AtomicInteger(0);

        AtomicInteger totalNoPayloadChannels = new AtomicInteger(0);

        AtomicInteger totalChannels = new AtomicInteger(0);

        AtomicInteger totalUdpTransportManagers = new AtomicInteger();

        AtomicInteger totalTcpTransportManagers = new AtomicInteger();

        AtomicLong totalBytesReceived = new AtomicLong();

        AtomicLong totalBytesSent = new AtomicLong();

        AtomicLong totalPacketsReceived = new AtomicLong();

        AtomicLong totalPacketsSent = new AtomicLong();

        public AtomicLong totalBytesReceivedOcto = new AtomicLong();

        public AtomicLong totalBytesSentOcto = new AtomicLong();

        public AtomicLong totalPacketsReceivedOcto = new AtomicLong();

        public AtomicLong totalPacketsSentOcto = new AtomicLong();
    }
}