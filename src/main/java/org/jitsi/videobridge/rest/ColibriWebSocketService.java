package org.jitsi.videobridge.rest;

import javax.servlet.Servlet;
import net.java.sip.communicator.util.ServiceUtils;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jitsi.service.configuration.ConfigurationService;
import org.osgi.framework.BundleContext;

public class ColibriWebSocketService {
    public static final String DOMAIN_PNAME = "org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN";

    public static final String SERVER_ID_PNAME = "org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID";

    public static final String TLS_PNAME = "org.jitsi.videobridge.rest.COLIBRI_WS_TLS";

    public static final String DISABLE_PNAME = "org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE";

    public static final String COLIBRI_WS_PATH = "/colibri-ws/";

    private final String baseUrl;

    private final String serverId;

    public ColibriWebSocketService(BundleContext bundleContext, boolean tls) {
        ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        String baseUrl = null;
        String serverId = null;
        if (cfg != null && !cfg.getBoolean("org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE", false)) {
            String domain = cfg.getString("org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN", null);
            if (domain != null) {
                tls = cfg.getBoolean("org.jitsi.videobridge.rest.COLIBRI_WS_TLS", tls);
                serverId = cfg.getString("org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID", "default-id");
                baseUrl = tls ? "wss://" : "ws://";
                baseUrl = baseUrl + domain + "/colibri-ws/" + serverId + "/";
            }
        }
        this.baseUrl = baseUrl;
        this.serverId = serverId;
    }

    String getServerId() {
        return this.serverId;
    }

    public String getColibriWebSocketUrl(String conferenceId, String endpointId, String pwd) {
        if (this.baseUrl == null)
            return null;
        return this.baseUrl + conferenceId + "/" + endpointId + "?pwd=" + pwd;
    }

    ServletHolder initializeColibriWebSocketServlet(BundleContext bundleContext, ServletContextHandler servletContextHandler) {
        ServletHolder holder = null;
        ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        if (this.baseUrl != null && (cfg == null ||
                !cfg.getBoolean("org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE", false))) {
            holder = new ServletHolder();
            holder.setServlet((Servlet)new ColibriWebSocketServlet(bundleContext, this));
            servletContextHandler.addServlet(holder, "/colibri-ws/*");
        }
        return holder;
    }
}
