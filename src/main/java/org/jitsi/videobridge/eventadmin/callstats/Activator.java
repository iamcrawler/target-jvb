package org.jitsi.videobridge.eventadmin.callstats;

import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.eventadmin.EventHandler;
import org.jitsi.eventadmin.EventUtil;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.stats.media.StatsService;
import org.jitsi.util.ConfigUtils;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator, ServiceListener {
    private BundleContext bundleContext;

    private CallStatsConferenceStatsHandler conferenceStatsHandler;

    private ServiceRegistration<EventHandler> serviceRegistration;

    public void start(BundleContext bundleContext) throws Exception {
        this.bundleContext = bundleContext;
        bundleContext.addServiceListener(this);
    }

    public void stop(BundleContext bundleContext) throws Exception {
        bundleContext.removeServiceListener(this);
        if (this.serviceRegistration != null) {
            this.serviceRegistration.unregister();
            this.serviceRegistration = null;
        }
        if (this.conferenceStatsHandler != null) {
            this.conferenceStatsHandler.stop();
            this.conferenceStatsHandler = null;
        }
    }

    public void serviceChanged(ServiceEvent ev) {
        Object service;
        ConfigurationService cfg;
        String bridgeId;
        int interval;
        String conferenceIDPrefix;
        String[] topics;
        if (this.bundleContext == null)
            return;
        try {
            service = this.bundleContext.getService(ev.getServiceReference());
        } catch (IllegalArgumentException|IllegalStateException|SecurityException ex) {
            service = null;
        }
        if (service == null || !(service instanceof StatsService))
            return;
        switch (ev.getType()) {
            case 1:
                cfg = (ConfigurationService)ServiceUtils.getService(this.bundleContext, ConfigurationService.class);
                bridgeId = ConfigUtils.getString(cfg, "io.callstats.sdk.CallStats.bridgeId", "jitsi");
                interval = ConfigUtils.getInt(cfg, "org.jitsi.videobridge.STATISTICS_INTERVAL", 1000);
                interval = ConfigUtils.getInt(cfg, "org.jitsi.videobridge.STATISTICS_INTERVAL.callstats.io", interval);
                conferenceIDPrefix = ConfigUtils.getString(cfg, "io.callstats.sdk.CallStats.conferenceIDPrefix", null);
                this.conferenceStatsHandler = new CallStatsConferenceStatsHandler();
                this.conferenceStatsHandler.start((StatsService)service, bridgeId, conferenceIDPrefix, interval);
                topics = new String[] { "org/jitsi/*" };
                this.serviceRegistration = EventUtil.registerEventHandler(this.bundleContext, topics, this.conferenceStatsHandler);
                break;
            case 4:
                if (this.conferenceStatsHandler != null) {
                    this.conferenceStatsHandler.stop();
                    this.conferenceStatsHandler = null;
                }
                break;
        }
    }
}
