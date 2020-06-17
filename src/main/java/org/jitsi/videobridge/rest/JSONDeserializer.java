package org.jitsi.videobridge.rest;

import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jitsi.utils.StringUtils;
import org.jitsi.xmpp.extensions.AbstractPacketExtension;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.colibri.ShutdownIQ;
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
import org.jitsi.xmpp.extensions.jingle.CandidatePacketExtension;
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension;
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension;
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension;
import org.jitsi.xmpp.extensions.jingle.RawUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.RemoteCandidatePacketExtension;
import org.jitsi.xmpp.extensions.jingle.RtcpFbPacketExtension;
import org.jitsi.xmpp.extensions.jingle.RtcpmuxPacketExtension;
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

final class JSONDeserializer {
    public static void deserializeAbstractPacketExtensionAttributes(JSONObject jsonObject, AbstractPacketExtension abstractPacketExtension) {
        for (Map.Entry<Object, Object> e : (Iterable<Map.Entry<Object, Object>>)jsonObject

                .entrySet()) {
            Object key = e.getKey();
            if (key != null) {
                String name = key.toString();
                if (name != null) {
                    Object value = e.getValue();
                    if (!(value instanceof JSONObject) && !(value instanceof JSONArray))
                        abstractPacketExtension.setAttribute(name, value);
                }
            }
        }
    }

    public static <T extends CandidatePacketExtension> T deserializeCandidate(
            JSONObject candidate,
            Class<T> candidateIQClass,
            IceUdpTransportPacketExtension transportIQ)
    {
        T candidateIQ;

        if (candidate == null)
        {
            candidateIQ = null;
        }
        else
        {
            try
            {
                candidateIQ = candidateIQClass.newInstance();
            }
            catch (IllegalAccessException | InstantiationException iae)
            {
                throw new UndeclaredThrowableException(iae);
            }
            // attributes
            deserializeAbstractPacketExtensionAttributes(
                    candidate,
                    candidateIQ);

            transportIQ.addChildExtension(candidateIQ);
        }
        return candidateIQ;
    }

    public static void deserializeCandidates(JSONArray candidates, IceUdpTransportPacketExtension transportIQ) {
        if (candidates != null && !candidates.isEmpty())
            for (Object candidate : candidates)
                deserializeCandidate((JSONObject)candidate, CandidatePacketExtension.class, transportIQ);
    }

    public static ColibriConferenceIQ.Channel deserializeChannel(JSONObject channel, ColibriConferenceIQ.Content contentIQ) {
        ColibriConferenceIQ.Channel channelIQ;
        if (channel == null) {
            channelIQ = null;
        } else {
            Object direction = channel.get("direction");
            Object lastN = channel.get("last-n");
            Object receivingSimulcastStream = channel.get("receive-simulcast-layer");
            Object payloadTypes = channel.get("payload-types");
            Object rtpLevelRelayType = channel.get("rtp-level-relay-type");
            Object sources = channel.get("sources");
            Object sourceGroups = channel.get("ssrc-groups");
            Object ssrcs = channel.get("ssrcs");
            Object headerExtensions = channel.get("rtp-hdrexts");
            channelIQ = new ColibriConferenceIQ.Channel();
            deserializeChannelCommon(channel, (ColibriConferenceIQ.ChannelCommon)channelIQ);
            if (direction != null)
                channelIQ.setDirection(direction.toString());
            if (lastN != null)
                channelIQ.setLastN(objectToInteger(lastN));
            if (receivingSimulcastStream != null)
                channelIQ.setReceivingSimulcastLayer(
                        objectToInteger(receivingSimulcastStream));
            if (payloadTypes != null)
                deserializePayloadTypes((JSONArray)payloadTypes, channelIQ);
            if (rtpLevelRelayType != null)
                channelIQ.setRTPLevelRelayType(rtpLevelRelayType.toString());
            if (sources != null)
                deserializeSources((JSONArray)sources, channelIQ);
            if (sourceGroups != null)
                deserializeSourceGroups((JSONArray)sourceGroups, channelIQ);
            if (ssrcs != null)
                deserializeSSRCs((JSONArray)ssrcs, channelIQ);
            if (headerExtensions != null)
                deserializeHeaderExtensions((JSONArray)headerExtensions, channelIQ);
            contentIQ.addChannel(channelIQ);
        }
        return channelIQ;
    }

