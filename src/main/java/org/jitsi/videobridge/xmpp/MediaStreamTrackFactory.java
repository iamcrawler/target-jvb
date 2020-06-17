package org.jitsi.videobridge.xmpp;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
import org.jitsi.impl.neomedia.rtp.MediaStreamTrackReceiver;
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.utils.logging.Logger;
import org.jitsi.xmpp.extensions.AbstractPacketExtension;
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension;
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension;

public class MediaStreamTrackFactory {
    private static ConfigurationService cfg = LibJitsi.getConfigurationService();

    private static final Logger logger = Logger.getLogger(MediaStreamTrackFactory.class);

    public static final String ENABLE_SVC_PNAME = "org.jitsi.videobridge.ENABLE_SVC";

    public static final String ENABLE_VP9_SVC_PNAME = "org.jitsi.videobridge.ENABLE_VP9_SVC";

    private static final int VP8_SIMULCAST_TEMPORAL_LAYERS = 3;

    private static final int VP8_SIMULCAST_BASE_LAYER_HEIGHT = 180;

    private static final int VP9_SVC_SPATIAL_LAYERS = 3;

    private static final int VP9_SVC_TEMPORAL_LAYERS = 3;

    private static final boolean ENABLE_VP9_SVC = cfg
            .getBoolean("org.jitsi.videobridge.ENABLE_VP9_SVC", false);

    private static final Boolean ENABLE_SVC = Boolean.valueOf(cfg.getBoolean("org.jitsi.videobridge.ENABLE_SVC", false));

    private static Map<String, String> secondarySsrcTypeMap = null;

    private static synchronized Map<String, String> getSecondarySsrcTypeMap() {
        if (secondarySsrcTypeMap == null) {
            secondarySsrcTypeMap = new HashMap<>();
            secondarySsrcTypeMap.put("FID", "rtx");
        }
        return secondarySsrcTypeMap;
    }

    private static RTPEncodingDesc[] createRTPEncodings(MediaStreamTrackDesc track, TrackSsrcs primary, int spatialLen, int temporalLen, Map<Long, SecondarySsrcs> secondarySsrcs) {
        RTPEncodingDesc[] rtpEncodings = new RTPEncodingDesc[primary.size() * spatialLen * temporalLen];
        int height = 180;
        for (int streamIdx = 0; streamIdx < primary.size(); streamIdx++) {
            for (int spatialIdx = 0; spatialIdx < spatialLen; spatialIdx++) {
                double frameRate = 30.0D / (1 << temporalLen - 1);
                int temporalIdx = 0;
                for (; temporalIdx < temporalLen; temporalIdx++) {
                    RTPEncodingDesc[] dependencies;
                    int idx = qid(streamIdx, spatialIdx, temporalIdx, spatialLen, temporalLen);
                    if (spatialIdx > 0 && temporalIdx > 0) {
                        dependencies = new RTPEncodingDesc[] { rtpEncodings[qid(streamIdx, spatialIdx, temporalIdx - 1, spatialLen, temporalLen)], rtpEncodings[qid(streamIdx, spatialIdx - 1, temporalIdx, spatialLen, temporalLen)] };
                    } else if (spatialIdx > 0) {
                        dependencies = new RTPEncodingDesc[] { rtpEncodings[qid(streamIdx, spatialIdx - 1, temporalIdx, spatialLen, temporalLen)] };
                    } else if (temporalIdx > 0) {
                        dependencies = new RTPEncodingDesc[] { rtpEncodings[qid(streamIdx, spatialIdx, temporalIdx - 1, spatialLen, temporalLen)] };
                    } else {
                        dependencies = null;
                    }
                    int temporalId = (temporalLen > 1) ? temporalIdx : -1;
                    int spatialId = (spatialLen > 1) ? spatialIdx : -1;
                    rtpEncodings[idx] = new RTPEncodingDesc(track, idx, primary

                            .get(streamIdx).longValue(), temporalId, spatialId, height, frameRate, dependencies);
                    SecondarySsrcs ssrcSecondarySsrcs = secondarySsrcs.get(primary.get(streamIdx));
                    if (ssrcSecondarySsrcs != null)
                        ssrcSecondarySsrcs.forEach(ssrcSecondarySsrc -> {
                            String type = getSecondarySsrcTypeMap().get(ssrcSecondarySsrc.type);
                            if (type == null) {
                                logger.error("Unable to find a mapping for secondary ssrc type " + ssrcSecondarySsrc.type + " will NOT included this secondary ssrc as an encoding");
                            } else {
                                rtpEncodings[idx].addSecondarySsrc(ssrcSecondarySsrc.ssrc, type);
                            }
                        });
                    frameRate *= 2.0D;
                }
            }
            height *= 2;
        }
        return rtpEncodings;
    }

