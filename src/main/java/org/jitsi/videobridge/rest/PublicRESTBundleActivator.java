package org.jitsi.videobridge.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.RewriteRegexRule;
import org.eclipse.jetty.rewrite.handler.Rule;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.resource.Resource;
import org.jitsi.rest.AbstractJettyBundleActivator;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.util.ConfigUtils;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.rest.ssi.SSIResourceHandler;
import org.osgi.framework.BundleContext;

public class PublicRESTBundleActivator extends AbstractJettyBundleActivator {
    private static final Logger logger = Logger.getLogger(PublicRESTBundleActivator.class);

    public static final String JETTY_PROPERTY_PREFIX = "org.jitsi.videobridge.rest";

    public static final String JETTY_PROXY_SERVLET_HOST_HEADER_PNAME = ".jetty.ProxyServlet.hostHeader";

    public static final String JETTY_PROXY_SERVLET_PATH_SPEC_PNAME = ".jetty.ProxyServlet.pathSpec";

    public static final String JETTY_PROXY_SERVLET_PROXY_TO_PNAME = ".jetty.ProxyServlet.proxyTo";

    public static final String JETTY_RESOURCE_HANDLER_RESOURCE_BASE_PNAME = ".jetty.ResourceHandler.resourceBase";

    public static final String JETTY_CORS_ALLOWED_ORIGINS = ".jetty.cors.allowedOrigins";

    public static final String JETTY_RESOURCE_HANDLER_ALIAS_PREFIX = ".jetty.ResourceHandler.alias";

    public static final String JETTY_REWRITE_HANDLER_REGEX_PNAME = ".jetty.RewriteHandler.regex";

    public static final String JETTY_REWRITE_HANDLER_REPLACEMENT_PNAME = ".jetty.RewriteHandler.replacement";

    private ColibriWebSocketService colibriWebSocketService;

    public PublicRESTBundleActivator() {
        super("org.jitsi.videobridge.rest");
    }

    protected void doStop(BundleContext bundleContext) throws Exception {
        if (this.server != null)
            Thread.sleep(1000L);
        super.doStop(bundleContext);
    }

    protected Handler initializeHandler(BundleContext bundleContext, Server server) throws Exception {
        HandlerWrapper handlerWrapper1 = null;
        Handler handler = super.initializeHandler(bundleContext, server);
        HandlerWrapper rewriteHandler = initializeRewriteHandler(bundleContext, server);
        if (rewriteHandler != null) {
            rewriteHandler.setHandler(handler);
            handlerWrapper1 = rewriteHandler;
        }
        return (Handler)handlerWrapper1;
    }

    protected Handler initializeHandlerList(BundleContext bundleContext, Server server) throws Exception {
        List<Handler> handlers = new ArrayList<>();
        Handler resourceHandler = initializeResourceHandler(bundleContext, server);
        if (resourceHandler != null)
            handlers.add(resourceHandler);
        Handler aliasHandler = initializeResourceHandlerAliases(bundleContext, server);
        if (aliasHandler != null)
            handlers.add(aliasHandler);
        Handler redirectHandler = initializeRedirectHandler(bundleContext, server);
        if (redirectHandler != null)
            handlers.add(redirectHandler);
        Handler servletHandler = initializeServletHandler(bundleContext, server);
        if (servletHandler != null)
            handlers.add(servletHandler);
        return initializeHandlerList(handlers);
    }

    private ServletHolder initializeLongPollingServlet(ServletContextHandler servletContextHandler) {
        ServletHolder holder = new ServletHolder();
        holder.setServlet((Servlet)new LongPollingServlet());
        servletContextHandler.addServlet(holder, HandlerImpl.COLIBRI_TARGET + "*");
        return holder;
    }

    private ServletHolder initializeProxyServlet(ServletContextHandler servletContextHandler) {
        String pathSpec = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest.jetty.ProxyServlet.pathSpec", null);
        ServletHolder holder = null;
        if (pathSpec != null && pathSpec.length() != 0) {
            String proxyTo = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest.jetty.ProxyServlet.proxyTo", null);
            if (proxyTo != null && proxyTo.length() != 0) {
                holder = new ServletHolder();
                holder.setHeldClass(ProxyServletImpl.class);
                holder.setInitParameter("maxThreads", Integer.toString(256));
                holder.setInitParameter("prefix", pathSpec);
                holder.setInitParameter("proxyTo", proxyTo);
                String hostHeader = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest.jetty.ProxyServlet.hostHeader", null);
                if (hostHeader != null && hostHeader.length() != 0)
                    holder.setInitParameter("hostHeader", hostHeader);
                servletContextHandler.addServlet(holder, pathSpec);
                String allowedOrigins = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest.jetty.cors.allowedOrigins", null);
                if (allowedOrigins != null && allowedOrigins.length() != 0) {
                    FilterHolder filterHolder = servletContextHandler.addFilter(CrossOriginFilter.class, "/*",

                            EnumSet.of(DispatcherType.REQUEST));
                    filterHolder.setInitParameter("allowedOrigins", allowedOrigins);
                }
                servletContextHandler.addFilter(TraceFilter.class, "/*",

                        EnumSet.of(DispatcherType.REQUEST));
            }
        }
        return holder;
    }

