package org.jitsi.videobridge;

import java.util.List;
import org.jitsi.service.neomedia.AudioMediaStream;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.RTPExtension;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.utils.MediaType;
import org.jitsi.xmpp.extensions.colibri.RTPLevelRelayType;

public class AudioChannel extends RtpChannel {
    public AudioChannel(Content content, String id, String channelBundleId, String transportNamespace, Boolean initiator) {
        super(content, id, channelBundleId, transportNamespace, initiator);
    }

    protected void removeStreamListeners() {
        super.removeStreamListeners();
        try {
            MediaStream stream = getStream();
            if (stream instanceof AudioMediaStream)
                ((AudioMediaStream)stream).setCsrcAudioLevelListener(null);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            } else if (t instanceof ThreadDeath) {
                throw (ThreadDeath)t;
            }
        }
    }

    protected void rtpLevelRelayTypeChanged(RTPLevelRelayType oldValue, RTPLevelRelayType newValue) {
        super.rtpLevelRelayTypeChanged(oldValue, newValue);
        if (RTPLevelRelayType.MIXER.equals(newValue)) {
            Content content = getContent();
            if (MediaType.AUDIO.equals(content.getMediaType())) {
                MediaStream stream = getStream();
                MediaDevice device = content.getMixer();
                List<RTPExtension> rtpExtensions = device.getSupportedExtensions();
                if (rtpExtensions.size() == 1)
                    stream.addRTPExtension((byte)1, rtpExtensions.get(0));
            }
        }
    }

    protected void configureStream(MediaStream stream) {
        if (stream instanceof AudioMediaStream)
            ((AudioMediaStream)stream)
                    .setCsrcAudioLevelListener(new AudioChannelAudioLevelListener(this));
    }
}