    private static List<SecondarySsrc> getSecondarySsrcs(long ssrc, List<SourceGroupPacketExtension> sourceGroups) {
        List<SecondarySsrc> secondarySsrcs = new ArrayList<>();
        for (SourceGroupPacketExtension sourceGroup : sourceGroups) {
            if (sourceGroup.getSemantics().equalsIgnoreCase("SIM"))
                continue;
            long groupPrimarySsrc = ((SourcePacketExtension)sourceGroup.getSources().get(0)).getSSRC();
            long groupSecondarySsrc = ((SourcePacketExtension)sourceGroup.getSources().get(1)).getSSRC();
            if (groupPrimarySsrc == ssrc)
                secondarySsrcs.add(new SecondarySsrc(groupSecondarySsrc, sourceGroup

                        .getSemantics()));
        }
        return secondarySsrcs;
    }

    private static Map<Long, SecondarySsrcs> getAllSecondarySsrcs(TrackSsrcs ssrcs, List<SourceGroupPacketExtension> sourceGroups) {
        Map<Long, SecondarySsrcs> allSecondarySsrcs = new HashMap<>();
        for (Iterator<Long> iterator = ssrcs.iterator(); iterator.hasNext(); ) {
            long ssrc = ((Long)iterator.next()).longValue();
            List<SecondarySsrc> secondarySsrcs = getSecondarySsrcs(ssrc, sourceGroups);
            allSecondarySsrcs.put(Long.valueOf(ssrc), new SecondarySsrcs(secondarySsrcs));
        }
        return allSecondarySsrcs;
    }

    private static List<SourceGroupPacketExtension> getGroups(String semantics, List<SourceGroupPacketExtension> groups) {
        return (List<SourceGroupPacketExtension>)groups.stream()
                .filter(sg -> sg.getSemantics().equalsIgnoreCase(semantics))
                .collect(Collectors.toList());
    }

    private static void removeReferences(TrackSsrcs trackSsrcs, List<SourcePacketExtension> sources, List<SourceGroupPacketExtension> sourceGroups) {
        List<SourceGroupPacketExtension> groupsToRemove = (List<SourceGroupPacketExtension>)sourceGroups.stream().filter(group -> group.getSources().stream().anyMatch(source -> trackSsrcs.contains(source.getSSRC()))).collect(Collectors.toList());
        sourceGroups.removeAll(groupsToRemove);
        Set<Long> ssrcsToRemove = extractSsrcs(groupsToRemove);
        sources.removeIf(source ->

                (trackSsrcs.contains(Long.valueOf(source.getSSRC())) || ssrcsToRemove.contains(Long.valueOf(source.getSSRC()))));
    }

    private static Set<Long> extractSsrcs(List<SourceGroupPacketExtension> groups) {
        Set<Long> ssrcs = new HashSet<>();
        groups.forEach(group -> group.getSources().forEach(source -> ssrcs.add(source.getSSRC())));
        return ssrcs;
    }

