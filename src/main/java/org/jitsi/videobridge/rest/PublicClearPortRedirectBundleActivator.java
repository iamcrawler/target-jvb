package org.jitsi.videobridge.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.jitsi.rest.AbstractJettyBundleActivator;
import org.jitsi.utils.logging.Logger;
import org.osgi.framework.BundleContext;

public class PublicClearPortRedirectBundleActivator extends AbstractJettyBundleActivator {
    private static final Logger logger = Logger.getLogger(PublicClearPortRedirectBundleActivator.class);

    public static final String JETTY_PROPERTY_PREFIX = "org.jitsi.videobridge.clearport.redirect";

    public PublicClearPortRedirectBundleActivator() {
        super("org.jitsi.videobridge.clearport.redirect");
    }

    protected boolean willStart(BundleContext bundleContext) throws Exception {
        if (this.cfg.getProperty("org.jitsi.videobridge.rest.jetty.tls.port") == null)
            return false;
        if (this.cfg.getProperty("org.jitsi.videobridge.clearport.redirect.jetty.port") == null)
            this.cfg.setProperty("org.jitsi.videobridge.clearport.redirect.jetty.port", Integer.valueOf(80));
        return super.willStart(bundleContext);
    }

    protected Handler initializeHandlerList(BundleContext bundleContext, Server server) throws Exception {
        List<Handler> handlers = new ArrayList<>();
        handlers.add(new RedirectHandler(this.cfg

                .getInt("org.jitsi.videobridge.rest.jetty.tls.port", 443)));
        return initializeHandlerList(handlers);
    }

    public void start(BundleContext bundleContext) throws Exception {
        try {
            super.start(bundleContext);
        } catch (Exception t) {
            logger.warn("Could not start redirect from clear port(80) to secure port:" + t

                    .getMessage());
        }
    }

    private class RedirectHandler extends AbstractHandler {
        private final int targetPort;

        RedirectHandler(int targetPort) {
            this.targetPort = targetPort;
        }

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            String host = request.getServerName();
            String location = "https://" + host + ":" + this.targetPort + target;
            response.setHeader("Location", location);
            response.setStatus(301);
            response.setContentLength(0);
            baseRequest.setHandled(true);
        }
    }
}
