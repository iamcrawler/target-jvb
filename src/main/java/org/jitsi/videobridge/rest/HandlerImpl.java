package org.jitsi.videobridge.rest;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.ServiceUtils;
import org.eclipse.jetty.server.Request;
import org.jitsi.rest.AbstractJSONHandler;
import org.jitsi.rest.RESTUtil;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.ConferenceSpeechActivity;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.videobridge.stats.Statistics;
import org.jitsi.videobridge.stats.StatsManager;
import org.jitsi.videobridge.xmpp.ClientConnectionImpl;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.ShutdownIQ;
import org.jivesoftware.smack.packet.IQ;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.jxmpp.jid.impl.JidCreate;
import org.osgi.framework.BundleContext;

class HandlerImpl extends AbstractJSONHandler {
    static final String COLIBRI_TARGET;

    private static final String CONFERENCES = "conferences";

    private static final String DEFAULT_COLIBRI_TARGET = "/colibri/";

    private static final String DOMINANT_SPEAKER_IDENTIFICATION = "dominant-speaker-identification";

    private static final Logger logger = Logger.getLogger(HandlerImpl.class);

    private static final String SHUTDOWN = "shutdown";

    private static final String STATISTICS = "stats";

    private static final String MUC_CLIENT = "muc-client";

    private final boolean shutdownEnabled;

    private final boolean colibriEnabled;

    static {
        String colibriTarget = "/colibri/";
        if (!colibriTarget.endsWith("/"))
            colibriTarget = colibriTarget + "/";
        COLIBRI_TARGET = colibriTarget;
    }

    public HandlerImpl(BundleContext bundleContext, boolean enableShutdown, boolean enableColibri) {
        super(bundleContext);
        this.shutdownEnabled = enableShutdown;
        if (this.shutdownEnabled)
            logger.info("Graceful shutdown over REST is enabled");
        this.colibriEnabled = enableColibri;
        if (this.colibriEnabled)
            logger.info("Colibri REST endpoints are enabled");
    }

    private void doGetConferenceJSON(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Videobridge videobridge = getVideobridge();
        if (videobridge == null) {
            response.setStatus(503);
        } else {
            int conferenceIDEndIndex = target.indexOf('/');
            String conferenceID = target;
            if (conferenceIDEndIndex > 0 && conferenceIDEndIndex < target
                    .length() - 1) {
                target = target.substring(conferenceIDEndIndex + 1);
                if ("dominant-speaker-identification".equals(target))
                    conferenceID = conferenceID.substring(0, conferenceIDEndIndex);
            }
            Conference conference = videobridge.getConference(conferenceID, null);
            if (conference == null) {
                response.setStatus(404);
            } else if ("dominant-speaker-identification".equals(target)) {
                doGetDominantSpeakerIdentificationJSON(conference, baseRequest, request, response);
            } else {
                ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();
                conference.describeDeep(conferenceIQ);
                JSONObject conferenceJSONObject = JSONSerializer.serializeConference(conferenceIQ);
                if (conferenceJSONObject == null) {
                    response.setStatus(500);
                } else {
                    response.setStatus(200);
                    conferenceJSONObject.writeJSONString(response.getWriter());
                }
            }
        }
    }

    private void doGetConferencesJSON(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Videobridge videobridge = getVideobridge();
        if (videobridge == null) {
            response.setStatus(503);
        } else {
            Conference[] conferences = videobridge.getConferences();
            List<ColibriConferenceIQ> conferenceIQs = new ArrayList<>();
            for (Conference conference : conferences) {
                ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();
                conferenceIQ.setID(conference.getID());
                conferenceIQs.add(conferenceIQ);
            }
            JSONArray conferencesJSONArray = JSONSerializer.serializeConferences(conferenceIQs);
            if (conferencesJSONArray == null)
                conferencesJSONArray = new JSONArray();
            response.setStatus(200);
            conferencesJSONArray.writeJSONString(response.getWriter());
        }
    }

    private void doGetDominantSpeakerIdentificationJSON(Conference conference, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        ConferenceSpeechActivity conferenceSpeechActivity = conference.getSpeechActivity();
        if (conferenceSpeechActivity == null) {
            response.setStatus(503);
        } else {
            JSONObject jsonObject = conferenceSpeechActivity.doGetDominantSpeakerIdentificationJSON();
            if (jsonObject != null) {
                response.setStatus(200);
                jsonObject.writeJSONString(response.getWriter());
            }
        }
    }

