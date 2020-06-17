package org.jitsi.videobridge.stats;

public abstract class StatsTransport extends BundleContextHolder2 {
    public abstract void publishStatistics(Statistics paramStatistics);

    public void publishStatistics(Statistics statistics, long measurementInterval) {
        publishStatistics(statistics);
    }
}
