package org.jitsi.videobridge;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jitsi.eventadmin.EventAdmin;
import org.jitsi.impl.neomedia.device.AudioSilenceMediaDevice;
import org.jitsi.impl.neomedia.rtp.translator.RTPTranslatorImpl;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.RTPTranslator;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.service.neomedia.recording.Recorder;
import org.jitsi.service.neomedia.recording.Synchronizer;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.event.PropertyChangeNotifier;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.octo.OctoChannel;
import org.jitsi.videobridge.util.Expireable;
import org.jitsi.videobridge.util.ExpireableImpl;
import org.jitsi.xmpp.extensions.colibri.RTPLevelRelayType;
import org.osgi.framework.BundleContext;

public class Content extends PropertyChangeNotifier implements RTPTranslator.WriteFilter, Expireable {
    private static final Logger classLogger = Logger.getLogger(Content.class);

    public static final String CHANNEL_MODIFIED_PROPERTY_NAME = "org.jitsi.videobridge.VideoChannel.mod";

    static String getLoggingId(Content content) {
        if (content == null)
            return Conference.getLoggingId(null) + ",content=null";
        return content.getLoggingId();
    }

    private final Map<String, Channel> channels = new HashMap<>();

    private final Conference conference;

    private boolean expired = false;

    private long initialLocalSSRC = -1L;

    private long lastActivityTime;

    private final MediaType mediaType;

    private MediaDevice mixer;

    private final String name;

    private final String loggingId;

    private Recorder recorder = null;

    @Deprecated
    private boolean recording = false;

    @Deprecated
    private String recordingPath = null;

    private final Object rtpLevelRelaySyncRoot = new Object();

    private RTPTranslator rtpTranslator;

    private final Logger logger;

    private final ExpireableImpl expireableImpl;

