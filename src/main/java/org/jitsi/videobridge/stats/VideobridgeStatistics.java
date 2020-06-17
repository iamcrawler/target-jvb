package org.jitsi.videobridge.stats;

import java.lang.management.ManagementFactory;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.locks.Lock;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.stats.MediaStreamStats2;
import org.jitsi.service.neomedia.stats.ReceiveTrackStats;
import org.jitsi.service.neomedia.stats.SendTrackStats;
import org.jitsi.utils.MediaType;
import org.jitsi.videobridge.Channel;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.Content;
import org.jitsi.videobridge.RtpChannel;
import org.jitsi.videobridge.VideoChannel;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.videobridge.octo.OctoRelayService;
import org.json.simple.JSONArray;
import org.osgi.framework.BundleContext;

public class VideobridgeStatistics extends Statistics {
    public static final String AUDIOCHANNELS = "audiochannels";

    public static final String BITRATE_DOWNLOAD = "bit_rate_download";

    public static final String BITRATE_UPLOAD = "bit_rate_upload";

    public static final String PACKET_RATE_DOWNLOAD = "packet_rate_download";

    public static final String PACKET_RATE_UPLOAD = "packet_rate_upload";

    public static final String CONFERENCES = "conferences";

    public static final String CPU_USAGE = "cpu_usage";

    private static final DateFormat dateFormat;

    public static final String NUMBEROFPARTICIPANTS = "participants";

    public static final String NUMBEROFTHREADS = "threads";

    public static final String RTP_LOSS = "rtp_loss";

    public static final String LOSS_RATE_DOWNLOAD = "loss_rate_download";

    public static final String LOSS_RATE_UPLOAD = "loss_rate_upload";

    public static final String JITTER_AGGREGATE = "jitter_aggregate";

    public static final String RTT_AGGREGATE = "rtt_aggregate";

    public static final String LARGEST_CONFERENCE = "largest_conference";

    public static final String CONFERENCE_SIZES = "conference_sizes";

    private static final int CONFERENCE_SIZE_BUCKETS = 22;

    public static final String SHUTDOWN_IN_PROGRESS = "graceful_shutdown";

    public static final String TIMESTAMP = "current_timestamp";

    public static final String TOTAL_MEMORY = "total_memory";

    private static final String TOTAL_NO_PAYLOAD_CHANNELS = "total_no_payload_channels";

    private static final String TOTAL_NO_TRANSPORT_CHANNELS = "total_no_transport_channels";

    private static final String TOTAL_CHANNELS = "total_channels";

    private static final String TOTAL_FAILED_CONFERENCES = "total_failed_conferences";

    private static final String TOTAL_PARTIALLY_FAILED_CONFERENCES = "total_partially_failed_conferences";

    private static final String TOTAL_CONFERENCES_COMPLETED = "total_conferences_completed";

    private static final String TOTAL_CONFERENCES_CREATED = "total_conferences_created";

    private static final String TOTAL_CONFERENCE_SECONDS = "total_conference_seconds";

    private static final String TOTAL_LOSS_CONTROLLED_PARTICIPANT_SECONDS = "total_loss_controlled_participant_seconds";

    private static final String TOTAL_LOSS_LIMITED_PARTICIPANT_SECONDS = "total_loss_limited_participant_seconds";

    private static final String TOTAL_LOSS_DEGRADED_PARTICIPANT_SECONDS = "total_loss_degraded_participant_seconds";

    private static final String TOTAL_UDP_CONNECTIONS = "total_udp_connections";

    private static final String TOTAL_TCP_CONNECTIONS = "total_tcp_connections";

    private static final String TOTAL_DATA_CHANNEL_MESSAGES_RECEIVED = "total_data_channel_messages_received";

    private static final String TOTAL_DATA_CHANNEL_MESSAGES_SENT = "total_data_channel_messages_sent";

    private static final String TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_RECEIVED = "total_colibri_web_socket_messages_received";

    private static final String TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_SENT = "total_colibri_web_socket_messages_sent";

    private static final String TOTAL_BYTES_RECEIVED = "total_bytes_received";

    private static final String TOTAL_BYTES_SENT = "total_bytes_sent";