    private static Integer objectToInteger(Object o) {
        Integer i;
        if (o instanceof Integer) {
            i = (Integer)o;
        } else if (o instanceof Number) {
            i = Integer.valueOf(((Number)o).intValue());
        } else {
            i = Integer.valueOf(o.toString());
        }
        return i;
    }

    private static Boolean objectToBoolean(Object o) {
        if (o instanceof Boolean)
            return (Boolean)o;
        return Boolean.valueOf(o.toString());
    }

    public static ColibriConferenceIQ.ChannelBundle deserializeChannelBundle(JSONObject channelBundle, ColibriConferenceIQ conferenceIQ) {
        ColibriConferenceIQ.ChannelBundle channelBundleIQ;
        if (channelBundle == null) {
            channelBundleIQ = null;
        } else {
            Object id = channelBundle.get("id");
            Object transport = channelBundle.get("transport");
            channelBundleIQ = new ColibriConferenceIQ.ChannelBundle((id == null) ? null : id.toString());
            if (transport != null)
                deserializeTransport((JSONObject)transport, channelBundleIQ);
            conferenceIQ.addChannelBundle(channelBundleIQ);
        }
        return channelBundleIQ;
    }

    public static ColibriConferenceIQ.Endpoint deserializeEndpoint(JSONObject endpoint, ColibriConferenceIQ conferenceIQ) {
        ColibriConferenceIQ.Endpoint endpointIQ;
        if (endpoint == null) {
            endpointIQ = null;
        } else {
            Object id = endpoint.get("id");
            Object statsId = endpoint.get("stats-id");
            Object displayName = endpoint.get("displayname");
            endpointIQ = new ColibriConferenceIQ.Endpoint(Objects.toString(id, null), Objects.toString(statsId, null), Objects.toString(displayName, null));
            conferenceIQ.addEndpoint(endpointIQ);
        }
        return endpointIQ;
    }

    public static void deserializeChannelBundles(JSONArray channelBundles, ColibriConferenceIQ conferenceIQ) {
        if (channelBundles != null && !channelBundles.isEmpty())
            for (Object channelBundle : channelBundles)
                deserializeChannelBundle((JSONObject)channelBundle, conferenceIQ);
    }

    public static void deserializeEndpoints(JSONArray endpoints, ColibriConferenceIQ conferenceIQ) {
        if (endpoints != null && !endpoints.isEmpty())
            for (Object endpoint : endpoints)
                deserializeEndpoint((JSONObject)endpoint, conferenceIQ);
    }

    public static void deserializeChannelCommon(JSONObject channel, ColibriConferenceIQ.ChannelCommon channelIQ) {
        Object id = channel.get("id");
        Object channelBundleId = channel.get("channel-bundle-id");
        Object endpoint = channel.get("endpoint");
        Object expire = channel.get("expire");
        Object initiator = channel.get("initiator");
        Object transport = channel.get("transport");
        if (id != null)
            channelIQ.setID(id.toString());
        if (channelBundleId != null)
            channelIQ.setChannelBundleId(channelBundleId.toString());
        if (endpoint != null)
            channelIQ.setEndpoint(endpoint.toString());
        if (expire != null) {
            int i = objectToInteger(expire).intValue();
            if (i != -1)
                channelIQ.setExpire(i);
        }
        if (initiator != null)
            channelIQ.setInitiator(objectToBoolean(initiator));
        if (transport != null)
            deserializeTransport((JSONObject)transport, channelIQ);
    }

    public static void deserializeChannels(JSONArray channels, ColibriConferenceIQ.Content contentIQ) {
        if (channels != null && !channels.isEmpty())
            for (Object channel : channels)
                deserializeChannel((JSONObject)channel, contentIQ);
    }

