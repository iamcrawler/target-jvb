package org.jitsi.videobridge.xmpp;

import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.service.version.Version;
import org.jitsi.service.version.VersionService;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.ShutdownIQ;
import org.jitsi.xmpp.extensions.health.HealthCheckIQ;
import org.jitsi.xmpp.util.IQUtils;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.XMPPError;
//import org.jivesoftware.smackx.iqversion.packet.Version;
import org.osgi.framework.BundleContext;

class XmppCommon {
    private static final Logger logger = Logger.getLogger(XmppCommon.class);

    static final String[] FEATURES = new String[] { "http://jitsi.org/protocol/colibri", "http://jitsi.org/protocol/healthcheck", "urn:xmpp:jingle:apps:dtls:0", "urn:xmpp:jingle:transports:ice-udp:1", "urn:xmpp:jingle:transports:raw-udp:1", "jabber:iq:version" };

    private BundleContext bundleContext;

    BundleContext getBundleContext() {
        return this.bundleContext;
    }

    void start(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    void stop(BundleContext bundleContext) {
        this.bundleContext = null;
    }

    Videobridge getVideobridge() {
        BundleContext bundleContext = getBundleContext();
        return (bundleContext != null) ?
                (Videobridge)ServiceUtils2.getService(bundleContext, Videobridge.class) : null;
    }

    private VersionService getVersionService() {
        BundleContext bundleContext = getBundleContext();
        return (bundleContext != null) ?
                (VersionService)ServiceUtils2.getService(bundleContext, VersionService.class) : null;
    }

    IQ handleIQ(IQ requestIQ) {
        if (logger.isDebugEnabled() && requestIQ != null)
            logger.debug("RECV: " + requestIQ.toXML());
        IQ replyIQ = handleIQInternal(requestIQ);
        if (logger.isDebugEnabled() && replyIQ != null)
            logger.debug("SENT: " + replyIQ.toXML());
        return replyIQ;
    }

    private IQ handleIQInternal(IQ iq) {
        IQ responseIQ = null;
        if (iq != null) {
            IQ.Type type = iq.getType();
            if (IQ.Type.get.equals(type) || IQ.Type.set.equals(type)) {
                responseIQ = handleIQRequest(iq);
                if (responseIQ != null) {
                    responseIQ.setFrom(iq.getTo());
                    responseIQ.setStanzaId(iq.getStanzaId());
                    responseIQ.setTo(iq.getFrom());
                }
            } else if (IQ.Type.error.equals(type) || IQ.Type.result.equals(type)) {
                handleIQResponse(iq);
            }
        }
        return responseIQ;
    }

    private IQ handleIQRequest(IQ request) {
        IQ response;
        if (request instanceof Version)
            return handleVersionIQ((Version)request);
        Videobridge videobridge = getVideobridge();
        if (videobridge == null)
            return IQUtils.createError(request, XMPPError.Condition.internal_server_error, "No Videobridge service is running");
        if (request instanceof ColibriConferenceIQ) {
            response = videobridge.handleColibriConferenceIQ((ColibriConferenceIQ)request);
        } else if (request instanceof HealthCheckIQ) {
            response = videobridge.handleHealthCheckIQ((HealthCheckIQ)request);
        } else if (request instanceof ShutdownIQ) {
            response = videobridge.handleShutdownIQ((ShutdownIQ)request);
        } else {
            response = null;
        }
        return response;
    }

    private IQ handleVersionIQ(Version versionRequest) {
        VersionService versionService = getVersionService();
        if (versionService == null)
            return (IQ)IQ.createErrorResponse((IQ)versionRequest,

                    XMPPError.getBuilder(XMPPError.Condition.service_unavailable));
        Version currentVersion = versionService.getCurrentVersion();
        if (currentVersion == null)
            return (IQ)IQ.createErrorResponse((IQ)versionRequest,

                    XMPPError.getBuilder(XMPPError.Condition.internal_server_error));
        org.jivesoftware.smackx.iqversion.packet.Version versionResult = new org.jivesoftware.smackx.iqversion.packet.Version(
                currentVersion.getApplicationName(),
                currentVersion.toString(),
                System.getProperty("os.name"));
        versionResult.setType(IQ.Type.result);
        return (IQ)versionResult;
    }

    private void handleIQResponse(IQ response) {
        Videobridge videobridge = getVideobridge();
        if (videobridge != null)
            videobridge.handleIQResponse(response);
    }
}
