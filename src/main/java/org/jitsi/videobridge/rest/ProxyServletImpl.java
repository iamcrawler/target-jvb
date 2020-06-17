package org.jitsi.videobridge.rest;

import java.net.URI;
import java.net.URISyntaxException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.proxy.ProxyServlet;

public class ProxyServletImpl extends ProxyServlet.Transparent {
    private String proxyTo;

    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        String proxyTo = config.getInitParameter("proxyTo");
        if (proxyTo != null && !proxyTo.endsWith("/"))
            this.proxyTo = proxyTo;
    }

    protected String rewriteTarget(HttpServletRequest request) {
        String rewrittenURIStr = super.rewriteTarget(request);
        if (this.proxyTo != null && rewrittenURIStr != null) {
            String requestPath = request.getRequestURI();
            if (requestPath != null && !requestPath.endsWith("/")) {
                URI rewrittenURI = URI.create(rewrittenURIStr).normalize();
                String rewrittenPath = rewrittenURI.getPath();
                int len;
                if (rewrittenPath != null && (
                        len = rewrittenPath.length()) > 1 && rewrittenPath
                        .endsWith("/")) {
                    rewrittenPath = rewrittenPath.substring(0, len - 1);
                    try {
                        rewrittenURI = new URI(rewrittenURI.getScheme(), rewrittenURI.getAuthority(), rewrittenPath, rewrittenURI.getQuery(), rewrittenURI.getFragment());
                    } catch (URISyntaxException uRISyntaxException) {}
                }
                rewrittenURIStr = rewrittenURI.toString();
            }
        }
        return rewrittenURIStr;
    }
}
