package org.jitsi.videobridge.eventadmin.callstats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jitsi.eventadmin.Event;
import org.jitsi.eventadmin.EventHandler;
import org.jitsi.stats.media.StatsService;
import org.jitsi.utils.concurrent.RecurringRunnable;
import org.jitsi.utils.concurrent.RecurringRunnableExecutor;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.Conference;

class CallStatsConferenceStatsHandler implements EventHandler {
    private static final Logger logger = Logger.getLogger(CallStatsConferenceStatsHandler.class);

    private static final RecurringRunnableExecutor statisticsExecutor = new RecurringRunnableExecutor(CallStatsConferenceStatsHandler.class

            .getSimpleName() + "-statisticsExecutor");

    private StatsService statsService;

    private String bridgeId;

    private String conferenceIDPrefix;

    private final Map<Conference, ConferencePeriodicRunnable> statisticsProcessors = new ConcurrentHashMap<>();

    private int interval;

    void start(StatsService statsService, String bridgeId, String conferenceIDPrefix, int interval) {
        this.statsService = statsService;
        this.bridgeId = bridgeId;
        this.interval = interval;
        this.conferenceIDPrefix = conferenceIDPrefix;
    }

    void stop() {
        for (ConferencePeriodicRunnable cpr : this.statisticsProcessors.values())
            statisticsExecutor.deRegisterRecurringRunnable((RecurringRunnable)cpr);
    }

    public void handleEvent(Event event) {
        if (event == null) {
            logger.debug("Could not handle an event because it was null.");
            return;
        }
        String topic = event.getTopic();
        if ("org/jitsi/videobridge/Conference/CREATED".equals(topic)) {
            conferenceCreated((Conference)event
                    .getProperty("event.source"));
        } else if ("org/jitsi/videobridge/Conference/EXPIRED".equals(topic)) {
            conferenceExpired((Conference)event
                    .getProperty("event.source"));
        }
    }

    private void conferenceCreated(Conference conference) {
        if (conference == null) {
            logger.debug("Could not log conference created event because the conference is null.");
            return;
        }
        ConferencePeriodicRunnable cpr = new ConferencePeriodicRunnable(conference, this.interval, this.statsService, this.conferenceIDPrefix, this.bridgeId);
        cpr.start();
        this.statisticsProcessors.put(conference, cpr);
        statisticsExecutor.registerRecurringRunnable((RecurringRunnable)cpr);
    }

    private void conferenceExpired(Conference conference) {
        if (conference == null) {
            logger.debug("Could not log conference expired event because the conference is null.");
            return;
        }
        ConferencePeriodicRunnable cpr = this.statisticsProcessors.remove(conference);
        if (cpr == null)
            return;
        cpr.stop();
        statisticsExecutor.deRegisterRecurringRunnable((RecurringRunnable)cpr);
    }
}