    protected void doGetHealthJSON(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        beginResponse(null, baseRequest, request, response);
        Videobridge videobridge = getVideobridge();
        if (videobridge == null) {
            response.setStatus(503);
        } else {
            getHealthJSON(videobridge, baseRequest, request, response);
        }
        endResponse(null, baseRequest, request, response);
    }

    public static void getHealthJSON(Videobridge videobridge, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        int status;
        String reason = null;
        try {
            videobridge.healthCheck();
            status = 200;
        } catch (Exception ex) {
            if (ex instanceof IOException)
                throw (IOException)ex;
            if (ex instanceof ServletException)
                throw (ServletException)ex;
            status = 500;
            reason = ex.getMessage();
        }
        if (reason != null)
            response.getOutputStream().println(reason);
        response.setStatus(status);
    }

    private void doGetStatisticsJSON(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        BundleContext bundleContext = getBundleContext();
        if (bundleContext != null) {
            StatsManager statsManager = (StatsManager)ServiceUtils.getService(bundleContext, StatsManager.class);
            if (statsManager != null) {
                Iterator<Statistics> i = statsManager.getStatistics().iterator();
                Statistics statistics = null;
                if (i.hasNext())
                    statistics = i.next();
                JSONObject statisticsJSONObject = JSONSerializer.serializeStatistics(statistics);
                Writer writer = response.getWriter();
                response.setStatus(200);
                if (statisticsJSONObject == null) {
                    writer.write("null");
                } else {
                    statisticsJSONObject.writeJSONString(writer);
                }
                return;
            }
        }
        response.setStatus(503);
    }

    private void doPatchConferenceJSON(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Videobridge videobridge = getVideobridge();
        if (videobridge == null) {
            response.setStatus(503);
        } else {
            Conference conference = videobridge.getConference(target, null);
            if (conference == null) {
                String message = String.format("Failed to patch conference: %s, conference not found", new Object[] { target });
                logger.error(message);
                response.getOutputStream().println(message);
                response.setStatus(404);
            } else if (RESTUtil.isJSONContentType(request.getContentType())) {
                Object requestJSONObject = null;
                int status = 0;
                try {
                    requestJSONObject = (new JSONParser()).parse(request.getReader());
                    if (requestJSONObject == null || !(requestJSONObject instanceof JSONObject)) {
                        String message = String.format("Failed to patch conference: %s, could not parse JSON", new Object[] { target });
                        logger.error(message);
                        response.getOutputStream().println(message);
                        status = 400;
                    }
                } catch (ParseException pe) {
                    String message = String.format("Failed to patch conference: %s, could not parse JSON message: %s", new Object[] { target, pe

                            .getMessage() });
                    logger.error(message);
                    response.getOutputStream().println(message);
                    status = 400;
                }
                if (status == 0) {
                    ColibriConferenceIQ requestConferenceIQ = JSONDeserializer.deserializeConference((JSONObject)requestJSONObject);
                    if (requestConferenceIQ == null || (requestConferenceIQ
                            .getID() != null &&
                            !requestConferenceIQ.getID().equals(conference
                                    .getID()))) {
                        String message = String.format("Failed to patch conference: %s, conference JSON has invalid conference id", new Object[] { target });
                        logger.error(message);
                        response.getOutputStream().println(message);
                        status = 400;
                    } else {
                        ColibriConferenceIQ responseConferenceIQ = null;
                        try {
                            IQ responseIQ = videobridge.handleColibriConferenceIQ(requestConferenceIQ, 1);
                            if (responseIQ instanceof ColibriConferenceIQ) {
                                responseConferenceIQ = (ColibriConferenceIQ)responseIQ;
                            } else {
                                status = getHttpStatusCodeForResultIq(responseIQ);
                            }
                            if (responseIQ.getError() != null) {
                                String message = String.format("Failed to patch conference: %s, message: %s", new Object[] { target, responseIQ

                                        .getError().getDescriptiveText() });
                                logger.error(message);
                                response.getOutputStream().println(message);
                            }
                        } catch (Exception e) {
                            String message = String.format("Failed to patch conference: %s, message: %s", new Object[] { target, e

                                    .getMessage() });
                            logger.error(message);
                            response.getOutputStream().println(message);
                            status = 500;
                        }
                        if (status == 0 && responseConferenceIQ != null) {
                            JSONObject responseJSONObject = JSONSerializer.serializeConference(responseConferenceIQ);
                            if (responseJSONObject == null)
                                responseJSONObject = new JSONObject();
                            response.setStatus(200);
                            responseJSONObject.writeJSONString(response
                                    .getWriter());
                        }
                    }
                } else {
                    response.setStatus(status);
                }
            } else {
                String message = String.format("Failed to patch conference: %s, invalid content type, must be %s", new Object[] { target, "application/json" });
                logger.error(message);
                response.getOutputStream().println(message);
                response.setStatus(406);
            }
        }
    }

