package org.jitsi.videobridge;


import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackReceiver;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.event.PropertyChangeNotifier;

public abstract class AbstractEndpoint extends PropertyChangeNotifier {
    private final String id;

    private final String loggingId;

    private final Conference conference;

    private final List<WeakReference<RtpChannel>> channels = new LinkedList<>();

    private String displayName;

    private String statsId;

    private boolean expired = false;

    protected AbstractEndpoint(Conference conference, String id) {
        this.conference = Objects.<Conference>requireNonNull(conference, "conference");
        this.id = Objects.<String>requireNonNull(id, "id");
        this.loggingId = conference.getLoggingId() + ",endp_id=" + id;
    }

    public AbstractEndpointMessageTransport getMessageTransport() {
        return null;
    }

    public boolean addChannel(RtpChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (channel.isExpired())
            return false;
        boolean added = false;
        boolean removed = false;
        synchronized (this.channels) {
            boolean add = true;
            Iterator<WeakReference<RtpChannel>> i = this.channels.iterator();
            while (i.hasNext()) {
                RtpChannel c = ((WeakReference<RtpChannel>)i.next()).get();
                if (c == null) {
                    i.remove();
                    removed = true;
                    continue;
                }
                if (c.equals(channel)) {
                    add = false;
                    continue;
                }
                if (c.isExpired()) {
                    i.remove();
                    removed = true;
                }
            }
            if (add) {
                this.channels.add(new WeakReference<>(channel));
                added = true;
            }
        }
        if (removed)
            maybeExpire();
        return added;
    }

    int getChannelCount(MediaType mediaType) {
        return getChannels(mediaType).size();
    }

    public List<RtpChannel> getChannels() {
        return getChannels(null);
    }

    public List<RtpChannel> getChannels(MediaType mediaType) {
        boolean removed = false;
        List<RtpChannel> channels = new LinkedList<>();
        synchronized (this.channels) {
            Iterator<WeakReference<RtpChannel>> i = this.channels.iterator();
            while (i.hasNext()) {
                RtpChannel c = ((WeakReference<RtpChannel>)i.next()).get();
                if (c == null || c.isExpired()) {
                    i.remove();
                    removed = true;
                    continue;
                }
                if (mediaType == null || mediaType
                        .equals(c.getContent().getMediaType()))
                    channels.add(c);
            }
        }
        if (removed)
            maybeExpire();
        return channels;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public String getStatsId() {
        return this.statsId;
    }

    public final String getID() {
        return this.id;
    }

    public Conference getConference() {
        return this.conference;
    }

    public boolean isExpired() {
        return this.expired;
    }

    public boolean removeChannel(RtpChannel channel) {
        boolean removed;
        if (channel == null)
            return false;
        synchronized (this.channels) {
            removed = this.channels.removeIf(w -> {
                Channel c = w.get();
                return (c == null || c.equals(channel) || c.isExpired());
            });
        }
        if (removed)
            maybeExpire();
        return removed;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setStatsId(String value) {
        this.statsId = value;
    }

    public String toString() {
        return getClass().getName() + " " + getID();
    }

    public void expire() {
        this.expired = true;
        getConference().endpointExpired(this);
    }

    public String getLoggingId() {
        return this.loggingId;
    }

    protected void maybeExpire() {}

    public Set<String> getSelectedEndpoints() {
        return Collections.EMPTY_SET;
    }

    public Set<String> getPinnedEndpoints() {
        return Collections.EMPTY_SET;
    }

    public MediaStreamTrackDesc[] getMediaStreamTracks(MediaType mediaType) {
        return
                getAllMediaStreamTracks(mediaType)
                        .<MediaStreamTrackDesc>toArray(new MediaStreamTrackDesc[0]);
    }

    protected List<MediaStreamTrackDesc> getAllMediaStreamTracks(MediaType mediaType) {
        List<RtpChannel> channels = getChannels(mediaType);
        if (channels == null || channels.isEmpty())
            return Collections.EMPTY_LIST;
        List<MediaStreamTrackDesc> allTracks = new LinkedList<>();
        channels.stream()
                .map(channel -> channel.getStream().getMediaStreamTrackReceiver())
                .filter(Objects::nonNull)
                .forEach(trackReceiver -> allTracks.addAll(Arrays.asList(trackReceiver.getMediaStreamTracks())));
        return allTracks;
    }

    public void incrementSelectedCount() {}

    public void decrementSelectedCount() {}

    public abstract void sendMessage(String paramString) throws IOException;
}
