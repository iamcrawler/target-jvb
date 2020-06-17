package org.jitsi.videobridge.octo;


import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.utils.ArrayUtils;
import org.jitsi.utils.MediaType;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.Conference;

public class OctoEndpoint extends AbstractEndpoint {
    OctoEndpoint(Conference conference, String id) {
        super(conference, id);
    }

    public void sendMessage(String msg) {}

    protected void maybeExpire() {
        MediaStreamTrackDesc[] audioTracks = getMediaStreamTracks(MediaType.AUDIO);
        MediaStreamTrackDesc[] videoTracks = getMediaStreamTracks(MediaType.VIDEO);
        if (ArrayUtils.isNullOrEmpty((Object[])audioTracks) &&
                ArrayUtils.isNullOrEmpty((Object[])videoTracks))
            expire();
    }

    List<MediaStreamTrackDesc> getMediaStreamTracks() {
        List<MediaStreamTrackDesc> tracks = new LinkedList<MediaStreamTrackDesc>();
        tracks.addAll(Arrays.asList(getMediaStreamTracks(MediaType.AUDIO)));
        tracks.addAll(Arrays.asList(getMediaStreamTracks(MediaType.VIDEO)));
        return tracks;
    }

    public MediaStreamTrackDesc[] getMediaStreamTracks(MediaType mediaType) {
        String id = getID();
        return (MediaStreamTrackDesc[])getAllMediaStreamTracks(mediaType).stream()
                .filter(track -> id.equals(track.getOwner()))
                .toArray(x$0 -> new MediaStreamTrackDesc[x$0]);
    }
}