    private void doPostConferencesJSON(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Videobridge videobridge = getVideobridge();
        if (videobridge == null) {
            response.setStatus(503);
        } else if (RESTUtil.isJSONContentType(request.getContentType())) {
            Object requestJSONObject = null;
            int status = 0;
            try {
                requestJSONObject = (new JSONParser()).parse(request.getReader());
                if (requestJSONObject == null || !(requestJSONObject instanceof JSONObject)) {
                    String message = "Failed to create conference, could not parse JSON";
                    logger.error(message);
                    response.getOutputStream().println(message);
                    status = 400;
                }
            } catch (ParseException pe) {
                String message = String.format("Failed to create conference, could not parse JSON, message: %s", new Object[] { pe
                        .getMessage() });
                logger.error(message);
                response.getOutputStream().println(message);
                status = 400;
            }
            if (status == 0) {
                ColibriConferenceIQ requestConferenceIQ = JSONDeserializer.deserializeConference((JSONObject)requestJSONObject);
                if (requestConferenceIQ == null || requestConferenceIQ
                        .getID() != null) {
                    status = 400;
                } else {
                    ColibriConferenceIQ responseConferenceIQ = null;
                    try {
                        IQ responseIQ = videobridge.handleColibriConferenceIQ(requestConferenceIQ, 1);
                        if (responseIQ instanceof ColibriConferenceIQ) {
                            responseConferenceIQ = (ColibriConferenceIQ)responseIQ;
                        } else {
                            status = getHttpStatusCodeForResultIq(responseIQ);
                        }
                        if (responseIQ.getError() != null) {
                            String message = String.format("Failed to create conference, message: %s", new Object[] { responseIQ

                                    .getError().getDescriptiveText() });
                            logger.error(message);
                            response.getOutputStream().println(message);
                        }
                    } catch (Exception e) {
                        status = 500;
                    }
                    if (status == 0 && responseConferenceIQ != null) {
                        JSONObject responseJSONObject = JSONSerializer.serializeConference(responseConferenceIQ);
                        if (responseJSONObject == null)
                            responseJSONObject = new JSONObject();
                        response.setStatus(200);
                        responseJSONObject.writeJSONString(response
                                .getWriter());
                    }
                }
            } else {
                response.setStatus(status);
            }
        } else {
            response.setStatus(406);
        }
    }

    private void doPostShutdownJSON(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object requestJSONObject;
        int status;
        Videobridge videobridge = getVideobridge();
        if (videobridge == null) {
            response.setStatus(503);
            return;
        }
        if (!RESTUtil.isJSONContentType(request.getContentType())) {
            response.setStatus(406);
            return;
        }
        try {
            requestJSONObject = (new JSONParser()).parse(request.getReader());
            if (requestJSONObject == null || !(requestJSONObject instanceof JSONObject)) {
                response.setStatus(400);
                return;
            }
        } catch (ParseException pe) {
            response.setStatus(400);
            return;
        }
        ShutdownIQ requestShutdownIQ = JSONDeserializer.deserializeShutdownIQ((JSONObject)requestJSONObject);
        if (requestShutdownIQ == null) {
            status = 400;
        } else {
            String ipAddress = request.getHeader("X-FORWARDED-FOR");
            if (ipAddress == null)
                ipAddress = request.getRemoteAddr();
            requestShutdownIQ.setFrom(JidCreate.from(ipAddress));
            try {
                IQ responseIQ = videobridge.handleShutdownIQ(requestShutdownIQ);
                if (IQ.Type.result.equals(responseIQ.getType())) {
                    status = 200;
                } else {
                    status = getHttpStatusCodeForResultIq(responseIQ);
                }
            } catch (Exception e) {
                logger.error("Error while trying to handle shutdown request", e);
                status = 500;
            }
        }
        response.setStatus(status);
    }

