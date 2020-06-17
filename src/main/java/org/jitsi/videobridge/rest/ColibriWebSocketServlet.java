package org.jitsi.videobridge.rest;

import java.io.IOException;
import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.ServiceUtils;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.jitsi.videobridge.AbstractEndpoint;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.Endpoint;
import org.jitsi.videobridge.Videobridge;
import org.osgi.framework.BundleContext;

class ColibriWebSocketServlet extends WebSocketServlet {
    private static final Logger logger = Logger.getLogger(ColibriWebSocketServlet.class);

    private BundleContext bundleContext;

    private ColibriWebSocketService service;

    ColibriWebSocketServlet(BundleContext bundleContext, ColibriWebSocketService service) {
        this.bundleContext = bundleContext;
        this.service = service;
    }

    public void configure(WebSocketServletFactory webSocketServletFactory) {
        webSocketServletFactory.getPolicy().setIdleTimeout(60000L);
        webSocketServletFactory.setCreator(new WebSocketCreator() {
            public Object createWebSocket(ServletUpgradeRequest request, ServletUpgradeResponse response) {
                try {
                    return ColibriWebSocketServlet.this

                            .createWebSocket(request, response);
                } catch (IOException ioe) {
                    response.setSuccess(false);
                    return null;
                }
            }
        });
    }

    private ColibriWebSocket createWebSocket(ServletUpgradeRequest request, ServletUpgradeResponse response) throws IOException {
        String path = request.getRequestURI().getPath();
        if (path == null ||
                !path.startsWith("/colibri-ws/")) {
            if (logger.isDebugEnabled())
                logger.debug("Received request for an invalid path: " + path);
            response.sendError(404, "invalid path");
            return null;
        }
        path = path.substring("/colibri-ws/"
                .length(), path
                .length());
        String[] ids = path.split("/");
        if (ids.length < 3) {
            if (logger.isDebugEnabled())
                logger.debug("Received request for an invalid path: " + path);
            response.sendError(404, "invalid path");
            return null;
        }
        String serverId = getService().getServerId();
        if (!serverId.equals(ids[0])) {
            logger.warn("Received request with a mismatching server ID (expected '" + serverId + "', received '" + ids[0] + "').");
            response.sendError(404, "server ID mismatch");
            return null;
        }
        Videobridge videobridge = getVideobridge();
        if (videobridge == null) {
            logger.warn("No associated Videobridge");
            response.sendError(500, "no videobridge");
            return null;
        }
        String authFailed = "authentication failed";
        Conference conference = videobridge.getConference(ids[1], null);
        if (conference == null) {
            logger.warn("Received request for an nonexistent conference: " + ids[1]);
            response.sendError(403, authFailed);
            return null;
        }
        AbstractEndpoint abstractEndpoint = conference.getEndpoint(ids[2]);
        if (abstractEndpoint == null || !(abstractEndpoint instanceof Endpoint)) {
            logger.warn("Received request for a nonexistent endpoint: " + ids[1] + "(conference " + conference
                    .getID());
            response.sendError(403, authFailed);
            return null;
        }
        Endpoint endpoint = (Endpoint)abstractEndpoint;
        String pwd = getPwd(request.getRequestURI().getQuery());
        if (!endpoint.acceptWebSocket(pwd)) {
            response.sendError(403, authFailed);
            return null;
        }
        return new ColibriWebSocket(this, endpoint);
    }

    private String getPwd(String query) {
        if (query == null)
            return null;
        if (!query.startsWith("pwd="))
            return null;
        return query.substring("pwd=".length());
    }

    ColibriWebSocketService getService() {
        return this.service;
    }

    Videobridge getVideobridge() {
        return (Videobridge)ServiceUtils.getService(this.bundleContext, Videobridge.class);
    }
}
