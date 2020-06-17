package org.jitsi.videobridge;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;
import net.java.sip.communicator.service.netaddr.NetworkAddressManagerService;
import net.java.sip.communicator.util.NetworkUtils;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.neomedia.DefaultStreamConnector;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.SrtpControl;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.jingle.CandidatePacketExtension;
import org.jitsi.xmpp.extensions.jingle.CandidateType;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.osgi.framework.BundleContext;

public class RawUdpTransportManager extends TransportManager {
    private static final Logger classLogger = Logger.getLogger(RawUdpTransportManager.class);

    private final Channel channel;

    private final int generation;

    private final String rtcpCandidateID;

    private final String rtpCandidateID;

    private final StreamConnector streamConnector;

    private boolean started = false;

    private final Logger logger;

    public RawUdpTransportManager(Channel channel) throws IOException {
        this.channel = channel;
        this
                .logger = Logger.getLogger(classLogger, channel

                .getContent().getConference().getLogger());
        addChannel(channel);
        this.streamConnector = createStreamConnector();
        this.generation = 0;
        this.rtpCandidateID = generateCandidateID();
        this.rtcpCandidateID = generateCandidateID();
    }

    public boolean addChannel(Channel c) {
        return getChannels().isEmpty() ? super.addChannel(c) : false;
    }

    public void close() {
        super.close();
        if (this.streamConnector != null)
            this.streamConnector.close();
    }

    public boolean close(Channel channel) {
        if (channel == this.channel) {
            super.close(channel);
            channel.transportClosed();
            close();
            return true;
        }
        return false;
    }

    private static InetAddress getLocalHostLanAddress() throws UnknownHostException {
        try {
            InetAddress candidateAddress = null;
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        if (inetAddress.isSiteLocalAddress())
                            return inetAddress;
                        if (candidateAddress == null)
                            candidateAddress = inetAddress;
                    }
                }
            }
            if (candidateAddress != null)
                return candidateAddress;
            InetAddress jdkSuppliedAddress = InetAddress.getLocalHost();
            if (jdkSuppliedAddress == null)
                throw new UnknownHostException("The JDK InetAddress.getLocalHost() method unexpectedly returned null.");
            return jdkSuppliedAddress;
        } catch (Exception e) {
            UnknownHostException unknownHostException = new UnknownHostException("Failed to determine LAN address: " + e);
            unknownHostException.initCause(e);
            throw unknownHostException;
        }
    }

    private StreamConnector createStreamConnector() throws IOException {
        BundleContext bundleContext = this.channel.getBundleContext();
        NetworkAddressManagerService nams = (NetworkAddressManagerService)ServiceUtils.getService(bundleContext, NetworkAddressManagerService.class);
        InetAddress bindAddr = null;
        if (nams != null) {
            Content content = this.channel.getContent();
            Conference conference = content.getConference();
            Videobridge videobridge = conference.getVideobridge();
            for (ComponentImpl component : videobridge.getComponents()) {
                String domain = component.getDomain();
                if (domain != null && domain.length() != 0) {
                    int subdomainEnd = domain.indexOf('.');
                    if (subdomainEnd >= 0)
                        domain = domain.substring(subdomainEnd + 1);
                    if (domain.length() != 0)
                        try {
                            bindAddr = nams.getLocalHost(
                                    NetworkUtils.getInetAddress(domain));
                        } catch (UnknownHostException uhe) {
                            this.logger.info("Failed to get InetAddress from " + domain + " for channel " + this.channel

                                    .getID() + " of content " + content
                                    .getName() + " of conference " + conference
                                    .getID() + ".", uhe);
                        }
                }
                if (bindAddr != null)
                    break;
            }
        }
        if (bindAddr == null) {
            ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
            InetAddress localAddress = null;
            if (cfg != null) {
                String localAddressStr = cfg.getString("org.jitsi.videobridge.NAT_HARVESTER_LOCAL_ADDRESS");
                if (localAddressStr != null && !localAddressStr.isEmpty())
                    localAddress = InetAddress.getByName(localAddressStr);
            }
            if (localAddress != null) {
                bindAddr = localAddress;
            } else {
                bindAddr = getLocalHostLanAddress();
            }
        }
        DefaultStreamConnector defaultStreamConnector = new DefaultStreamConnector(bindAddr);
        defaultStreamConnector.getDataSocket();
        defaultStreamConnector.getControlSocket();
        return (StreamConnector)defaultStreamConnector;
    }

    public void describe(ColibriConferenceIQ.ChannelCommon iq) {
        super.describe(iq);
        IceUdpTransportPacketExtension transport = iq.getTransport();
        if (transport != null) {
            String host = null;
            int rtcpPort = 0;
            int rtpPort = 0;
            for (CandidatePacketExtension candidate : transport.getCandidateList()) {
                switch (candidate.getComponent()) {
                    case 2:
                        rtcpPort = candidate.getPort();
                        break;
                    case 1:
                        rtpPort = candidate.getPort();
                        break;
                    default:
                        continue;
                }
                if (host == null || host.length() == 0)
                    host = candidate.getIP();
            }
            if (iq instanceof ColibriConferenceIQ.Channel) {
                ColibriConferenceIQ.Channel channelIq = (ColibriConferenceIQ.Channel)iq;
                channelIq.setHost(host);
                channelIq.setRTCPPort(rtcpPort);
                channelIq.setRTPPort(rtpPort);
            }
        }
    }

    protected void describe(IceUdpTransportPacketExtension pe) {
        StreamConnector streamConnector = getStreamConnector(this.channel);
        DatagramSocket socket = streamConnector.getDataSocket();
        CandidatePacketExtension candidate = new CandidatePacketExtension();
        candidate.setComponent(1);
        candidate.setGeneration(this.generation);
        candidate.setID(this.rtpCandidateID);
        candidate.setIP(socket.getLocalAddress().getHostAddress());
        candidate.setPort(socket.getLocalPort());
        candidate.setType(CandidateType.host);
        pe.addCandidate(candidate);
        socket = streamConnector.getControlSocket();
        candidate = new CandidatePacketExtension();
        candidate.setComponent(2);
        candidate.setGeneration(this.generation);
        candidate.setID(this.rtcpCandidateID);
        candidate.setIP(socket.getLocalAddress().getHostAddress());
        candidate.setPort(socket.getLocalPort());
        candidate.setType(CandidateType.host);
        pe.addCandidate(candidate);
    }

    public SrtpControl getSrtpControl(Channel channel) {
        return null;
    }

    public StreamConnector getStreamConnector(Channel channel) {
        return this.streamConnector;
    }

    public MediaStreamTarget getStreamTarget(Channel channel) {
        return null;
    }

    public String getXmlNamespace() {
        return "urn:xmpp:jingle:transports:raw-udp:1";
    }

    public void startConnectivityEstablishment(IceUdpTransportPacketExtension transport) {
        if (this.started)
            return;
        if (!(transport instanceof org.jitsi.xmpp.extensions.jingle.RawUdpTransportPacketExtension)) {
            this.logger.error("Only RAW transport is accepted by this transport manager.");
            return;
        }
        this.channel.transportConnected();
        this.started = true;
    }

    public boolean isConnected() {
        return true;
    }
}