    public Videobridge getVideobridge() {
        return (Videobridge)getService(Videobridge.class);
    }

    private void handleColibriJSON(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (!this.colibriEnabled) {
            response.setStatus(503);
            return;
        }
        if (target != null)
            if (target.startsWith("conferences")) {
                target = target.substring("conferences".length());
                if (target.startsWith("/"))
                    target = target.substring(1);
                String requestMethod = request.getMethod();
                if ("".equals(target)) {
                    if ("GET".equals(requestMethod)) {
                        doGetConferencesJSON(baseRequest, request, response);
                    } else if ("POST".equals(requestMethod)) {
                        doPostConferencesJSON(baseRequest, request, response);
                    } else {
                        response.setStatus(405);
                    }
                } else if ("GET".equals(requestMethod)) {
                    doGetConferenceJSON(target, baseRequest, request, response);
                } else if ("PATCH".equals(requestMethod)) {
                    doPatchConferenceJSON(target, baseRequest, request, response);
                } else {
                    response.setStatus(405);
                }
            } else if (target.equals("stats")) {
                if ("GET".equals(request.getMethod())) {
                    doGetStatisticsJSON(baseRequest, request, response);
                } else {
                    response.setStatus(405);
                }
            } else if (target.equals("shutdown")) {
                if (!this.shutdownEnabled) {
                    response.setStatus(503);
                    return;
                }
                if ("POST".equals(request.getMethod())) {
                    doPostShutdownJSON(baseRequest, request, response);
                } else {
                    response.setStatus(405);
                }
            } else if (target.startsWith("muc-client/")) {
                doHandleMucClientRequest(target
                        .substring("muc-client/".length()), request, response);
            }
    }

    private void doHandleMucClientRequest(String target, HttpServletRequest request, HttpServletResponse response) {
        JSONObject requestJSONObject;
        if (!"POST".equals(request.getMethod())) {
            response.setStatus(405);
            return;
        }
        if (!RESTUtil.isJSONContentType(request.getContentType())) {
            response.setStatus(415);
            return;
        }
        try {
            Object o = (new JSONParser()).parse(request.getReader());
            if (o instanceof JSONObject) {
                requestJSONObject = (JSONObject)o;
            } else {
                requestJSONObject = null;
            }
        } catch (Exception e) {
            requestJSONObject = null;
        }
        if (requestJSONObject == null) {
            response.setStatus(400);
            return;
        }
        ClientConnectionImpl clientConnectionImpl = (ClientConnectionImpl)getService(ClientConnectionImpl.class);
        if (clientConnectionImpl == null) {
            response.setStatus(503);
            return;
        }
        if ("add".equals(target)) {
            if (clientConnectionImpl.addMucClient(requestJSONObject)) {
                response.setStatus(200);
            } else {
                response.setStatus(400);
            }
        } else if ("remove".equals(target)) {
            if (clientConnectionImpl.removeMucClient(requestJSONObject)) {
                response.setStatus(200);
            } else {
                response.setStatus(400);
            }
        } else {
            response.setStatus(404);
            return;
        }
    }

    protected void handleJSON(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        super.handleJSON(target, baseRequest, request, response);
        if (baseRequest.isHandled())
            return;
        if (target.startsWith(COLIBRI_TARGET)) {
            target = target.substring(COLIBRI_TARGET.length());
            int oldResponseStatus = response.getStatus();
            response.setStatus(501);
            beginResponse(target, baseRequest, request, response);
            handleColibriJSON(target, baseRequest, request, response);
            int newResponseStatus = response.getStatus();
            if (newResponseStatus == 501) {
                response.setStatus(oldResponseStatus);
            } else {
                endResponse(target, baseRequest, request, response);
            }
        } else {
            String versionTarget = "/version";
            if (versionTarget.equals(target)) {
                target = target.substring(versionTarget.length());
                handleVersionJSON(target, baseRequest, request, response);
            }
        }
    }
}