    public static ColibriConferenceIQ deserializeConference(JSONObject conference) {
        ColibriConferenceIQ conferenceIQ;
        if (conference == null) {
            conferenceIQ = null;
        } else {
            Object id = conference.get("id");
            Object contents = conference.get("contents");
            Object channelBundles = conference.get("channel-bundles");
            Object endpoints = conference.get("endpoints");
            Object recording = conference.get("recording");
            Object strategy = conference.get("rtcp-termination-strategy");
            Object shutdownExt = conference.get("graceful-shutdown");
            conferenceIQ = new ColibriConferenceIQ();
            if (id != null)
                conferenceIQ.setID(id.toString());
            if (contents != null)
                deserializeContents((JSONArray)contents, conferenceIQ);
            if (channelBundles != null)
                deserializeChannelBundles((JSONArray)channelBundles, conferenceIQ);
            if (endpoints != null)
                deserializeEndpoints((JSONArray)endpoints, conferenceIQ);
            if (recording != null)
                deserializeRecording((JSONObject)recording, conferenceIQ);
            if (strategy != null)
                deserializeRTCPTerminationStrategy((JSONObject)strategy, conferenceIQ);
            if (shutdownExt != null)
                conferenceIQ.setGracefulShutdown(true);
        }
        return conferenceIQ;
    }

    private static void deserializeRTCPTerminationStrategy(JSONObject strategy, ColibriConferenceIQ conferenceIQ) {
        if ((((strategy != null) ? 1 : 0) & ((conferenceIQ != null) ? 1 : 0)) != 0) {
            Object attrName = strategy.get("name");
            String name = Objects.toString(attrName, null);
            if (StringUtils.isNullOrEmpty(name))
                return;
            ColibriConferenceIQ.RTCPTerminationStrategy strategyIQ = new ColibriConferenceIQ.RTCPTerminationStrategy();
            strategyIQ.setName(name);
            conferenceIQ.setRTCPTerminationStrategy(strategyIQ);
        }
    }

    public static ColibriConferenceIQ.Content deserializeContent(JSONObject content, ColibriConferenceIQ conferenceIQ) {
        ColibriConferenceIQ.Content contentIQ;
        if (content == null) {
            contentIQ = null;
        } else {
            Object name = content.get("name");
            Object channels = content.get("channels");
            Object sctpConnections = content.get("sctpconnections");
            contentIQ = conferenceIQ.getOrCreateContent(Objects.toString(name, null));
            if (channels != null)
                deserializeChannels((JSONArray)channels, contentIQ);
            if (sctpConnections != null)
                deserializeSctpConnections((JSONArray)sctpConnections, contentIQ);
            conferenceIQ.addContent(contentIQ);
        }
        return contentIQ;
    }

    public static void deserializeContents(JSONArray contents, ColibriConferenceIQ conferenceIQ) {
        if (contents != null && !contents.isEmpty())
            for (Object content : contents)
                deserializeContent((JSONObject)content, conferenceIQ);
    }

    public static DtlsFingerprintPacketExtension deserializeFingerprint(JSONObject fingerprint, IceUdpTransportPacketExtension transportIQ) {
        DtlsFingerprintPacketExtension fingerprintIQ;
        if (fingerprint == null) {
            fingerprintIQ = null;
        } else {
            Object theFingerprint = fingerprint.get("fingerprint");
            fingerprintIQ = new DtlsFingerprintPacketExtension();
            if (theFingerprint != null)
                fingerprintIQ.setFingerprint(theFingerprint.toString());
            deserializeAbstractPacketExtensionAttributes(fingerprint, (AbstractPacketExtension)fingerprintIQ);
            fingerprintIQ.removeAttribute("fingerprint");
            transportIQ.addChildExtension((ExtensionElement)fingerprintIQ);
        }
        return fingerprintIQ;
    }

    public static void deserializeFingerprints(JSONArray fingerprints, IceUdpTransportPacketExtension transportIQ) {
        if (fingerprints != null && !fingerprints.isEmpty())
            for (Object fingerprint : fingerprints)
                deserializeFingerprint((JSONObject)fingerprint, transportIQ);
    }

