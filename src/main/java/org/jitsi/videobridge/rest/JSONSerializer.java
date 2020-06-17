package org.jitsi.videobridge.rest;

import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jitsi.videobridge.stats.Statistics;
import org.jitsi.xmpp.extensions.AbstractPacketExtension;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.RTPLevelRelayType;
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension;
import org.jitsi.xmpp.extensions.jingle.CandidatePacketExtension;
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension;
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension;
import org.jitsi.xmpp.extensions.jingle.RemoteCandidatePacketExtension;
import org.jitsi.xmpp.extensions.jingle.RtcpFbPacketExtension;
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

final class JSONSerializer {
    static final String CANDIDATE_LIST = "candidates";

    static final String CHANNEL_BUNDLES = "channel-bundles";

    static final String ENDPOINTS = "endpoints";

    static final String CHANNELS = "channels";

    static final String CONTENTS = "contents";

    static final String FINGERPRINTS = "fingerprints";

    static final String PARAMETERS = "parameters";

    static final String PAYLOAD_TYPES = "payload-types";

    static final String RTCP_FBS = "rtcp-fbs";

    static final String SCTP_CONNECTIONS = "sctpconnections";

    static final String SOURCE_GROUPS = "ssrc-groups";

    static final String SOURCES = "sources";

    static final String SSRCS = "ssrcs";

    static final String RTP_HEADER_EXTS = "rtp-hdrexts";

    static final String WEBSOCKET_LIST = "web-sockets";

    static final String XMLNS = "xmlns";

    public static void serializeAbstractPacketExtensionAttributes(AbstractPacketExtension abstractPacketExtension, JSONObject jsonObject) {
        for (String name : abstractPacketExtension.getAttributeNames()) {
            Object value = abstractPacketExtension.getAttribute(name);
            if (value instanceof Enum)
                value = value.toString();
            jsonObject.put(name, value);
        }
    }

    public static JSONObject serializeCandidate(CandidatePacketExtension candidate) {
        JSONObject candidateJSONObject;
        if (candidate == null) {
            candidateJSONObject = null;
        } else {
            candidateJSONObject = new JSONObject();
            serializeAbstractPacketExtensionAttributes((AbstractPacketExtension)candidate, candidateJSONObject);
        }
        return candidateJSONObject;
    }

    public static JSONArray serializeCandidates(Collection<CandidatePacketExtension> candidates) {
        JSONArray candidatesJSONArray;
        if (candidates == null) {
            candidatesJSONArray = null;
        } else {
            candidatesJSONArray = new JSONArray();
            for (CandidatePacketExtension candidate : candidates)
                candidatesJSONArray.add(serializeCandidate(candidate));
        }
        return candidatesJSONArray;
    }

    public static JSONObject serializeChannel(ColibriConferenceIQ.Channel channel) {
        JSONObject jsonObject;
        if (channel == null) {
            jsonObject = null;
        } else {
            String direction = channel.getDirection();
            Integer lastN = channel.getLastN();
            List<PayloadTypePacketExtension> payloadTypes = channel.getPayloadTypes();
            Integer receivingSimulcastStream = channel.getReceivingSimulcastLayer();
            RTPLevelRelayType rtpLevelRelayType = channel.getRTPLevelRelayType();
            List<SourcePacketExtension> sources = channel.getSources();
            List<SourceGroupPacketExtension> sourceGroups = channel.getSourceGroups();
            int[] ssrcs = channel.getSSRCs();
            jsonObject = serializeChannelCommon((ColibriConferenceIQ.ChannelCommon)channel);
            if (direction != null)
                jsonObject.put("direction", direction);
            if (lastN != null)
                jsonObject.put("last-n", lastN);
            if (lastN != null)
                jsonObject.put("receive-simulcast-layer", receivingSimulcastStream);
            if (payloadTypes != null && !payloadTypes.isEmpty())
                jsonObject.put("payload-types",

                        serializePayloadTypes(payloadTypes));
            if (rtpLevelRelayType != null)
                jsonObject.put("rtp-level-relay-type", rtpLevelRelayType

                        .toString());
            if (sources != null && !sources.isEmpty())
                jsonObject.put("sources", serializeSources(sources));
            if (sourceGroups != null && !sourceGroups.isEmpty())
                jsonObject.put("ssrc-groups",

                        serializeSourceGroups(sourceGroups));
            if (ssrcs != null && ssrcs.length > 0)
                jsonObject.put("ssrcs", serializeSSRCs(ssrcs));
        }
        return jsonObject;
    }

