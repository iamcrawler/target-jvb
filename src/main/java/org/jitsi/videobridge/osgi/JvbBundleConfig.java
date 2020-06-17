package org.jitsi.videobridge.osgi;

import java.util.Map;
import org.ice4j.ice.harvest.AbstractUdpListener;
import org.jitsi.impl.neomedia.transform.csrc.SsrcTransformEngine;
import org.jitsi.impl.neomedia.transform.srtp.SRTPCryptoContext;
import org.jitsi.meet.OSGiBundleConfig;
import org.jitsi.service.neomedia.AudioMediaStream;
import org.jitsi.service.neomedia.VideoMediaStream;
import org.jitsi.stats.media.Utils;

public class JvbBundleConfig extends OSGiBundleConfig {
    private static final String[][] BUNDLES = new String[][] {
            { "org/jitsi/eventadmin/Activator" }, { "org/jitsi/service/libjitsi/LibJitsiActivator" }, { "net/java/sip/communicator/util/UtilActivator", "net/java/sip/communicator/impl/fileaccess/FileAccessActivator" }, { "net/java/sip/communicator/impl/configuration/ConfigurationActivator" }, { "net/java/sip/communicator/impl/resources/ResourceManagementActivator" }, { "net/java/sip/communicator/impl/netaddr/NetaddrActivator" }, { "net/java/sip/communicator/impl/packetlogging/PacketLoggingActivator" }, { "net/java/sip/communicator/service/gui/internal/GuiServiceActivator" }, { "net/java/sip/communicator/service/protocol/media/ProtocolMediaActivator" }, { "org/jitsi/videobridge/eventadmin/callstats/Activator" },
            { "org/jitsi/videobridge/version/VersionActivator" }, { "org/jitsi/videobridge/rest/RESTBundleActivator", "org/jitsi/videobridge/rest/PublicRESTBundleActivator", "org/jitsi/videobridge/rest/PublicClearPortRedirectBundleActivator", "org/jitsi/videobridge/stats/StatsManagerBundleActivator", "org/jitsi/videobridge/EndpointConnectionStatus" }, { "org/jitsi/videobridge/VideobridgeBundleActivator" }, { "org/jitsi/videobridge/xmpp/ClientConnectionImpl" }, { "org/jitsi/videobridge/octo/OctoRelayService" } };

    protected String[][] getBundlesImpl() {
        return BUNDLES;
    }

    public Map<String, String> getSystemPropertyDefaults() {
        Map<String, String> defaults = super.getSystemPropertyDefaults();
        String true_ = Boolean.toString(true);
        String false_ = Boolean.toString(false);
        defaults.put("net.java.sip.communicator.impl.neomedia.video.maxbandwidth",

                Integer.toString(2147483647));
        defaults.put(SsrcTransformEngine.DROP_MUTED_AUDIO_SOURCE_IN_REVERSE_TRANSFORM, true_);
        defaults.put(SRTPCryptoContext.CHECK_REPLAY_PNAME, false_);
        defaults.put("org.ice4j.ice.CONSENT_FRESHNESS_INTERVAL", "3000");
        defaults.put("org.ice4j.ice.CONSENT_FRESHNESS_WAIT_INTERVAL", "500");
        defaults.put("org.ice4j.ice.CONSENT_FRESHNESS_MAX_WAIT_INTERVAL", "500");
        defaults.put("org.ice4j.ice.CONSENT_FRESHNESS_MAX_RETRANSMISSIONS", "5");
        defaults.put("org.ice4j.ice.harvest.DISABLE_LINK_LOCAL_ADDRESSES", true_);
        defaults.put(AudioMediaStream.DISABLE_DTMF_HANDLING_PNAME, true_);
        defaults.put(VideoMediaStream.REQUEST_RETRANSMISSIONS_PNAME, true_);
        defaults.put("net.java.sip.communicator.packetlogging.PACKET_LOGGING_ENABLED", false_);
        defaults.put(AbstractUdpListener.SO_RCVBUF_PNAME, "10485760");
        defaults.put("org.jitsi.impl.neomedia.rtp.sendsidebandwidthestimation.BandwidthEstimatorImpl.START_BITRATE_BPS", "2500000");
        defaults.put("org.jitsi.videobridge.ENABLE_SVC", true_);
        defaults.put("org.jitsi.impl.neomedia.rtp.ENABLE_AST_RBE", true_);
        defaults.put("net.java.sip.communicator.impl.configuration.USE_PROPFILE_CONFIG", true_);
        Utils.getCallStatsJavaSDKSystemPropertyDefaults(defaults);
        return defaults;
    }
}
