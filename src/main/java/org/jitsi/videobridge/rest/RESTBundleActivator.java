package org.jitsi.videobridge.rest;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.jitsi.rest.AbstractJettyBundleActivator;
import org.osgi.framework.BundleContext;

public class RESTBundleActivator extends AbstractJettyBundleActivator {
    public static final String ENABLE_REST_SHUTDOWN_PNAME = "org.jitsi.videobridge.ENABLE_REST_SHUTDOWN";

    public static final String ENABLE_REST_COLIBRI_PNAME = "org.jitsi.videobridge.ENABLE_REST_COLIBRI";

    public static final String JETTY_PROPERTY_PREFIX = "org.jitsi.videobridge.rest.private";

    public RESTBundleActivator() {
        super("org.jitsi.videobridge.rest.private");
    }

    protected void doStop(BundleContext bundleContext) throws Exception {
        if (this.server != null)
            Thread.sleep(1000L);
        super.doStop(bundleContext);
    }

    private Handler initializeColibriHandler(BundleContext bundleContext, Server server) {
        return (Handler)new HandlerImpl(bundleContext,

                getCfgBoolean("org.jitsi.videobridge.ENABLE_REST_SHUTDOWN", false),
                getCfgBoolean("org.jitsi.videobridge.ENABLE_REST_COLIBRI", true));
    }

    protected Handler initializeHandlerList(BundleContext bundleContext, Server server) throws Exception {
        List<Handler> handlers = new ArrayList<>();
        Handler colibriHandler = initializeColibriHandler(bundleContext, server);
        if (colibriHandler != null)
            handlers.add(colibriHandler);
        return initializeHandlerList(handlers);
    }

    protected boolean willStart(BundleContext bundleContext) throws Exception {
        boolean b = super.willStart(bundleContext);
        if (b)
            b = getCfgBoolean("org.jitsi.videobridge.rest", false);
        return b;
    }
}