    private static final String TOTAL_PACKETS_RECEIVED = "total_packets_received";

    private static final String TOTAL_PACKETS_SENT = "total_packets_sent";

    private static final String TOTAL_BYTES_RECEIVED_OCTO = "total_bytes_received_octo";

    private static final String TOTAL_BYTES_SENT_OCTO = "total_bytes_sent_octo";

    private static final String TOTAL_PACKETS_RECEIVED_OCTO = "total_packets_received_octo";

    private static final String TOTAL_PACKETS_SENT_OCTO = "total_packets_sent_octo";

    public static final String USED_MEMORY = "used_memory";

    public static final String VIDEOCHANNELS = "videochannels";

    public static final String VIDEOSTREAMS = "videostreams";

    public static final String RELAY_ID = "relay_id";

    public static final String REGION = "region";

    public static String region = null;

    public static final String REGION_PNAME = "org.jitsi.videobridge.REGION";

    static {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static String currentTimeMillis() {
        return dateFormat.format(new Date());
    }

    private boolean inGenerate = false;

    public VideobridgeStatistics() {
        BundleContext bundleContext = StatsManagerBundleActivator.getBundleContext();
        ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        if (cfg != null)
            region = cfg.getString("org.jitsi.videobridge.REGION", region);
        unlockedSetStat("audiochannels", Integer.valueOf(0));
        unlockedSetStat("bit_rate_download", Integer.valueOf(0));
        unlockedSetStat("bit_rate_upload", Integer.valueOf(0));
        unlockedSetStat("conferences", Integer.valueOf(0));
        unlockedSetStat("cpu_usage", Double.valueOf(0.0D));
        unlockedSetStat("participants", Integer.valueOf(0));
        unlockedSetStat("threads", Integer.valueOf(0));
        unlockedSetStat("rtp_loss", Double.valueOf(0.0D));
        unlockedSetStat("total_memory", Integer.valueOf(0));
        unlockedSetStat("used_memory", Integer.valueOf(0));
        unlockedSetStat("videochannels", Integer.valueOf(0));
        unlockedSetStat("videostreams", Integer.valueOf(0));
        unlockedSetStat("loss_rate_download", Double.valueOf(0.0D));
        unlockedSetStat("loss_rate_upload", Double.valueOf(0.0D));
        unlockedSetStat("jitter_aggregate", Double.valueOf(0.0D));
        unlockedSetStat("rtt_aggregate", Double.valueOf(0.0D));
        unlockedSetStat("largest_conference", Integer.valueOf(0));
        unlockedSetStat("conference_sizes", "[]");
        unlockedSetStat("current_timestamp", currentTimeMillis());
    }

    public void generate() {
        boolean inGenerate;
        Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            if (this.inGenerate) {
                inGenerate = true;
            } else {
                inGenerate = false;
                this.inGenerate = true;
            }
        } finally {
            lock.unlock();
        }
        if (!inGenerate)
            try {
                generate0();
            } finally {
                lock.lock();
                try {
                    this.inGenerate = false;
                } finally {
                    lock.unlock();
                }
            }
    }

