package org.jitsi.videobridge.stats;

import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.stats.media.BridgeStatistics;
import org.jitsi.stats.media.StatsService;
import org.jitsi.stats.media.StatsServiceFactory;
import org.jitsi.util.ConfigUtils;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;

public class CallStatsIOTransport extends StatsTransport {
    private static final String PNAME_CALLSTATS_IO_APP_ID = "io.callstats.sdk.CallStats.appId";

    private static final String PNAME_CALLSTATS_IO_APP_SECRET = "io.callstats.sdk.CallStats.appSecret";

    private static final String PNAME_CALLSTATS_IO_KEY_ID = "io.callstats.sdk.CallStats.keyId";

    private static final String PNAME_CALLSTATS_IO_KEY_PATH = "io.callstats.sdk.CallStats.keyPath";

    public static final String PNAME_CALLSTATS_IO_BRIDGE_ID = "io.callstats.sdk.CallStats.bridgeId";

    public static final String DEFAULT_BRIDGE_ID = "jitsi";

    public static final String PNAME_CALLSTATS_IO_CONF_PREFIX = "io.callstats.sdk.CallStats.conferenceIDPrefix";

    private BridgeStatistics bridgeStatusInfoBuilder = new BridgeStatistics();

    private StatsService statsService;

    private StatsServiceListener serviceListener;

    protected void bundleContextChanged(BundleContext oldValue, BundleContext newValue) {
        super.bundleContextChanged(oldValue, newValue);
        if (newValue == null) {
            dispose(oldValue);
        } else if (oldValue == null) {
            init(newValue);
        }
    }

    private void dispose(BundleContext bundleContext) {
        if (this.serviceListener != null) {
            bundleContext.removeServiceListener(this.serviceListener);
            this.serviceListener = null;
        }
        if (this.statsService != null) {
            StatsServiceFactory.getInstance()
                    .stopStatsService(bundleContext, this.statsService.getId());
            this.statsService = null;
        }
        this.bridgeStatusInfoBuilder = null;
    }

    private void init(BundleContext bundleContext) {
        ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        init(bundleContext, cfg);
    }

    private void init(BundleContext bundleContext, ConfigurationService cfg) {
        int appId = ConfigUtils.getInt(cfg, "io.callstats.sdk.CallStats.appId", 0);
        String appSecret = ConfigUtils.getString(cfg, "io.callstats.sdk.CallStats.appSecret", null);
        String keyId = ConfigUtils.getString(cfg, "io.callstats.sdk.CallStats.keyId", null);
        String keyPath = ConfigUtils.getString(cfg, "io.callstats.sdk.CallStats.keyPath", null);
        String bridgeId = ConfigUtils.getString(cfg, "io.callstats.sdk.CallStats.bridgeId", "jitsi");
        this.serviceListener = new StatsServiceListener(appId, bundleContext);
        bundleContext.addServiceListener(this.serviceListener);
        StatsServiceFactory.getInstance().createStatsService(bundleContext, appId, appSecret, keyId, keyPath, bridgeId);
    }

    private void populateBridgeStatusInfoBuilderWithStatistics(BridgeStatistics bsib, Statistics s, long measurementInterval) {
        bsib.audioFabricCount(s
                .getStatAsInt("audiochannels"));
        bsib.avgIntervalJitter(s
                .getStatAsInt("jitter_aggregate"));
        bsib.avgIntervalRtt(s
                .getStatAsInt("rtt_aggregate"));
        bsib.conferenceCount(s.getStatAsInt("conferences"));
        bsib.cpuUsage(
                (float)s.getStatAsDouble("cpu_usage"));
        bsib.intervalDownloadBitRate(

                (int)Math.round(s
                        .getStatAsDouble("bit_rate_download")));
        bsib.intervalRtpFractionLoss(
                (float)s.getStatAsDouble("loss_rate_download"));
        bsib.intervalUploadBitRate(

                (int)Math.round(s
                        .getStatAsDouble("bit_rate_upload")));
        bsib.measurementInterval((int)measurementInterval);
        bsib.memoryUsage(s.getStatAsInt("used_memory"));
        bsib.participantsCount(s
                .getStatAsInt("participants"));
        bsib.threadCount(s.getStatAsInt("threads"));
        bsib.totalMemory(s.getStatAsInt("total_memory"));
        bsib.videoFabricCount(s
                .getStatAsInt("videochannels"));
    }

    public void publishStatistics(Statistics statistics) {}

    public void publishStatistics(Statistics statistics, long measurementInterval) {
        if (this.statsService != null) {
            BridgeStatistics bridgeStatusInfoBuilder = this.bridgeStatusInfoBuilder;
            populateBridgeStatusInfoBuilderWithStatistics(bridgeStatusInfoBuilder, statistics, measurementInterval);
            this.statsService.sendBridgeStatusUpdate(bridgeStatusInfoBuilder);
        }
    }

    private class StatsServiceListener implements ServiceListener {
        private final int id;

        private final BundleContext bundleContext;

        StatsServiceListener(int id, BundleContext bundleContext) {
            this.id = id;
            this.bundleContext = bundleContext;
        }

        public void serviceChanged(ServiceEvent serviceEvent) {
            Object service;
            try {
                service = this.bundleContext.getService(serviceEvent
                        .getServiceReference());
            } catch (IllegalArgumentException|IllegalStateException|SecurityException ex) {
                service = null;
            }
            if (service == null || !(service instanceof StatsService))
                return;
            if (((StatsService)service).getId() != this.id)
                return;
            switch (serviceEvent.getType()) {
                case 1:
                    CallStatsIOTransport.this.statsService = (StatsService)service;
                    break;
                case 4:
                    CallStatsIOTransport.this.statsService = null;
                    break;
            }
        }
    }
}