    public static void deserializeParameters(JSONObject parameters, PayloadTypePacketExtension payloadTypeIQ) {
        if (parameters != null)
            for (Map.Entry<Object, Object> e : (Iterable<Map.Entry<Object, Object>>)parameters
                    .entrySet()) {
                Object name = e.getKey();
                Object value = e.getValue();
                if (name != null || value != null)
                    payloadTypeIQ.addParameter(new ParameterPacketExtension(

                            Objects.toString(name, null),
                            Objects.toString(value, null)));
            }
    }

    public static void deserializeRtcpFbs(JSONArray rtcpFbs, PayloadTypePacketExtension payloadTypeIQ) {
        if (rtcpFbs != null)
            for (Object iter : rtcpFbs) {
                JSONObject rtcpFb = (JSONObject)iter;
                String type = (String)rtcpFb.get("type");
                String subtype = (String)rtcpFb.get("subtype");
                if (type != null) {
                    RtcpFbPacketExtension ext = new RtcpFbPacketExtension();
                    ext.setFeedbackType(type);
                    if (subtype != null)
                        ext.setFeedbackSubtype(subtype);
                    payloadTypeIQ.addRtcpFeedbackType(ext);
                }
            }
    }

    public static RTPHdrExtPacketExtension deserializeHeaderExtension(JSONObject headerExtension, ColibriConferenceIQ.Channel channelIQ) {
        RTPHdrExtPacketExtension headerExtensionIQ;
        if (headerExtension == null) {
            headerExtensionIQ = null;
        } else {
            URI uri;
            Long id = (Long)headerExtension.get("id");
            String uriString = (String)headerExtension.get("uri");
            try {
                uri = new URI(uriString);
            } catch (URISyntaxException e) {
                uri = null;
            }
            if (uri != null) {
                headerExtensionIQ = new RTPHdrExtPacketExtension();
                headerExtensionIQ.setID(String.valueOf(id));
                headerExtensionIQ.setURI(uri);
                channelIQ.addRtpHeaderExtension(headerExtensionIQ);
            } else {
                headerExtensionIQ = null;
            }
        }
        return headerExtensionIQ;
    }

    public static void deserializeHeaderExtensions(JSONArray headerExtensions, ColibriConferenceIQ.Channel channelIQ) {
        if (headerExtensions != null && !headerExtensions.isEmpty())
            for (Object headerExtension : headerExtensions)
                deserializeHeaderExtension((JSONObject)headerExtension, channelIQ);
    }

    public static PayloadTypePacketExtension deserializePayloadType(JSONObject payloadType, ColibriConferenceIQ.Channel channelIQ) {
        PayloadTypePacketExtension payloadTypeIQ;
        if (payloadType == null) {
            payloadTypeIQ = null;
        } else {
            Object parameters = payloadType.get("parameters");
            payloadTypeIQ = new PayloadTypePacketExtension();
            deserializeAbstractPacketExtensionAttributes(payloadType, (AbstractPacketExtension)payloadTypeIQ);
            if (parameters != null)
                deserializeParameters((JSONObject)parameters, payloadTypeIQ);
            Object rtcpFbs = payloadType.get("rtcp-fbs");
            if (rtcpFbs != null && rtcpFbs instanceof JSONArray)
                deserializeRtcpFbs((JSONArray)rtcpFbs, payloadTypeIQ);
            channelIQ.addPayloadType(payloadTypeIQ);
        }
        return payloadTypeIQ;
    }

    public static void deserializePayloadTypes(JSONArray payloadTypes, ColibriConferenceIQ.Channel channelIQ) {
        if (payloadTypes != null && !payloadTypes.isEmpty())
            for (Object payloadType : payloadTypes)
                deserializePayloadType((JSONObject)payloadType, channelIQ);
    }

