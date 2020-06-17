package org.jitsi.videobridge.xmpp;

import java.util.Collection;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.utils.logging.Logger;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.ShutdownIQ;
import org.jitsi.xmpp.extensions.health.HealthCheckIQ;
import org.jitsi.xmpp.mucclient.IQListener;
import org.jitsi.xmpp.mucclient.MucClientConfiguration;
import org.jitsi.xmpp.mucclient.MucClientManager;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smackx.iqversion.packet.Version;
import org.json.simple.JSONObject;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

public class ClientConnectionImpl implements BundleActivator, IQListener {
    private static final Logger logger = Logger.getLogger(ClientConnectionImpl.class);

    private static final String PREFIX = "org.jitsi.videobridge.xmpp.user.";

    private MucClientManager mucClientManager;

    private final XmppCommon common = new XmppCommon();

    public void start(BundleContext bundleContext) {
        ConfigurationService config = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        if (config == null) {
            logger.info("Not using XMPP user login; no config service.");
            return;
        }
        Collection<ClientConnectionImpl> userLoginBundles = ServiceUtils2.getServices(bundleContext, ClientConnectionImpl.class);
        if (!userLoginBundles.contains(this)) {
            this.common.start(bundleContext);
            this.mucClientManager = new MucClientManager(XmppCommon.FEATURES);
            this.mucClientManager.registerIQ((IQ)new HealthCheckIQ());
            this.mucClientManager.registerIQ((IQ)new ColibriConferenceIQ());
            this.mucClientManager.registerIQ((IQ)new Version());
            this.mucClientManager.registerIQ((IQ)ShutdownIQ.createForceShutdownIQ());
            this.mucClientManager.registerIQ(
                    (IQ)ShutdownIQ.createGracefulShutdownIQ());
            this.mucClientManager.setIQListener(this);
            Collection<MucClientConfiguration> configurations = MucClientConfiguration.loadFromConfigService(config, "org.jitsi.videobridge.xmpp.user.", true);
            configurations.forEach(c -> this.mucClientManager.addMucClient(c));
            bundleContext.registerService(ClientConnectionImpl.class, this, null);
        } else {
            logger.error("Already started");
        }
    }

    public IQ handleIq(IQ iq) {
        return this.common.handleIQ(iq);
    }

    public void stop(BundleContext bundleContext) throws Exception {
        try {
            Collection<ServiceReference<ComponentImpl>> serviceReferences = bundleContext.getServiceReferences(ComponentImpl.class, null);
            if (serviceReferences != null)
                for (ServiceReference<ComponentImpl> serviceReference : serviceReferences) {
                    Object service = bundleContext.getService(serviceReference);
                    if (service == this)
                        bundleContext.ungetService(serviceReference);
                }
        } finally {
            this.common.stop(bundleContext);
        }
    }

    public void setPresenceExtension(ExtensionElement extension) {
        this.mucClientManager.setPresenceExtension(extension);
    }

    public boolean addMucClient(JSONObject jsonObject) {
        if (jsonObject == null || !(jsonObject.get("id") instanceof String))
            return false;
        MucClientConfiguration config = new MucClientConfiguration((String)jsonObject.get("id"));
        for (Object key : jsonObject.keySet()) {
            Object value = jsonObject.get(key);
            if (key instanceof String && value instanceof String &&
                    !"id".equals(key))
                config.setProperty((String)key, (String)value);
        }
        if (!config.isComplete()) {
            logger.info("Not adding a MucClient, configuration incomplete.");
            return false;
        }
        if (this.mucClientManager == null) {
            logger.warn("Not adding a MucClient. Not started?");
            return false;
        }
        this.mucClientManager.addMucClient(config);
        return true;
    }

    public boolean removeMucClient(JSONObject jsonObject) {
        if (jsonObject == null || !(jsonObject.get("id") instanceof String) || this.mucClientManager == null)
            return false;
        this.mucClientManager.removeMucClient((String)jsonObject.get("id"));
        return true;
    }
}