    public static JSONObject serializeChannelBundle(ColibriConferenceIQ.ChannelBundle channelBundle) {
        JSONObject jsonObject;
        if (channelBundle == null) {
            jsonObject = null;
        } else {
            String id = channelBundle.getId();
            IceUdpTransportPacketExtension transport = channelBundle.getTransport();
            jsonObject = new JSONObject();
            if (id != null)
                jsonObject.put("id", id);
            if (transport != null)
                jsonObject.put(transport
                                .getElementName(),
                        serializeTransport(transport));
        }
        return jsonObject;
    }

    public static JSONObject serializeEndpoint(ColibriConferenceIQ.Endpoint endpoint) {
        JSONObject jsonObject;
        if (endpoint == null) {
            jsonObject = null;
        } else {
            String id = endpoint.getId();
            String statsId = endpoint.getStatsId();
            String displayName = endpoint.getDisplayName();
            jsonObject = new JSONObject();
            if (id != null)
                jsonObject.put("id", id);
            if (statsId != null)
                jsonObject.put("stats-id", statsId);
            if (displayName != null)
                jsonObject.put("displayname", displayName);
        }
        return jsonObject;
    }

    public static JSONArray serializeChannelBundles(Collection<ColibriConferenceIQ.ChannelBundle> channelBundles) {
        JSONArray jsonArray;
        if (channelBundles == null) {
            jsonArray = null;
        } else {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.ChannelBundle channelBundle : channelBundles)
                jsonArray.add(serializeChannelBundle(channelBundle));
        }
        return jsonArray;
    }

    public static JSONArray serializeEndpoints(Collection<ColibriConferenceIQ.Endpoint> endpoints) {
        JSONArray jsonArray;
        if (endpoints == null) {
            jsonArray = null;
        } else {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.Endpoint endpoint : endpoints)
                jsonArray.add(serializeEndpoint(endpoint));
        }
        return jsonArray;
    }

    public static JSONObject serializeChannelCommon(ColibriConferenceIQ.ChannelCommon channelCommon) {
        JSONObject jsonObject;
        if (channelCommon == null) {
            jsonObject = null;
        } else {
            String id = channelCommon.getID();
            String channelBundleId = channelCommon.getChannelBundleId();
            String endpoint = channelCommon.getEndpoint();
            int expire = channelCommon.getExpire();
            Boolean initiator = channelCommon.isInitiator();
            IceUdpTransportPacketExtension transport = channelCommon.getTransport();
            jsonObject = new JSONObject();
            if (id != null)
                jsonObject.put("id", id);
            if (channelBundleId != null)
                jsonObject.put("channel-bundle-id", channelBundleId);
            if (endpoint != null)
                jsonObject.put("endpoint", endpoint);
            if (expire >= 0)
                jsonObject.put("expire",

                        Integer.valueOf(expire));
            if (initiator != null)
                jsonObject.put("initiator", initiator);
            if (transport != null)
                jsonObject.put(transport
                                .getElementName(),
                        serializeTransport(transport));
        }
        return jsonObject;
    }

    public static JSONArray serializeChannels(Collection<ColibriConferenceIQ.Channel> collection) {
        JSONArray jsonArray;
        if (collection == null) {
            jsonArray = null;
        } else {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.Channel element : collection)
                jsonArray.add(serializeChannel(element));
        }
        return jsonArray;
    }

    public static JSONObject serializeConference(ColibriConferenceIQ conference) {
        JSONObject jsonObject;
        if (conference == null) {
            jsonObject = null;
        } else {
            String id = conference.getID();
            List<ColibriConferenceIQ.Content> contents = conference.getContents();
            List<ColibriConferenceIQ.ChannelBundle> channelBundles = conference.getChannelBundles();
            List<ColibriConferenceIQ.Endpoint> endpoints = conference.getEndpoints();
            ColibriConferenceIQ.Recording recording = conference.getRecording();
            boolean isGracefulShutdown = conference.isGracefulShutdown();
            jsonObject = new JSONObject();
            if (id != null)
                jsonObject.put("id", id);
            if (contents != null && !contents.isEmpty())
                jsonObject.put("contents", serializeContents(contents));
            if (channelBundles != null && !channelBundles.isEmpty())
                jsonObject.put("channel-bundles",

                        serializeChannelBundles(channelBundles));
            if (endpoints != null && !endpoints.isEmpty())
                jsonObject.put("endpoints",

                        serializeEndpoints(endpoints));
            if (recording != null)
                jsonObject.put("recording",
                        serializeRecording(recording));
            if (isGracefulShutdown)
                jsonObject.put("graceful-shutdown", "true");
        }
        return jsonObject;
    }

    public static JSONArray serializeConferences(Collection<ColibriConferenceIQ> conferences) {
        JSONArray conferencesJSONArray;
        if (conferences == null) {
            conferencesJSONArray = null;
        } else {
            conferencesJSONArray = new JSONArray();
            for (ColibriConferenceIQ conference : conferences)
                conferencesJSONArray.add(serializeConference(conference));
        }
        return conferencesJSONArray;
    }

    public static JSONObject serializeContent(ColibriConferenceIQ.Content content) {
        JSONObject jsonObject;
        if (content == null) {
            jsonObject = null;
        } else {
            String name = content.getName();
            List<ColibriConferenceIQ.Channel> channels = content.getChannels();
            List<ColibriConferenceIQ.SctpConnection> sctpConnections = content.getSctpConnections();
            jsonObject = new JSONObject();
            if (name != null)
                jsonObject.put("name", name);
            if (channels != null && !channels.isEmpty())
                jsonObject.put("channels", serializeChannels(channels));
            if (sctpConnections != null && !sctpConnections.isEmpty())
                jsonObject.put("sctpconnections",

                        serializeSctpConnections(sctpConnections));
        }
        return jsonObject;
    }

    public static JSONArray serializeContents(Collection<ColibriConferenceIQ.Content> contents) {
        JSONArray jsonArray;
        if (contents == null) {
            jsonArray = null;
        } else {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.Content content : contents)
                jsonArray.add(serializeContent(content));
        }
        return jsonArray;
    }

    public static JSONObject serializeFingerprint(DtlsFingerprintPacketExtension fingerprint) {
        JSONObject fingerprintJSONObject;
        if (fingerprint == null) {
            fingerprintJSONObject = null;
        } else {
            String theFingerprint = fingerprint.getFingerprint();
            fingerprintJSONObject = new JSONObject();
            if (theFingerprint != null)
                fingerprintJSONObject.put(fingerprint
                        .getElementName(), theFingerprint);
            serializeAbstractPacketExtensionAttributes((AbstractPacketExtension)fingerprint, fingerprintJSONObject);
        }
        return fingerprintJSONObject;
    }

    public static JSONArray serializeFingerprints(Collection<DtlsFingerprintPacketExtension> fingerprints) {
        JSONArray fingerprintsJSONArray;
        if (fingerprints == null) {
            fingerprintsJSONArray = null;
        } else {
            fingerprintsJSONArray = new JSONArray();
            for (DtlsFingerprintPacketExtension fingerprint : fingerprints)
                fingerprintsJSONArray.add(serializeFingerprint(fingerprint));
        }
        return fingerprintsJSONArray;
    }

    public static JSONObject serializeParameters(Collection<ParameterPacketExtension> parameters) {
        JSONObject parametersJSONObject;
        if (parameters == null) {
            parametersJSONObject = null;
        } else {
            parametersJSONObject = new JSONObject();
            for (ParameterPacketExtension parameter : parameters) {
                String name = parameter.getName();
                String value = parameter.getValue();
                if (name != null || value != null)
                    parametersJSONObject.put(name, value);
            }
        }
        return parametersJSONObject;
    }

    public static JSONArray serializeRtcpFbs(@NotNull Collection<RtcpFbPacketExtension> rtcpFbs) {
        JSONArray rtcpFbsJSON = new JSONArray();
        for (RtcpFbPacketExtension ext : rtcpFbs) {
            String type = ext.getFeedbackType();
            String subtype = ext.getFeedbackSubtype();
            if (type != null) {
                JSONObject rtcpFbJSON = new JSONObject();
                rtcpFbJSON.put("type", type);
                if (subtype != null)
                    rtcpFbJSON.put("subtype", subtype);
                rtcpFbsJSON.add(rtcpFbJSON);
            }
        }
        return rtcpFbsJSON;
    }

    public static JSONObject serializePayloadType(PayloadTypePacketExtension payloadType) {
        JSONObject payloadTypeJSONObject;
        if (payloadType == null) {
            payloadTypeJSONObject = null;
        } else {
            List<ParameterPacketExtension> parameters = payloadType.getParameters();
            payloadTypeJSONObject = new JSONObject();
            serializeAbstractPacketExtensionAttributes((AbstractPacketExtension)payloadType, payloadTypeJSONObject);
            if (parameters != null && !parameters.isEmpty())
                payloadTypeJSONObject.put("parameters",

                        serializeParameters(parameters));
            List<RtcpFbPacketExtension> rtcpFeedbackTypeList = payloadType.getRtcpFeedbackTypeList();
            if (rtcpFeedbackTypeList != null &&
                    !rtcpFeedbackTypeList.isEmpty())
                payloadTypeJSONObject.put("rtcp-fbs",

                        serializeRtcpFbs(rtcpFeedbackTypeList));
        }
        return payloadTypeJSONObject;
    }

    public static JSONObject serializeRecording(ColibriConferenceIQ.Recording recording) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("state", recording
                .getState().toString());
        String token = recording.getToken();
        if (token != null)
            jsonObject.put("token", token);
        String directory = recording.getDirectory();
        if (directory != null)
            jsonObject.put("directory", directory);
        return jsonObject;
    }

    public static JSONArray serializePayloadTypes(Collection<PayloadTypePacketExtension> payloadTypes) {
        JSONArray payloadTypesJSONArray;
        if (payloadTypes == null) {
            payloadTypesJSONArray = null;
        } else {
            payloadTypesJSONArray = new JSONArray();
            for (PayloadTypePacketExtension payloadType : payloadTypes)
                payloadTypesJSONArray.add(serializePayloadType(payloadType));
        }
        return payloadTypesJSONArray;
    }

    public static JSONObject serializeSctpConnection(ColibriConferenceIQ.SctpConnection sctpConnection) {
        JSONObject jsonObject;
        if (sctpConnection == null) {
            jsonObject = null;
        } else {
            int port = sctpConnection.getPort();
            jsonObject = serializeChannelCommon((ColibriConferenceIQ.ChannelCommon)sctpConnection);
            jsonObject.put("port",

                    Integer.valueOf(port));
        }
        return jsonObject;
    }

    public static JSONArray serializeSctpConnections(Collection<ColibriConferenceIQ.SctpConnection> collection) {
        JSONArray jsonArray;
        if (collection == null) {
            jsonArray = null;
        } else {
            jsonArray = new JSONArray();
            for (ColibriConferenceIQ.SctpConnection element : collection)
                jsonArray.add(serializeSctpConnection(element));
        }
        return jsonArray;
    }

    public static Long serializeSource(SourcePacketExtension source) {
        return (source == null) ? null : Long.valueOf(source.getSSRC());
    }

    private static Object serializeSourceGroup(SourceGroupPacketExtension sourceGroup) {
        if (sourceGroup.getSemantics() != null && sourceGroup
                .getSemantics().length() != 0 && sourceGroup
                .getSources() != null && sourceGroup
                .getSources().size() != 0) {
            JSONObject sourceGroupJSONObject = new JSONObject();
            sourceGroupJSONObject.put("semantics",

                    JSONValue.escape(sourceGroup.getSemantics()));
            JSONArray ssrcsJSONArray = new JSONArray();
            for (SourcePacketExtension source : sourceGroup.getSources())
                ssrcsJSONArray.add(Long.valueOf(source.getSSRC()));
            sourceGroupJSONObject.put("sources", ssrcsJSONArray);
            return sourceGroupJSONObject;
        }
        return null;
    }

    public static JSONArray serializeSourceGroups(Collection<SourceGroupPacketExtension> sourceGroups) {
        JSONArray sourceGroupsJSONArray;
        if (sourceGroups == null || sourceGroups.size() == 0) {
            sourceGroupsJSONArray = null;
        } else {
            sourceGroupsJSONArray = new JSONArray();
            for (SourceGroupPacketExtension sourceGroup : sourceGroups)
                sourceGroupsJSONArray.add(serializeSourceGroup(sourceGroup));
        }
        return sourceGroupsJSONArray;
    }

    public static JSONArray serializeSources(Collection<SourcePacketExtension> sources) {
        JSONArray sourcesJSONArray;
        if (sources == null) {
            sourcesJSONArray = null;
        } else {
            sourcesJSONArray = new JSONArray();
            for (SourcePacketExtension source : sources)
                sourcesJSONArray.add(serializeSource(source));
        }
        return sourcesJSONArray;
    }

    public static JSONArray serializeSSRCs(int[] ssrcs) {
        JSONArray ssrcsJSONArray;
        if (ssrcs == null) {
            ssrcsJSONArray = null;
        } else {
            ssrcsJSONArray = new JSONArray();
            for (int i = 0; i < ssrcs.length; i++)
                ssrcsJSONArray.add(Long.valueOf(ssrcs[i] & 0xFFFFFFFFL));
        }
        return ssrcsJSONArray;
    }

    public static JSONObject serializeStatistics(Statistics statistics) {
        JSONObject statisticsJSONObject;
        if (statistics == null) {
            statisticsJSONObject = null;
        } else {
            statisticsJSONObject = new JSONObject(statistics.getStats());
        }
        return statisticsJSONObject;
    }

    public static JSONObject serializeTransport(IceUdpTransportPacketExtension transport) {
        JSONObject jsonObject;
        if (transport == null) {
            jsonObject = null;
        } else {
            String xmlns = transport.getNamespace();
            List<DtlsFingerprintPacketExtension> fingerprints = transport.getChildExtensionsOfType(DtlsFingerprintPacketExtension.class);
            List<CandidatePacketExtension> candidateList = transport.getCandidateList();
            List<WebSocketPacketExtension> webSocketList = transport.getChildExtensionsOfType(WebSocketPacketExtension.class);
            RemoteCandidatePacketExtension remoteCandidate = transport.getRemoteCandidate();
            boolean rtcpMux = transport.isRtcpMux();
            jsonObject = new JSONObject();
            if (xmlns != null)
                jsonObject.put("xmlns", xmlns);
            serializeAbstractPacketExtensionAttributes((AbstractPacketExtension)transport, jsonObject);
            if (fingerprints != null && !fingerprints.isEmpty())
                jsonObject.put("fingerprints",

                        serializeFingerprints(fingerprints));
            if (candidateList != null && !candidateList.isEmpty())
                jsonObject.put("candidates",

                        serializeCandidates(candidateList));
            if (remoteCandidate != null)
                jsonObject.put(remoteCandidate
                                .getElementName(),
                        serializeCandidate((CandidatePacketExtension)remoteCandidate));
            if (webSocketList != null && !webSocketList.isEmpty())
                jsonObject.put("web-sockets",

                        serializeWebSockets(webSocketList));
            if (rtcpMux)
                jsonObject.put("rtcp-mux",

                        Boolean.valueOf(rtcpMux));
        }
        return jsonObject;
    }

    private static String serializeWebSocket(WebSocketPacketExtension webSocket) {
        return webSocket.getUrl();
    }

    private static JSONArray serializeWebSockets(List<WebSocketPacketExtension> webSocketList) {
        JSONArray webSocketsJSONArray;
        if (webSocketList == null) {
            webSocketsJSONArray = null;
        } else {
            webSocketsJSONArray = new JSONArray();
            for (WebSocketPacketExtension webSocket : webSocketList)
                webSocketsJSONArray.add(serializeWebSocket(webSocket));
        }
        return webSocketsJSONArray;
    }
}
