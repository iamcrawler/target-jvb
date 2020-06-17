package org.jitsi.videobridge.util;


import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Objects;
import org.jitsi.utils.logging.Logger;

public class ExpireableImpl implements Expireable {
    private static String getStackTraceAsString(Thread thread) {
        if (thread == null)
            return "null";
        return Arrays.<StackTraceElement>stream(thread.getStackTrace())
                .map(StackTraceElement::toString)
                .reduce((s, str) -> s.concat(" -> ").concat(str))
                .orElse("empty");
    }

    private static final Logger logger = Logger.getLogger(ExpireableImpl.class);

    private WeakReference<Thread> expireThread = null;

    private long expireStarted = -1L;

    private final Object syncRoot = new Object();

    private final String name;

    private final Runnable expireRunnable;

    public ExpireableImpl(String name, Runnable expireRunnable) {
        this.name = name;
        this.expireRunnable = Objects.<Runnable>requireNonNull(expireRunnable);
    }

    public boolean shouldExpire() {
        return false;
    }

    public void safeExpire() {
        synchronized (this.syncRoot) {
            if (logger.isDebugEnabled())
                logger.debug("Expiring " + this.name);
            long now = System.currentTimeMillis();
            if (this.expireStarted > 0L) {
                long duration = now - this.expireStarted;
                if (duration > 1000L || logger.isDebugEnabled()) {
                    Thread expireThread = (this.expireThread == null) ? null : this.expireThread.get();
                    logger.warn("A thread has been running safeExpire() on " + this.name + "for " + duration + "ms: " +

                            getStackTraceAsString(expireThread));
                }
                return;
            }
            this.expireStarted = now;
            this.expireThread = new WeakReference<>(Thread.currentThread());
        }
        try {
            this.expireRunnable.run();
        } catch (Throwable t) {
            logger.error("Failed to expire " + this.name, t);
        } finally {
            if (logger.isDebugEnabled())
                logger.debug("Expired " + this.name);
            synchronized (this.syncRoot) {
                this.expireStarted = -1L;
                this.expireThread = null;
            }
        }
    }
}