    public static void deserializeRecording(JSONObject recording, ColibriConferenceIQ conferenceIQ) {
        Object state = recording.get("state");
        if (state == null)
            return;
        ColibriConferenceIQ.Recording recordingIQ = new ColibriConferenceIQ.Recording(state.toString());
        Object token = recording.get("token");
        if (token != null)
            recordingIQ.setToken(token.toString());
        Object directory = recording.get("directory");
        if (directory != null)
            recordingIQ.setDirectory(directory.toString());
        conferenceIQ.setRecording(recordingIQ);
    }

    public static ColibriConferenceIQ.SctpConnection deserializeSctpConnection(JSONObject sctpConnection, ColibriConferenceIQ.Content contentIQ) {
        ColibriConferenceIQ.SctpConnection sctpConnectionIQ;
        if (sctpConnection == null) {
            sctpConnectionIQ = null;
        } else {
            Object port = sctpConnection.get("port");
            sctpConnectionIQ = new ColibriConferenceIQ.SctpConnection();
            deserializeChannelCommon(sctpConnection, (ColibriConferenceIQ.ChannelCommon)sctpConnectionIQ);
            if (port != null)
                sctpConnectionIQ.setPort(objectToInteger(port).intValue());
            contentIQ.addSctpConnection(sctpConnectionIQ);
        }
        return sctpConnectionIQ;
    }

    public static void deserializeSctpConnections(JSONArray sctpConnections, ColibriConferenceIQ.Content contentIQ) {
        if (sctpConnections != null && !sctpConnections.isEmpty())
            for (Object sctpConnection : sctpConnections)
                deserializeSctpConnection((JSONObject)sctpConnection, contentIQ);
    }

    public static ShutdownIQ deserializeShutdownIQ(
            JSONObject requestJSONObject)
    {
        String element = (String) requestJSONObject.keySet().iterator().next();

        return ShutdownIQ.isValidElementName(element) ?
                ShutdownIQ.createShutdownIQ(element) : null;
    }

    public static SourcePacketExtension deserializeSource(Object source) {
        SourcePacketExtension sourceIQ;
        if (source == null) {
            sourceIQ = null;
        } else {
            long ssrc;
            try {
                ssrc = deserializeSSRC(source);
            } catch (NumberFormatException nfe) {
                ssrc = -1L;
            }
            if (ssrc == -1L) {
                sourceIQ = null;
            } else {
                sourceIQ = new SourcePacketExtension();
                sourceIQ.setSSRC(ssrc);
            }
        }
        return sourceIQ;
    }

    public static SourcePacketExtension deserializeSource(Object source, ColibriConferenceIQ.Channel channelIQ) {
        SourcePacketExtension sourcePacketExtension = deserializeSource(source);
        if (sourcePacketExtension != null)
            channelIQ.addSource(sourcePacketExtension);
        return sourcePacketExtension;
    }

    public static SourceGroupPacketExtension deserializeSourceGroup(Object sourceGroup, ColibriConferenceIQ.Channel channelIQ) {
        SourceGroupPacketExtension sourceGroupIQ;
        if (sourceGroup == null || !(sourceGroup instanceof JSONObject)) {
            sourceGroupIQ = null;
        } else {
            JSONObject sourceGroupJSONObject = (JSONObject)sourceGroup;
            Object semantics = sourceGroupJSONObject.get("semantics");
            if (semantics != null && semantics instanceof String && ((String)semantics)

                    .length() != 0) {
                Object sourcesObject = sourceGroupJSONObject.get("sources");
                if (sourcesObject != null && sourcesObject instanceof JSONArray && ((JSONArray)sourcesObject)

                        .size() != 0) {
                    JSONArray sourcesJSONArray = (JSONArray)sourcesObject;
                    List<SourcePacketExtension> sourcePacketExtensions = new ArrayList<>();
                    for (Object source : sourcesJSONArray) {
                        SourcePacketExtension sourcePacketExtension = deserializeSource(source);
                        if (sourcePacketExtension != null)
                            sourcePacketExtensions.add(sourcePacketExtension);
                    }
                    sourceGroupIQ = new SourceGroupPacketExtension();
                    sourceGroupIQ.setSemantics(Objects.toString(semantics));
                    sourceGroupIQ.addSources(sourcePacketExtensions);
                    channelIQ.addSourceGroup(sourceGroupIQ);
                } else {
                    sourceGroupIQ = null;
                }
            } else {
                sourceGroupIQ = null;
            }
        }
        return sourceGroupIQ;
    }

