package org.jitsi.videobridge;

import org.jitsi.service.neomedia.recording.RecorderEvent;
import org.jitsi.service.neomedia.recording.RecorderEventHandler;
import org.jitsi.utils.MediaType;

class RecorderEventHandlerImpl implements RecorderEventHandler {
    private final Conference conference;

    private final RecorderEventHandler handler;

    RecorderEventHandlerImpl(Conference conference, RecorderEventHandler handler) throws IllegalArgumentException {
        if (conference == null)
            throw new NullPointerException("conference");
        if (handler == null)
            throw new NullPointerException("handler");
        this.conference = conference;
        this.handler = handler;
    }

    public void close() {
        this.handler.close();
    }

    void dominantSpeakerChanged(AbstractEndpoint endpoint) {
        long ssrc = -1L;
        for (Channel c : endpoint.getChannels(MediaType.VIDEO)) {
            int[] ssrcs = ((RtpChannel)c).getReceiveSSRCs();
            if (ssrcs != null && ssrcs.length > 0) {
                ssrc = ssrcs[0] & 0xFFFFFFFFL;
                break;
            }
        }
        if (ssrc != -1L) {
            RecorderEvent event = new RecorderEvent();
            event.setType(RecorderEvent.Type.SPEAKER_CHANGED);
            event.setMediaType(MediaType.VIDEO);
            event.setSsrc(ssrc);
            event.setEndpointId(endpoint.getID());
            event.setInstant(System.currentTimeMillis());
            handleEvent(event);
        }
    }

    public boolean handleEvent(RecorderEvent event) {
        if (event.getEndpointId() == null) {
            long ssrc = event.getSsrc();
            AbstractEndpoint endpoint = this.conference.findEndpointByReceiveSSRC(ssrc, MediaType.AUDIO);
            if (endpoint == null)
                endpoint = this.conference.findEndpointByReceiveSSRC(ssrc, MediaType.VIDEO);
            if (endpoint != null)
                event.setEndpointId(endpoint.getID());
        }
        return this.handler.handleEvent(event);
    }
}