    public Content(Conference conference, String name) {
        this.conference = Objects.<Conference>requireNonNull(conference, "conference");
        this.name = Objects.<String>requireNonNull(name, "name");
        this.loggingId = conference.getLoggingId() + ",content=" + name;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
        this.mediaType = MediaType.parseString(this.name);
        this.expireableImpl = new ExpireableImpl(getLoggingId(), this::expire);
        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.contentCreated(this));
        touch();
    }

    public boolean accept(MediaStream source, RawPacket pkt, MediaStream destination, boolean data) {
        boolean accept = true;
        if (destination != null) {
            RtpChannel dst = RtpChannel.getChannel(destination);
            if (dst != null) {
                RtpChannel src = (source == null) ? null : RtpChannel.getChannel(source);
                accept = dst.rtpTranslatorWillWrite(data, pkt, src);
            }
        }
        return accept;
    }

    public RtpChannel createRtpChannel(String channelBundleId, String transportNamespace, Boolean initiator, RTPLevelRelayType rtpLevelRelayType) throws Exception {
        return createRtpChannel(channelBundleId, transportNamespace, initiator, rtpLevelRelayType, false);
    }

    public RtpChannel createRtpChannel(String channelBundleId, String transportNamespace, Boolean initiator, RTPLevelRelayType rtpLevelRelayType, boolean octo) throws IOException {
        RtpChannel channel = null;
        synchronized (this.channels) {
            String id = generateUniqueChannelID();
            if (octo) {
                OctoChannel octoChannel = new OctoChannel(this, id);
            } else {
                switch (getMediaType()) {
                    case AUDIO:
                        channel = new AudioChannel(this, id, channelBundleId, transportNamespace, initiator);
                        break;
                    case DATA:
                        throw new IllegalStateException("mediaType");
                    case VIDEO:
                        channel = new VideoChannel(this, id, channelBundleId, transportNamespace, initiator);
                        break;
                    default:
                        channel = new RtpChannel(this, id, channelBundleId, transportNamespace, initiator);
                        break;
                }
            }
            this.channels.put(id, channel);
        }
        channel.initialize(rtpLevelRelayType);
        if (this.logger.isInfoEnabled()) {
            String transport = "unknown";
            if (octo) {
                transport = "octo";
            } else if (transportNamespace == null) {
                transport = "default";
            } else if ("urn:xmpp:jingle:transports:ice-udp:1"
                    .equals(transportNamespace)) {
                transport = "ice";
            } else if ("urn:xmpp:jingle:transports:raw-udp:1"
                    .equals(transportNamespace)) {
                transport = "rawudp";
            }
            this.logger.info(Logger.Category.STATISTICS, "create_channel," + channel
                    .getLoggingId() + " transport=" + transport + ",bundle=" + channelBundleId + ",initiator=" + initiator + ",media_type=" +

                    getMediaType() + ",relay_type=" + rtpLevelRelayType);
        }
        return channel;
    }

    public SctpConnection createSctpConnection(AbstractEndpoint endpoint, int sctpPort, String channelBundleId, Boolean initiator) throws IOException {
        SctpConnection sctpConnection;
        synchronized (this.channels) {
            String id = generateChannelID();
            sctpConnection = new SctpConnection(id, this, endpoint, sctpPort, channelBundleId, initiator);
            this.channels.put(sctpConnection.getID(), sctpConnection);
        }
        sctpConnection.initialize();
        return sctpConnection;
    }

    public boolean isExpired() {
        return this.expired;
    }

    public void expire() {
        synchronized (this) {
            if (this.expired)
                return;
            this.expired = true;
        }
        setRecording(false, null);
        Conference conference = getConference();
        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.contentExpired(this));
        try {
            conference.expireContent(this);
        } finally {
            for (Channel channel : getChannels()) {
                try {
                    channel.expire();
                } catch (Throwable t) {
                    this.logger.warn("Failed to expire channel " + channel
                            .getLoggingId(), t);
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath)t;
                }
            }
            synchronized (this.rtpLevelRelaySyncRoot) {
                if (this.rtpTranslator != null)
                    this.rtpTranslator.dispose();
            }
            if (this.logger.isInfoEnabled())
                this.logger.info("expire_content," + getLoggingId());
        }
    }

    public void expireChannel(Channel channel) {
        boolean expireChannel;
        String id = channel.getID();
        synchronized (this.channels) {
            if (channel.equals(this.channels.get(id))) {
                this.channels.remove(id);
                expireChannel = true;
            } else {
                expireChannel = false;
            }
        }
        if (expireChannel)
            channel.expire();
    }

    @Deprecated
    void feedKnownSsrcsToSynchronizer() {
        Recorder recorder;
        if (isRecording() && (recorder = getRecorder()) != null) {
            Synchronizer synchronizer = recorder.getSynchronizer();
            for (Channel channel : getChannels()) {
                if (!(channel instanceof RtpChannel))
                    continue;
                AbstractEndpoint endpoint = channel.getEndpoint();
                if (endpoint == null)
                    continue;
                for (int s : ((RtpChannel)channel).getReceiveSSRCs()) {
                    long ssrc = s & 0xFFFFFFFFL;
                    synchronizer.setEndpoint(ssrc, endpoint.getID());
                }
            }
        }
    }

    Channel findChannelByReceiveSSRC(long receiveSSRC) {
        for (Channel channel : getChannels()) {
            if (!(channel instanceof RtpChannel))
                continue;
            RtpChannel rtpChannel = (RtpChannel)channel;
            for (int channelReceiveSSRC : rtpChannel.getReceiveSSRCs()) {
                if (receiveSSRC == (0xFFFFFFFFL & channelReceiveSSRC))
                    return channel;
            }
        }
        return null;
    }

    private String generateChannelID() {
        return
                Long.toHexString(
                        System.currentTimeMillis() + Videobridge.RANDOM.nextLong());
    }

    private String generateUniqueChannelID() {
        synchronized (this.channels) {
            String id;
            do {
                id = generateChannelID();
            } while (this.channels.containsKey(id));
            return id;
        }
    }

    public BundleContext getBundleContext() {
        return getConference().getBundleContext();
    }

    public Channel getChannel(String id) {
        Channel channel;
        synchronized (this.channels) {
            channel = this.channels.get(id);
        }
        if (channel != null)
            channel.touch();
        return channel;
    }

    public int getChannelCount() {
        return

                (int)getChannels().stream().filter(c -> (c != null && !c.isExpired())).count();
    }

    public List<Channel> getChannels() {
        synchronized (this.channels) {
            return new LinkedList<>(this.channels.values());
        }
    }

    public final Conference getConference() {
        return this.conference;
    }

    public long getInitialLocalSSRC() {
        return this.initialLocalSSRC;
    }

    public long getLastActivityTime() {
        synchronized (this) {
            return this.lastActivityTime;
        }
    }

    MediaService getMediaService() {
        return getConference().getMediaService();
    }

    public MediaType getMediaType() {
        return this.mediaType;
    }

    public MediaDevice getMixer() {
        if (this.mixer == null) {
            MediaType mediaType = getMediaType();
            AudioSilenceMediaDevice audioSilenceMediaDevice = MediaType.AUDIO.equals(mediaType) ? new AudioSilenceMediaDevice() : null;
            if (audioSilenceMediaDevice == null)
                throw new UnsupportedOperationException("The mixer type of RTP-level relay is not supported for " + mediaType);
            this.mixer = getMediaService().createMixer((MediaDevice)audioSilenceMediaDevice);
        }
        return this.mixer;
    }

    public final String getName() {
        return this.name;
    }

    @Deprecated
    public Recorder getRecorder() {
        if (this.recorder == null) {
            MediaType mediaType = getMediaType();
            if (MediaType.AUDIO.equals(mediaType) || MediaType.VIDEO
                    .equals(mediaType)) {
                this.recorder = getMediaService().createRecorder(getRTPTranslator());
                this.recorder.setEventHandler(
                        getConference().getRecorderEventHandler());
            }
        }
        return this.recorder;
    }

    public RTPTranslator getRTPTranslator() {
        synchronized (this.rtpLevelRelaySyncRoot) {
            if (this.rtpTranslator == null && !this.expired) {
                this.rtpTranslator = getMediaService().createRTPTranslator();
                if (this.rtpTranslator != null) {
                    new RTPTranslatorWriteFilter(this.rtpTranslator, this);
                    if (this.rtpTranslator instanceof RTPTranslatorImpl) {
                        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl)this.rtpTranslator;
                        this
                                .initialLocalSSRC = Videobridge.RANDOM.nextLong() & 0xFFFFFFFFL;
                        rtpTranslatorImpl.setLocalSSRC(this.initialLocalSSRC);
                    }
                }
            }
            return this.rtpTranslator;
        }
    }

    public SctpConnection getSctpConnection(String id) {
        return (SctpConnection)getChannel(id);
    }

    @Deprecated
    public boolean isRecording() {
        return this.recording;
    }

    @Deprecated
    public boolean setRecording(boolean recording, String path) {
        this.recordingPath = path;
        if (this.recording != recording) {
            Recorder recorder = getRecorder();
            if (recording) {
                if (recorder != null) {
                    recording = startRecorder(recorder);
                } else {
                    recording = false;
                }
            } else {
                if (recorder != null) {
                    recorder.stop();
                    this.recorder = null;
                }
                recording = false;
            }
        }
        this.recording = recording;
        return this.recording;
    }

    @Deprecated
    private boolean startRecorder(Recorder recorder) {
        boolean started = false;
        String format = MediaType.AUDIO.equals(getMediaType()) ? "mp3" : null;
        try {
            recorder.start(format, this.recordingPath);
            started = true;
        } catch (IOException|org.jitsi.service.neomedia.MediaException ioe) {
            this.logger.error("Failed to start recorder: " + ioe);
            started = false;
        }
        return started;
    }

    public void touch() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (getLastActivityTime() < now)
                this.lastActivityTime = now;
        }
    }

    public void fireChannelChanged(RtpChannel channel) {
        firePropertyChange("org.jitsi.videobridge.VideoChannel.mod", channel, channel);
    }

    String getLoggingId() {
        return this.loggingId;
    }

    public boolean shouldExpire() {
        return (
                getChannels().isEmpty() &&
                        getLastActivityTime() + 60000L <
                                System.currentTimeMillis());
    }

    public void safeExpire() {
        this.expireableImpl.safeExpire();
    }

    private static class RTPTranslatorWriteFilter implements RTPTranslator.WriteFilter {
        private final WeakReference<RTPTranslator> rtpTranslator;

        private final WeakReference<RTPTranslator.WriteFilter> writeFilter;

        public RTPTranslatorWriteFilter(RTPTranslator rtpTranslator, RTPTranslator.WriteFilter writeFilter) {
            this.rtpTranslator = new WeakReference<>(rtpTranslator);
            this.writeFilter = new WeakReference<>(writeFilter);
            rtpTranslator.addWriteFilter(this);
        }

        public boolean accept(MediaStream source, RawPacket pkt, MediaStream destination, boolean data) {
            RTPTranslator.WriteFilter writeFilter = this.writeFilter.get();
            boolean accept = true;
            if (writeFilter == null) {
                RTPTranslator rtpTranslator = this.rtpTranslator.get();
                if (rtpTranslator != null)
                    rtpTranslator.removeWriteFilter(this);
            } else {
                accept = writeFilter.accept(source, pkt, destination, data);
            }
            return accept;
        }
    }
}