    public static void deserializeSourceGroups(JSONArray sourceGroups, ColibriConferenceIQ.Channel channelIQ) {
        if (sourceGroups != null && !sourceGroups.isEmpty())
            for (Object sourceGroup : sourceGroups)
                deserializeSourceGroup(sourceGroup, channelIQ);
    }

    public static void deserializeSources(JSONArray sources, ColibriConferenceIQ.Channel channelIQ) {
        if (sources != null && !sources.isEmpty())
            for (Object source : sources)
                deserializeSource(source, channelIQ);
    }

    public static int deserializeSSRC(Object o) throws NumberFormatException {
        int i = 0;
        if (o != null)
            if (o instanceof Number) {
                i = ((Number)o).intValue();
            } else {
                String s = o.toString();
                if (s.startsWith("-")) {
                    i = Integer.parseInt(s);
                } else {
                    i = (int)Long.parseLong(s);
                }
            }
        return i;
    }

    public static void deserializeSSRCs(JSONArray ssrcs, ColibriConferenceIQ.Channel channelIQ) {
        if (ssrcs != null && !ssrcs.isEmpty())
            for (Object ssrc : ssrcs) {
                int ssrcIQ;
                try {
                    ssrcIQ = deserializeSSRC(ssrc);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                channelIQ.addSSRC(ssrcIQ);
            }
    }

    public static IceUdpTransportPacketExtension deserializeTransport(JSONObject transport) {
        IceUdpTransportPacketExtension transportIQ = null;
        if (transport == null) {
            transportIQ = null;
        } else {
            Object xmlns = transport.get("xmlns");
            Object fingerprints = transport.get("fingerprints");
            Object candidateList = transport.get("candidates");
            Object remoteCandidate = transport.get("remote-candidate");
            Object rtcpMux = transport.get("rtcp-mux");
            if ("urn:xmpp:jingle:transports:ice-udp:1".equals(xmlns)) {
                transportIQ = new IceUdpTransportPacketExtension();
            } else if ("urn:xmpp:jingle:transports:raw-udp:1".equals(xmlns)) {
                RawUdpTransportPacketExtension rawUdpTransportPacketExtension = new RawUdpTransportPacketExtension();
            } else {
                transportIQ = null;
            }
            if (transportIQ != null) {
                deserializeAbstractPacketExtensionAttributes(transport, (AbstractPacketExtension)transportIQ);
                if (fingerprints != null)
                    deserializeFingerprints((JSONArray)fingerprints, transportIQ);
                if (candidateList != null)
                    deserializeCandidates((JSONArray)candidateList, transportIQ);
                if (remoteCandidate != null)
                    deserializeCandidate((JSONObject)remoteCandidate, RemoteCandidatePacketExtension.class, transportIQ);
                if (rtcpMux != null && objectToBoolean(rtcpMux).booleanValue())
                    transportIQ.addChildExtension((ExtensionElement)new RtcpmuxPacketExtension());
            }
        }
        return transportIQ;
    }

    public static IceUdpTransportPacketExtension deserializeTransport(JSONObject transport, ColibriConferenceIQ.ChannelBundle channelBundleIQ) {
        IceUdpTransportPacketExtension transportIQ = deserializeTransport(transport);
        if (transportIQ != null)
            channelBundleIQ.setTransport(transportIQ);
        return transportIQ;
    }

    public static IceUdpTransportPacketExtension deserializeTransport(JSONObject transport, ColibriConferenceIQ.ChannelCommon channelIQ) {
        IceUdpTransportPacketExtension transportIQ = deserializeTransport(transport);
        if (transportIQ != null)
            channelIQ.setTransport(transportIQ);
        return transportIQ;
    }
}
