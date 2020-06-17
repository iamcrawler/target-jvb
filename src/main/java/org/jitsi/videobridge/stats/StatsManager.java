package org.jitsi.videobridge.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jitsi.utils.concurrent.PeriodicRunnableWithObject;
import org.jitsi.utils.concurrent.RecurringRunnable;
import org.jitsi.utils.concurrent.RecurringRunnableExecutor;
import org.osgi.framework.BundleContext;

public class StatsManager extends BundleContextHolder2 {
    private final List<StatisticsPeriodicRunnable> statistics = new CopyOnWriteArrayList<>();

    private final RecurringRunnableExecutor statisticsExecutor = new RecurringRunnableExecutor(StatsManager.class

            .getSimpleName() + "-statisticsExecutor");

    private final RecurringRunnableExecutor transportExecutor = new RecurringRunnableExecutor(StatsManager.class

            .getSimpleName() + "-transportExecutor");

    private final List<TransportPeriodicRunnable> transports = new CopyOnWriteArrayList<>();

    void addStatistics(Statistics statistics, long period) {
        if (statistics == null)
            throw new NullPointerException("statistics");
        if (period < 1L)
            throw new IllegalArgumentException("period " + period);
        this.statistics.add(new StatisticsPeriodicRunnable(statistics, period));
    }

    void addTransport(StatsTransport transport, long period) {
        if (transport == null)
            throw new NullPointerException("transport");
        if (period < 1L)
            throw new IllegalArgumentException("period " + period);
        this.transports.add(new TransportPeriodicRunnable(transport, period));
    }

    public <T extends Statistics> T findStatistics(Class<T> clazz, long period) {
        for (StatisticsPeriodicRunnable spp : this.statistics) {
            if (spp.getPeriod() == period && clazz.isInstance(spp.o))
                return (T)spp.o;
        }
        return null;
    }

    public Collection<Statistics> getStatistics() {
        Collection<Statistics> ret;
        int count = this.statistics.size();
        if (count < 1) {
            ret = Collections.emptyList();
        } else {
            ret = new ArrayList<>(count);
            for (StatisticsPeriodicRunnable spp : this.statistics)
                ret.add(spp.o);
        }
        return ret;
    }

    public int getStatisticsCount() {
        return this.statistics.size();
    }

    public Collection<StatsTransport> getTransports() {
        Collection<StatsTransport> ret;
        int count = this.transports.size();
        if (count < 1) {
            ret = Collections.emptyList();
        } else {
            ret = new ArrayList<>(count);
            for (TransportPeriodicRunnable tpp : this.transports)
                ret.add(tpp.o);
        }
        return ret;
    }

    void start(BundleContext bundleContext) throws Exception {
        super.start(bundleContext);
        for (StatisticsPeriodicRunnable spp : this.statistics)
            this.statisticsExecutor.registerRecurringRunnable((RecurringRunnable)spp);
        for (TransportPeriodicRunnable tpp : this.transports) {
            ((StatsTransport)tpp.o).start(bundleContext);
            this.transportExecutor.registerRecurringRunnable((RecurringRunnable)tpp);
        }
    }

    void stop(BundleContext bundleContext) throws Exception {
        super.stop(bundleContext);
        for (StatisticsPeriodicRunnable spp : this.statistics)
            this.statisticsExecutor.deRegisterRecurringRunnable((RecurringRunnable)spp);
        for (TransportPeriodicRunnable tpp : this.transports) {
            this.transportExecutor.deRegisterRecurringRunnable((RecurringRunnable)tpp);
            ((StatsTransport)tpp.o).stop(bundleContext);
        }
    }

    private static class StatisticsPeriodicRunnable extends PeriodicRunnableWithObject<Statistics> {
        public StatisticsPeriodicRunnable(Statistics statistics, long period) {
            super(statistics, period);
        }

        protected void doRun() {
            ((Statistics)this.o).generate();
        }
    }

    private class TransportPeriodicRunnable extends PeriodicRunnableWithObject<StatsTransport> {
        public TransportPeriodicRunnable(StatsTransport transport, long period) {
            super(transport, period);
        }

        protected void doRun() {
            long transportPeriod = getPeriod();
            for (StatsManager.StatisticsPeriodicRunnable spp : StatsManager.this.statistics) {
                long statisticsPeriod = spp.getPeriod();
                if (transportPeriod == statisticsPeriod) {
                    long measurementInterval = statisticsPeriod;
                    ((StatsTransport)this.o).publishStatistics((Statistics)spp.o, measurementInterval);
                }
            }
        }
    }
}