    private static List<TrackSsrcs> getTrackSsrcs(List<SourcePacketExtension> sources, List<SourceGroupPacketExtension> sourceGroups) {
        List<TrackSsrcs> trackSsrcsList = new ArrayList<>();
        List<SourcePacketExtension> sourcesCopy = new ArrayList<>(sources);
        List<SourceGroupPacketExtension> sourceGroupsCopy = new ArrayList<>(sourceGroups);
        Arrays.<String>asList(new String[] { "SIM", "FID", "FEC-FR" }).forEach(groupSem -> {
            List<SourceGroupPacketExtension> groups = getGroups(groupSem, sourceGroupsCopy);
            groups.forEach(group -> {
                // An empty group is the signal that we want to clear all
                // the groups.
                // https://github.com/jitsi/jitsi/blob/7eabaab0fca37711813965d66a0720d1545f6c48/src/net/java/sip/communicator/impl/protocol/jabber/extensions/colibri/ColibriBuilder.java#L188
                if (group.getSources() == null || group.getSources().isEmpty())
                {
                    if (groups.size() > 1)
                    {
                        logger.warn("Received empty group, which is " +
                                "a signal to clear all groups, but there were " +
                                "other groups present, which shouldn't happen");
                    }
                    return;
                }
                List<Long> ssrcs;
                // For a simulcast group, all the ssrcs are considered primary
                // ssrcs, but for others, only the main ssrc of the group is
                if (groupSem.equalsIgnoreCase(
                        SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
                {
                    ssrcs
                            = group.getSources().stream()
                            .map(SourcePacketExtension::getSSRC)
                            .collect(Collectors.toList());
                }
                else
                {
                    ssrcs = Arrays.asList(group.getSources().get(0).getSSRC());
                }

                TrackSsrcs trackSsrcs = new TrackSsrcs(ssrcs);
                // Now we need to remove any groups with these ssrcs as their
                // primary, or sources that correspond to one of these ssrcs
                removeReferences(trackSsrcs, sourcesCopy, sourceGroupsCopy);

                trackSsrcsList.add(trackSsrcs);
            });
        });
        if (!sourceGroupsCopy.isEmpty())
            logger.warn("Unprocessed source groups: " + sourceGroupsCopy

                    .stream()
                    .map(AbstractPacketExtension::toXML)
                    .reduce(String::concat));
        sourcesCopy.forEach(source -> {
            if (source.getSSRC() != -1L) {
                trackSsrcsList.add(new TrackSsrcs(Long.valueOf(source.getSSRC())));
            } else if (sourcesCopy.size() > 1) {
                logger.warn("Received an empty source, which is a signal to clear all sources, but there were other sources present, which shouldn't happen");
            }
        });
        setOwners(sources, trackSsrcsList);
        return trackSsrcsList;
    }

    private static void setOwners(List<SourcePacketExtension> sources, List<TrackSsrcs> trackSsrcsList) {
        for (Iterator<TrackSsrcs> iterator = trackSsrcsList.iterator(); iterator.hasNext(); ) {
            TrackSsrcs trackSsrcs = iterator.next();
            long primarySsrc = trackSsrcs.get(0).longValue();
            SourcePacketExtension trackSource = sources.stream().filter(source -> (source.getSSRC() == primarySsrc)).findAny().orElse(null);
            trackSsrcs.owner = getOwner(trackSource);
        }
    }

    public static String getOwner(SourcePacketExtension source) {
        SSRCInfoPacketExtension ssrcInfoPacketExtension = (source == null) ? null : (SSRCInfoPacketExtension)source.getFirstChildOfType(SSRCInfoPacketExtension.class);
        if (ssrcInfoPacketExtension != null)
            return ssrcInfoPacketExtension
                    .getOwner()
                    .getResourceOrEmpty().toString();
        return null;
    }

    public static MediaStreamTrackDesc[] createMediaStreamTracks(MediaStreamTrackReceiver mediaStreamTrackReceiver, List<SourcePacketExtension> sources, List<SourceGroupPacketExtension> sourceGroups) {
        List<TrackSsrcs> trackSsrcsList = getTrackSsrcs(sources, sourceGroups);
        List<MediaStreamTrackDesc> tracks = new ArrayList<>();
        trackSsrcsList.forEach(trackSsrcs -> {
            int numSpatialLayersPerStream = 1;
            int numTemporalLayersPerStream = 1;
            if (trackSsrcs.size() > 1 && ENABLE_SVC.booleanValue())
                numTemporalLayersPerStream = 3;
            Map<Long, SecondarySsrcs> secondarySsrcs = getAllSecondarySsrcs(trackSsrcs, sourceGroups);
            MediaStreamTrackDesc track = createTrack(mediaStreamTrackReceiver, trackSsrcs, numSpatialLayersPerStream, numTemporalLayersPerStream, secondarySsrcs);
            tracks.add(track);
        });
        return tracks.<MediaStreamTrackDesc>toArray(new MediaStreamTrackDesc[tracks.size()]);
    }

    private static int qid(int streamIdx, int spatialIdx, int temporalIdx, int spatialLen, int temporalLen) {
        return streamIdx * spatialLen * temporalLen + spatialIdx * temporalLen + temporalIdx;
    }

    private static class SecondarySsrc {
        public long ssrc;

        public String type;

        public SecondarySsrc(long ssrc, String type) {
            this.ssrc = ssrc;
            this.type = type;
        }
    }

    private static class SecondarySsrcs implements Iterable<SecondarySsrc> {
        public List<MediaStreamTrackFactory.SecondarySsrc> secondarySsrcs;

        public SecondarySsrcs(List<MediaStreamTrackFactory.SecondarySsrc> secondarySsrcs) {
            this.secondarySsrcs = secondarySsrcs;
        }

        public Iterator<MediaStreamTrackFactory.SecondarySsrc> iterator() {
            return this.secondarySsrcs.iterator();
        }
    }

    private static class TrackSsrcs implements Iterable<Long> {
        private List<Long> trackSsrcs;

        private String owner;

        private TrackSsrcs(Long ssrc) {
            this(Collections.singletonList(ssrc));
        }

        public TrackSsrcs(List<Long> trackSsrcs) {
            this.trackSsrcs = trackSsrcs;
        }

        public boolean contains(Long ssrc) {
            return this.trackSsrcs.contains(ssrc);
        }

        public int size() {
            return this.trackSsrcs.size();
        }

        public Long get(int index) {
            return this.trackSsrcs.get(index);
        }

        public Iterator<Long> iterator() {
            return this.trackSsrcs.iterator();
        }
    }

    private static MediaStreamTrackDesc createTrack(MediaStreamTrackReceiver receiver, TrackSsrcs primarySsrcs, int numSpatialLayersPerStream, int numTemporalLayersPerStream, Map<Long, SecondarySsrcs> allSecondarySsrcs) {
        int numEncodings = primarySsrcs.size() * numSpatialLayersPerStream * numTemporalLayersPerStream;
        RTPEncodingDesc[] rtpEncodings = new RTPEncodingDesc[numEncodings];
        MediaStreamTrackDesc track = new MediaStreamTrackDesc(receiver, rtpEncodings, primarySsrcs.owner);
        RTPEncodingDesc[] encodings = createRTPEncodings(track, primarySsrcs, numSpatialLayersPerStream, numTemporalLayersPerStream, allSecondarySsrcs);
        assert encodings.length <= numEncodings;
        System.arraycopy(encodings, 0, rtpEncodings, 0, encodings.length);
        return track;
    }
}