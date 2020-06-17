package org.jitsi.videobridge;

import java.util.Objects;
import java.util.concurrent.Executor;
import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.utils.concurrent.ExecutorUtils;
import org.jitsi.utils.concurrent.PeriodicRunnable;
import org.jitsi.utils.concurrent.RecurringRunnable;
import org.jitsi.utils.concurrent.RecurringRunnableExecutor;
import org.jitsi.utils.logging.Logger;
import org.osgi.framework.BundleContext;

class VideobridgeExpireThread {
    private static final Logger logger = Logger.getLogger(VideobridgeExpireThread.class);

    private static final RecurringRunnableExecutor EXECUTOR = new RecurringRunnableExecutor(VideobridgeExpireThread.class

            .getSimpleName());

    private static final Executor EXPIRE_EXECUTOR = ExecutorUtils.newCachedThreadPool(true, VideobridgeExpireThread.class
            .getSimpleName() + "-channel");

    public static final String EXPIRE_CHECK_SLEEP_SEC = "org.jitsi.videobridge.EXPIRE_CHECK_SLEEP_SEC";

    private static final int EXPIRE_CHECK_SLEEP_SEC_DEFAULT = 60;

    private PeriodicRunnable expireRunnable;

    private Videobridge videobridge;

    public VideobridgeExpireThread(Videobridge videobridge) {
        this.videobridge = Objects.<Videobridge>requireNonNull(videobridge);
    }

    void start(BundleContext bundleContext) {
        ConfigurationService cfg = (ConfigurationService)ServiceUtils2.getService(bundleContext, ConfigurationService.class);
        int expireCheckSleepSec = (cfg == null) ? 60 : cfg.getInt("org.jitsi.videobridge.EXPIRE_CHECK_SLEEP_SEC", 60);
        logger.info("Starting with " + expireCheckSleepSec + " second interval.");
        this.expireRunnable = new PeriodicRunnable((expireCheckSleepSec * 1000)) {
            public void run() {
                super.run();
                Videobridge videobridge = VideobridgeExpireThread.this.videobridge;
                if (videobridge != null)
                    VideobridgeExpireThread.this.expire(videobridge);
            }
        };
        EXECUTOR.registerRecurringRunnable((RecurringRunnable)this.expireRunnable);
    }

    void stop(BundleContext bundleContext) {
        logger.info("Stopping.");
        if (this.expireRunnable != null)
            EXECUTOR.deRegisterRecurringRunnable((RecurringRunnable)this.expireRunnable);
        this.expireRunnable = null;
        this.videobridge = null;
    }

    private void expire(Videobridge videobridge) {
        logger.info("Running expire()");
        for (Conference conference : videobridge.getConferences()) {
            if (conference.shouldExpire()) {
                EXPIRE_EXECUTOR.execute(conference::safeExpire);
            } else {
                for (Content content : conference.getContents()) {
                    if (content.shouldExpire()) {
                        EXPIRE_EXECUTOR.execute(content::safeExpire);
                    } else {
                        for (Channel channel : content.getChannels()) {
                            if (channel.shouldExpire())
                                EXPIRE_EXECUTOR.execute(channel::safeExpire);
                        }
                    }
                }
            }
        }
    }
}