    protected void generate0() {
        int audioChannels = 0, videoChannels = 0;
        int conferences = 0;
        int endpoints = 0;
        int videoStreams = 0;
        double fractionLostSum = 0.0D;
        int fractionLostCount = 0;
        long packetsReceived = 0L, packetsReceivedLost = 0L;
        long bitrateDownloadBps = 0L, bitrateUploadBps = 0L;
        double jitterSumMs = 0.0D;
        long rttSumMs = 0L;
        int jitterCount = 0, rttCount = 0;
        int largestConferenceSize = 0;
        int[] conferenceSizes = new int[22];
        int packetRateUpload = 0, packetRateDownload = 0;
        boolean shutdownInProgress = false;
        int totalConferencesCreated = 0, totalConferencesCompleted = 0;
        int totalFailedConferences = 0, totalPartiallyFailedConferences = 0;
        int totalNoTransportChannels = 0, totalNoPayloadChannels = 0;
        int totalChannels = 0;
        long totalConferenceSeconds = 0L;
        long totalLossControlledParticipantSeconds = 0L;
        long totalLossLimitedParticipantSeconds = 0L;
        long totalLossDegradedParticipantSeconds = 0L;
        int totalUdpConnections = 0, totalTcpConnections = 0;
        long totalDataChannelMessagesReceived = 0L;
        long totalDataChannelMessagesSent = 0L;
        long totalColibriWebSocketMessagesReceived = 0L;
        long totalColibriWebSocketMessagesSent = 0L;
        long totalBytesReceived = 0L;
        long totalBytesSent = 0L;
        long totalPacketsReceived = 0L;
        long totalPacketsSent = 0L;
        long totalBytesReceivedOcto = 0L;
        long totalBytesSentOcto = 0L;
        long totalPacketsReceivedOcto = 0L;
        long totalPacketsSentOcto = 0L;
        BundleContext bundleContext = StatsManagerBundleActivator.getBundleContext();
        OctoRelayService relayService = (OctoRelayService)ServiceUtils.getService(bundleContext, OctoRelayService.class);
        String relayId = (relayService == null) ? null : relayService.getRelayId();
        for (Videobridge videobridge : Videobridge.getVideobridges(bundleContext)) {
            Videobridge.Statistics jvbStats = videobridge.getStatistics();
            totalConferencesCreated += jvbStats.totalConferencesCreated.get();
            totalConferencesCompleted += jvbStats.totalConferencesCompleted
                    .get();
            totalConferenceSeconds += jvbStats.totalConferenceSeconds.get();
            totalLossControlledParticipantSeconds += jvbStats.totalLossControlledParticipantMs
                    .get() / 1000L;
            totalLossLimitedParticipantSeconds += jvbStats.totalLossLimitedParticipantMs
                    .get() / 1000L;
            totalLossDegradedParticipantSeconds += jvbStats.totalLossDegradedParticipantMs
                    .get() / 1000L;
            totalFailedConferences += jvbStats.totalFailedConferences.get();
            totalPartiallyFailedConferences += jvbStats.totalPartiallyFailedConferences
                    .get();
            totalNoTransportChannels += jvbStats.totalNoTransportChannels.get();
            totalNoPayloadChannels += jvbStats.totalNoPayloadChannels.get();
            totalChannels += jvbStats.totalChannels.get();
            totalUdpConnections += jvbStats.totalUdpTransportManagers.get();
            totalTcpConnections += jvbStats.totalTcpTransportManagers.get();
            totalDataChannelMessagesReceived += jvbStats.totalDataChannelMessagesReceived
                    .get();
            totalDataChannelMessagesSent += jvbStats.totalDataChannelMessagesSent
                    .get();
            totalColibriWebSocketMessagesReceived += jvbStats.totalColibriWebSocketMessagesReceived
                    .get();
            totalColibriWebSocketMessagesSent += jvbStats.totalColibriWebSocketMessagesSent
                    .get();
            totalBytesReceived += jvbStats.totalBytesReceived.get();
            totalBytesSent += jvbStats.totalBytesSent.get();
            totalPacketsReceived += jvbStats.totalPacketsReceived.get();
            totalPacketsSent += jvbStats.totalPacketsSent.get();
            totalBytesReceivedOcto += jvbStats.totalBytesReceivedOcto.get();
            totalBytesSentOcto += jvbStats.totalBytesSentOcto.get();
            totalPacketsReceivedOcto += jvbStats.totalPacketsReceivedOcto.get();
            totalPacketsSentOcto += jvbStats.totalPacketsSentOcto.get();
            for (Conference conference : videobridge.getConferences()) {
                if (conference.includeInStatistics()) {
                    conferences++;
                    int conferenceEndpoints = conference.getEndpointCount();
                    endpoints += conference.getEndpointCount();
                    if (conferenceEndpoints > largestConferenceSize)
                        largestConferenceSize = conferenceEndpoints;
                    int idx = (conferenceEndpoints < conferenceSizes.length) ? conferenceEndpoints : (conferenceSizes.length - 1);
                    conferenceSizes[idx] = conferenceSizes[idx] + 1;
                    for (Content content : conference.getContents()) {
                        MediaType mediaType = content.getMediaType();
                        int contentChannelCount = content.getChannelCount();
                        if (MediaType.AUDIO.equals(mediaType)) {
                            audioChannels += contentChannelCount;
                        } else if (MediaType.VIDEO.equals(mediaType)) {
                            videoChannels += content.getChannelCount();
                        }
                        for (Channel channel : content.getChannels()) {
                            if (channel instanceof RtpChannel) {
                                RtpChannel rtpChannel = (RtpChannel)channel;
                                MediaStream stream = rtpChannel.getStream();
                                if (stream == null)
                                    continue;
                                MediaStreamStats2 stats = stream.getMediaStreamStats();
                                ReceiveTrackStats receiveStats = stats.getReceiveStats();
                                SendTrackStats sendStats = stats.getSendStats();
                                packetsReceived += receiveStats.getCurrentPackets();
                                packetsReceivedLost += receiveStats
                                        .getCurrentPacketsLost();
                                fractionLostCount++;
                                fractionLostSum += sendStats.getLossRate();
                                packetRateDownload = (int)(packetRateDownload + receiveStats.getPacketRate());
                                packetRateUpload = (int)(packetRateUpload + sendStats.getPacketRate());
                                bitrateDownloadBps += receiveStats.getBitrate();
                                bitrateUploadBps += sendStats.getBitrate();
                                double jitter = sendStats.getJitter();
                                if (jitter != Double.MIN_VALUE) {
                                    jitterSumMs += Math.abs(jitter);
                                    jitterCount++;
                                }
                                jitter = receiveStats.getJitter();
                                if (jitter != Double.MIN_VALUE) {
                                    jitterSumMs += Math.abs(jitter);
                                    jitterCount++;
                                }
                                long rtt = sendStats.getRtt();
                                if (rtt > 0L) {
                                    rttSumMs += rtt;
                                    rttCount++;
                                }
                                if (channel instanceof VideoChannel) {
                                    VideoChannel videoChannel = (VideoChannel)channel;
                                    int channelStreams = 1;
                                    int lastN = videoChannel.getLastN();
                                    channelStreams += (lastN == -1) ? (contentChannelCount - 1) :

                                            Math.min(lastN, contentChannelCount - 1);
                                    videoStreams += channelStreams;
                                }
                            }
                        }
                    }
                }
            }
            if (videobridge.isShutdownInProgress())
                shutdownInProgress = true;
        }
        double lossRateDownload = (packetsReceived + packetsReceivedLost > 0L) ? (packetsReceivedLost / (packetsReceived + packetsReceivedLost)) : 0.0D;
        double lossRateUpload = (fractionLostCount > 0) ? (fractionLostSum / fractionLostCount) : 0.0D;
        double jitterAggregate = (jitterCount > 0) ? (jitterSumMs / jitterCount) : 0.0D;
        double rttAggregate = (rttCount > 0) ? (rttSumMs / rttCount) : 0.0D;
        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : conferenceSizes)
            conferenceSizesJson.add(Integer.valueOf(size));
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        OsStatistics osStatistics = OsStatistics.getOsStatistics();
        double cpuUsage = osStatistics.getCPUUsage();
        int totalMemory = osStatistics.getTotalMemory();
        int usedMemory = osStatistics.getUsedMemory();
        String timestamp = currentTimeMillis();
        Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            unlockedSetStat("bit_rate_download",

                    Long.valueOf((bitrateDownloadBps + 500L) / 1000L));
            unlockedSetStat("bit_rate_upload",

                    Long.valueOf((bitrateUploadBps + 500L) / 1000L));
            unlockedSetStat("packet_rate_download", Integer.valueOf(packetRateDownload));
            unlockedSetStat("packet_rate_upload", Integer.valueOf(packetRateUpload));
            unlockedSetStat("rtp_loss",

                    Double.valueOf(lossRateDownload + lossRateUpload));
            unlockedSetStat("loss_rate_download", Double.valueOf(lossRateDownload));
            unlockedSetStat("loss_rate_upload", Double.valueOf(lossRateUpload));
            unlockedSetStat("jitter_aggregate", Double.valueOf(jitterAggregate));
            unlockedSetStat("rtt_aggregate", Double.valueOf(rttAggregate));
            unlockedSetStat("audiochannels", Integer.valueOf(audioChannels));
            unlockedSetStat("total_failed_conferences", Integer.valueOf(totalFailedConferences));
            unlockedSetStat("total_partially_failed_conferences",

                    Integer.valueOf(totalPartiallyFailedConferences));
            unlockedSetStat("total_no_payload_channels",

                    Integer.valueOf(totalNoPayloadChannels));
            unlockedSetStat("total_no_transport_channels",

                    Integer.valueOf(totalNoTransportChannels));
            unlockedSetStat("total_conferences_created",

                    Integer.valueOf(totalConferencesCreated));
            unlockedSetStat("total_conferences_completed",

                    Integer.valueOf(totalConferencesCompleted));
            unlockedSetStat("total_udp_connections", Integer.valueOf(totalUdpConnections));
            unlockedSetStat("total_tcp_connections", Integer.valueOf(totalTcpConnections));
            unlockedSetStat("total_conference_seconds", Long.valueOf(totalConferenceSeconds));
            unlockedSetStat("total_loss_controlled_participant_seconds",
                    Long.valueOf(totalLossControlledParticipantSeconds));
            unlockedSetStat("total_loss_limited_participant_seconds",
                    Long.valueOf(totalLossLimitedParticipantSeconds));
            unlockedSetStat("total_loss_degraded_participant_seconds",
                    Long.valueOf(totalLossDegradedParticipantSeconds));
            unlockedSetStat("total_channels", Integer.valueOf(totalChannels));
            unlockedSetStat("conferences", Integer.valueOf(conferences));
            unlockedSetStat("participants", Integer.valueOf(endpoints));
            unlockedSetStat("videochannels", Integer.valueOf(videoChannels));
            unlockedSetStat("videostreams", Integer.valueOf(videoStreams));
            unlockedSetStat("largest_conference", Integer.valueOf(largestConferenceSize));
            unlockedSetStat("conference_sizes", conferenceSizesJson);
            unlockedSetStat("threads", Integer.valueOf(threadCount));
            unlockedSetStat("cpu_usage", Double.valueOf(Math.max(cpuUsage, 0.0D)));
            unlockedSetStat("total_memory", Integer.valueOf(Math.max(totalMemory, 0)));
            unlockedSetStat("used_memory", Integer.valueOf(Math.max(usedMemory, 0)));
            unlockedSetStat("graceful_shutdown", Boolean.valueOf(shutdownInProgress));
            unlockedSetStat("total_data_channel_messages_received",
                    Long.valueOf(totalDataChannelMessagesReceived));
            unlockedSetStat("total_data_channel_messages_sent",
                    Long.valueOf(totalDataChannelMessagesSent));
            unlockedSetStat("total_colibri_web_socket_messages_received",
                    Long.valueOf(totalColibriWebSocketMessagesReceived));
            unlockedSetStat("total_colibri_web_socket_messages_sent",
                    Long.valueOf(totalColibriWebSocketMessagesSent));
            unlockedSetStat("total_bytes_received", Long.valueOf(totalBytesReceived));
            unlockedSetStat("total_bytes_sent", Long.valueOf(totalBytesSent));
            unlockedSetStat("total_packets_received", Long.valueOf(totalPacketsReceived));
            unlockedSetStat("total_packets_sent", Long.valueOf(totalPacketsSent));
            unlockedSetStat("total_bytes_received_octo", Long.valueOf(totalBytesReceivedOcto));
            unlockedSetStat("total_bytes_sent_octo", Long.valueOf(totalBytesSentOcto));
            unlockedSetStat("total_packets_received_octo",
                    Long.valueOf(totalPacketsReceivedOcto));
            unlockedSetStat("total_packets_sent_octo", Long.valueOf(totalPacketsSentOcto));
            unlockedSetStat("current_timestamp", timestamp);
            if (relayId != null)
                unlockedSetStat("relay_id", relayId);
            if (region != null)
                unlockedSetStat("region", region);
        } finally {
            lock.unlock();
        }
    }
}
