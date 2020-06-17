package org.jitsi.videobridge.stats;

import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.util.ConfigUtils;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class StatsManagerBundleActivator implements BundleActivator {
    private static BundleContext bundleContext;

    public static final int DEFAULT_STAT_INTERVAL = 1000;

    private static final String DEFAULT_STAT_TRANSPORT = null;

    private static final String ENABLE_STATISTICS_PNAME = "org.jitsi.videobridge.ENABLE_STATISTICS";

    private static final Logger logger = Logger.getLogger(StatsManagerBundleActivator.class);

    private static final String PUBSUB_NODE_PNAME = "org.jitsi.videobridge.PUBSUB_NODE";

    private static final String PUBSUB_SERVICE_PNAME = "org.jitsi.videobridge.PUBSUB_SERVICE";

    public static final String STAT_TRANSPORT_CALLSTATS_IO = "callstats.io";

    private static final String STAT_TRANSPORT_COLIBRI = "colibri";

    private static final String STAT_TRANSPORT_PUBSUB = "pubsub";

    private static final String STAT_TRANSPORT_MUC = "muc";

    public static final String STATISTICS_INTERVAL_PNAME = "org.jitsi.videobridge.STATISTICS_INTERVAL";

    private static final String STATISTICS_TRANSPORT_PNAME = "org.jitsi.videobridge.STATISTICS_TRANSPORT";

    private ServiceRegistration<StatsManager> serviceRegistration;

    public static BundleContext getBundleContext() {
        return bundleContext;
    }

    private void addTransport(StatsManager statsMgr, ConfigurationService cfg, int interval, String transport) {
        StatsTransport t = null;
        if ("callstats.io".equalsIgnoreCase(transport)) {
            t = new CallStatsIOTransport();
        } else if ("colibri".equalsIgnoreCase(transport)) {
            t = new ColibriStatsTransport();
        } else if ("pubsub".equalsIgnoreCase(transport)) {
            Jid service;
            try {
                service = JidCreate.from(cfg.getString("org.jitsi.videobridge.PUBSUB_SERVICE"));
            } catch (XmppStringprepException e) {
                logger.error("Invalid pubsub service name", (Throwable)e);
                return;
            }
            String node = cfg.getString("org.jitsi.videobridge.PUBSUB_NODE");
            if (service != null && node != null) {
                t = new PubSubStatsTransport(service, node);
            } else {
                logger.error("No configuration properties for PubSub service and/or node found.");
            }
        } else if ("muc".equalsIgnoreCase(transport)) {
            logger.info("Using a MUC stats transport");
            t = new MucStatsTransport();
        } else {
            logger.error("Unknown/unsupported statistics transport: " + transport);
        }
        if (t != null) {
            interval = ConfigUtils.getInt(cfg, "org.jitsi.videobridge.STATISTICS_INTERVAL." + transport, interval);
            if (statsMgr.findStatistics(VideobridgeStatistics.class, interval) == null)
                statsMgr.addStatistics(new VideobridgeStatistics(), interval);
            statsMgr.addTransport(t, interval);
        }
    }

    private void addTransports(StatsManager statsMgr, ConfigurationService cfg, int interval) {
        String transports = ConfigUtils.getString(cfg, "org.jitsi.videobridge.STATISTICS_TRANSPORT", DEFAULT_STAT_TRANSPORT);
        if (transports == null || transports.length() == 0)
            return;
        for (String transport : transports.split(","))
            addTransport(statsMgr, cfg, interval, transport);
    }

    public void start(BundleContext bundleContext) throws Exception {
        ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        boolean enable = false;
        if (cfg != null)
            enable = cfg.getBoolean("org.jitsi.videobridge.ENABLE_STATISTICS", enable);
        if (enable) {
            StatsManagerBundleActivator.bundleContext = bundleContext;
            boolean started = false;
            try {
                start(cfg);
                started = true;
            } finally {
                if (!started && StatsManagerBundleActivator.bundleContext == bundleContext)
                    StatsManagerBundleActivator.bundleContext = null;
            }
        }
    }

    private void start(ConfigurationService cfg) throws Exception {
        StatsManager statsMgr = new StatsManager();
        int interval = ConfigUtils.getInt(cfg, "org.jitsi.videobridge.STATISTICS_INTERVAL", 1000);
        statsMgr.addStatistics(new VideobridgeStatistics(), interval);
        addTransports(statsMgr, cfg, interval);
        statsMgr.start(bundleContext);
        ServiceRegistration<StatsManager> serviceRegistration = null;
        try {
            serviceRegistration = bundleContext.registerService(StatsManager.class, statsMgr, null);
        } finally {
            if (serviceRegistration != null)
                this.serviceRegistration = serviceRegistration;
        }
    }

    public void stop(BundleContext bundleContext) throws Exception {
        ServiceRegistration<StatsManager> serviceRegistration = this.serviceRegistration;
        this.serviceRegistration = null;
        StatsManager statsMgr = null;
        if (serviceRegistration == null)
            return;
        try {
            statsMgr = (StatsManager)bundleContext.getService(serviceRegistration.getReference());
        } finally {
            serviceRegistration.unregister();
            if (statsMgr != null)
                statsMgr.stop(bundleContext);
        }
    }
}
