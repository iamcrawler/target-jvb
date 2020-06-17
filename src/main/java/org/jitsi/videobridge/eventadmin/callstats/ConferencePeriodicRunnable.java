package org.jitsi.videobridge.eventadmin.callstats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.stats.MediaStreamStats2;
import org.jitsi.service.neomedia.stats.ReceiveTrackStats;
import org.jitsi.service.neomedia.stats.SendTrackStats;
import org.jitsi.stats.media.AbstractStatsPeriodicRunnable;
import org.jitsi.stats.media.StatsService;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.RtpChannel;

public class ConferencePeriodicRunnable extends AbstractStatsPeriodicRunnable<Conference> {
    private static final Logger logger = Logger.getLogger(ConferencePeriodicRunnable.class);

    private static final MediaType[] MEDIA_TYPES = new MediaType[] { MediaType.AUDIO, MediaType.VIDEO };

    ConferencePeriodicRunnable(Conference conference, long period, StatsService statsService, String conferenceIDPrefix, String initiatorID) {
        super(conference, period, statsService,

                (conference.getName() == null) ? "null" : conference
                        .getName().toString(), conferenceIDPrefix, initiatorID);
    }

    protected Map<String, Collection<? extends ReceiveTrackStats>> getReceiveTrackStats() {
        return getTrackStats(true);
    }

    protected Map<String, Collection<? extends SendTrackStats>> getSendTrackStats() {
        return getTrackStats(false);
    }

    private <T extends Collection> Map<String, T> getTrackStats(boolean receive) {
        Map<String, T> resultStats = new HashMap<>();
        for (AbstractEndpoint endpoint : ((Conference)this.o).getEndpoints()) {
            for (MediaType mediaType : MEDIA_TYPES) {
                for (RtpChannel channel : endpoint.getChannels(mediaType)) {
                    if (channel == null) {
                        logger.debug("Could not log the channel expired event because the channel is null.");
                        continue;
                    }
                    if ((channel.getReceiveSSRCs()).length == 0)
                        continue;
                    MediaStream stream = channel.getStream();
                    if (stream == null)
                        continue;
                    MediaStreamStats2 stats = stream.getMediaStreamStats();
                    if (stats == null)
                        continue;
                    String endpointID = (endpoint.getStatsId() != null) ? endpoint.getStatsId() : endpoint.getID();
                    Collection<?> newStats = receive ? stats.getAllReceiveStats() : stats.getAllSendStats();
                    Collection collection = (Collection)resultStats.get(endpointID);
                    if (collection != null) {
                        collection.addAll(newStats);
                        continue;
                    }
                    resultStats.put(endpointID, (T)new ArrayList(newStats));
                }
            }
        }
        return resultStats;
    }
}