    private Handler initializeResourceHandler(BundleContext bundleContext, Server server) {
        ContextHandler contextHandler;
        String resourceBase = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest.jetty.ResourceHandler.resourceBase", null);
        if (resourceBase == null || resourceBase.length() == 0) {
            contextHandler = null;
        } else {
            SSIResourceHandler sSIResourceHandler = new SSIResourceHandler(this.cfg);
            sSIResourceHandler.setResourceBase(resourceBase);
            contextHandler = new ContextHandler();
            contextHandler.setHandler((Handler)sSIResourceHandler);
            contextHandler.addAliasCheck((ContextHandler.AliasCheck)new ContextHandler.ApproveAliases());
        }
        return (Handler)contextHandler;
    }

    private Handler initializeResourceHandlerAliases(BundleContext bundleContext, Server server) {
        return (Handler)new ResourceHandler() {
            public Resource getResource(String path) {
                String property = ".jetty.ResourceHandler.alias." + path;
                String value = ConfigUtils.getString(PublicRESTBundleActivator.this
                        .cfg, "org.jitsi.videobridge.rest" + property, null);
                try {
                    return (value == null) ? null :
                            Resource.newResource(value);
                } catch (IOException e) {
                    PublicRESTBundleActivator.logger.info("Error constructing resource.", e);
                    return null;
                }
            }
        };
    }

    private Handler initializeRedirectHandler(BundleContext bundleContext, Server server) {
        int privatePort;
        String privateSslContextFactoryKeyStorePath = getCfgString("org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePath", null);
        if (privateSslContextFactoryKeyStorePath == null) {
            privatePort = this.cfg.getInt("org.jitsi.videobridge.rest.private.jetty.port", 8080);
        } else {
            privatePort = this.cfg.getInt("org.jitsi.videobridge.rest.private.jetty.tls.port", 8443);
        }
        if (privatePort > 0)
            return (Handler)new RedirectHandler((privateSslContextFactoryKeyStorePath == null) ? "http" : "https", privatePort);
        return null;
    }

    private HandlerWrapper initializeRewriteHandler(BundleContext bundleContext, Server server) {
        String regex = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest.jetty.RewriteHandler.regex", null);
        RewriteHandler handler = null;
        if (regex != null && regex.length() != 0) {
            String replacement = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest.jetty.RewriteHandler.replacement", null);
            if (replacement != null) {
                RewriteRegexRule rule = new RewriteRegexRule();
                rule.setRegex(regex);
                rule.setReplacement(replacement);
                handler = new RewriteHandler();
                handler.addRule((Rule)rule);
            }
        }
        return (HandlerWrapper)handler;
    }

    private Handler initializeServletHandler(BundleContext bundleContext, Server server) {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        boolean b = false;
        ServletHolder servletHolder = initializeProxyServlet(servletContextHandler);
        if (servletHolder != null)
            b = true;
        servletHolder = initializeLongPollingServlet(servletContextHandler);
        if (servletHolder != null)
            b = true;
        ColibriWebSocketService colibriWebSocketService = new ColibriWebSocketService(bundleContext, isTls());
        servletHolder = colibriWebSocketService.initializeColibriWebSocketServlet(bundleContext, servletContextHandler);
        if (servletHolder != null) {
            this.colibriWebSocketService = colibriWebSocketService;
            b = true;
        }
        if (b) {
            servletContextHandler.setContextPath("/");
        } else {
            servletContextHandler = null;
        }
        return (Handler)servletContextHandler;
    }

    protected int getDefaultPort() {
        return -1;
    }

    protected int getDefaultTlsPort() {
        return -1;
    }

    protected void didStart(BundleContext bundleContext) throws Exception {
        super.didStart(bundleContext);
        if (this.colibriWebSocketService != null)
            bundleContext.registerService(ColibriWebSocketService.class
                    .getName(), this.colibriWebSocketService, null);
    }

    private class RedirectHandler extends AbstractHandler {
        private final String targetProtocol;

        private final int targetPort;

        RedirectHandler(String targetProtocol, int targetPort) {
            this.targetProtocol = targetProtocol;
            this.targetPort = targetPort;
        }

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if (target.startsWith("/colibri/") || target.startsWith("/about/")) {
                String host = request.getServerName();
                String location = this.targetProtocol + "://" + host + ":" + this.targetPort + target;
                response.setHeader("Location", location);
                response.setStatus(301);
                response.setContentLength(0);
                baseRequest.setHandled(true);
            }
        }
    }
}
