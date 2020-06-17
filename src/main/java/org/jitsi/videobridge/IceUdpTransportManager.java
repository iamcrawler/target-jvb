package org.jitsi.videobridge;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import net.java.sip.communicator.service.protocol.OperationFailedException;
import net.java.sip.communicator.util.ServiceUtils;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.KeepAliveStrategy;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.AbstractUdpListener;
import org.ice4j.ice.harvest.CandidateHarvester;
import org.ice4j.ice.harvest.SinglePortUdpHarvester;
import org.ice4j.ice.harvest.TcpHarvester;
import org.ice4j.socket.DTLSDatagramFilter;
import org.ice4j.socket.DatagramPacketFilter;
import org.ice4j.socket.IceSocketWrapper;
import org.ice4j.socket.MultiplexedDatagramSocket;
import org.ice4j.socket.MultiplexedSocket;
import org.ice4j.socket.MultiplexingDatagramSocket;
import org.ice4j.socket.MultiplexingSocket;
import org.jitsi.eventadmin.EventAdmin;
import org.jitsi.impl.neomedia.rtp.TransportCCEngine;
import org.jitsi.impl.neomedia.transform.dtls.DtlsControlImpl;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.neomedia.DefaultStreamConnector;
import org.jitsi.service.neomedia.DefaultTCPStreamConnector;
import org.jitsi.service.neomedia.DtlsControl;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.SrtpControl;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.StringUtils;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.health.Health;
import org.jitsi.videobridge.rest.ColibriWebSocketService;
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension;
import org.jitsi.xmpp.extensions.jingle.CandidatePacketExtension;
//import org.jitsi.xmpp.extensions.jingle.CandidateType;
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.RtcpmuxPacketExtension;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.osgi.framework.BundleContext;

public class IceUdpTransportManager extends TransportManager {
    private static final String DEFAULT_ICE_STREAM_NAME = "stream";

    public static final String DISABLE_TCP_HARVESTER = "org.jitsi.videobridge.DISABLE_TCP_HARVESTER";

    public static final String SINGLE_PORT_HARVESTER_PORT = "org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT";

    public static final String KEEP_ALIVE_STRATEGY_PNAME = "org.jitsi.videobridge.KEEP_ALIVE_STRATEGY";

    private static KeepAliveStrategy keepAliveStrategy = KeepAliveStrategy.SELECTED_AND_TCP;

    private static final int SINGLE_PORT_DEFAULT_VALUE = 10000;

    private static final Logger classLogger = Logger.getLogger(IceUdpTransportManager.class);

    private static final int TCP_DEFAULT_PORT = 443;

    private static final int TCP_FALLBACK_PORT = 4443;

    public static final String TCP_HARVESTER_MAPPED_PORT = "org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT";

    public static final String TCP_HARVESTER_PORT = "org.jitsi.videobridge.TCP_HARVESTER_PORT";

    public static final String TCP_HARVESTER_SSLTCP = "org.jitsi.videobridge.TCP_HARVESTER_SSLTCP";

    public static final String ICE_UFRAG_PREFIX_PNAME = "org.jitsi.videobridge.ICE_UFRAG_PREFIX";

    private static String iceUfragPrefix;

    private static final boolean TCP_HARVESTER_SSLTCP_DEFAULT = true;

    private static TcpHarvester tcpHarvester = null;

    private static List<SinglePortUdpHarvester> singlePortHarvesters = null;

    public static boolean healthy = true;

    private static boolean staticConfigurationInitialized = false;

    private static int tcpHarvesterMappedPort = -1;

    private static boolean useComponentSocket = true;

    public static final String USE_COMPONENT_SOCKET_PNAME = "org.jitsi.videobridge.USE_COMPONENT_SOCKET";

    private static void initializeStaticConfiguration(ConfigurationService cfg) {
        synchronized (IceUdpTransportManager.class) {
            if (staticConfigurationInitialized)
                return;
            staticConfigurationInitialized = true;
            useComponentSocket = cfg.getBoolean("org.jitsi.videobridge.USE_COMPONENT_SOCKET", useComponentSocket);
            classLogger.info("Using component socket: " + useComponentSocket);
            iceUfragPrefix = cfg.getString("org.jitsi.videobridge.ICE_UFRAG_PREFIX", null);
            String strategyName = cfg.getString("org.jitsi.videobridge.KEEP_ALIVE_STRATEGY");
            KeepAliveStrategy strategy = KeepAliveStrategy.fromString(strategyName);
            if (strategyName != null && strategy == null) {
                classLogger.warn("Invalid keep alive strategy name: " + strategyName);
            } else if (strategy != null) {
                keepAliveStrategy = strategy;
            }
            int singlePort = cfg.getInt("org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT", 10000);
            if (singlePort != -1) {
                singlePortHarvesters = SinglePortUdpHarvester.createHarvesters(singlePort);
                if (singlePortHarvesters.isEmpty()) {
                    singlePortHarvesters = null;
                    classLogger.info("No single-port harvesters created.");
                }
                healthy = (singlePortHarvesters != null);
            }
            if (!cfg.getBoolean("org.jitsi.videobridge.DISABLE_TCP_HARVESTER", false)) {
                int port = cfg.getInt("org.jitsi.videobridge.TCP_HARVESTER_PORT", -1);
                boolean fallback = false;
                boolean ssltcp = cfg.getBoolean("org.jitsi.videobridge.TCP_HARVESTER_SSLTCP", true);
                if (port == -1) {
                    port = 443;
                    fallback = true;
                }
                try {
                    tcpHarvester = new TcpHarvester(port, ssltcp);
                } catch (IOException ioe) {
                    classLogger.warn("Failed to initialize TCP harvester on port " + port + ": " + ioe + (fallback ? ". Retrying on port 4443" : "") + ".");
                }
                if (tcpHarvester == null) {
                    if (!fallback)
                        return;
                    port = 4443;
                    try {
                        tcpHarvester = new TcpHarvester(port, ssltcp);
                    } catch (IOException ioe) {
                        classLogger.warn("Failed to initialize TCP harvester on fallback port " + port + ": " + ioe);
                        return;
                    }
                }
                if (classLogger.isInfoEnabled())
                    classLogger.info("Initialized TCP harvester on port " + port + ", using SSLTCP:" + ssltcp);
                int mappedPort = cfg.getInt("org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT", -1);
                if (mappedPort != -1) {
                    tcpHarvesterMappedPort = mappedPort;
                    tcpHarvester.addMappedPort(mappedPort);
                }
            }
        }
    }

    public static void closeStaticConfiguration(ConfigurationService cfg) {
        synchronized (IceUdpTransportManager.class) {
            if (!staticConfigurationInitialized)
                return;
            staticConfigurationInitialized = false;
            if (singlePortHarvesters != null) {
                singlePortHarvesters.forEach(AbstractUdpListener::close);
                singlePortHarvesters = null;
            }
            if (tcpHarvester != null) {
                tcpHarvester.close();
                tcpHarvester = null;
            }
            healthy = true;
        }
    }

    private Channel channelForDtls = null;

    private boolean closed = false;

    private final Conference conference;

    private final DiagnosticContext diagnosticContext = new DiagnosticContext();

    private final String id;

    private Thread connectThread;

    private final Object connectThreadSyncRoot = new Object();

    private final DtlsControlImpl dtlsControl;

    private Agent iceAgent;

    private final PropertyChangeListener iceAgentStateChangeListener = this::iceAgentStateChange;

    private boolean iceConnected = false;

    private final IceMediaStream iceStream;

    private final PropertyChangeListener iceStreamPairChangeListener = this::iceStreamPairChange;

    private final boolean controlling;

    private int numComponents;

    private boolean rtcpmux;

    private SctpConnection sctpConnection = null;

    private final Logger logger;

    private final TransportCCEngine transportCCEngine;

    public IceUdpTransportManager(Conference conference, boolean controlling) throws IOException {
        this(conference, controlling, 2, "stream", (String)null);
    }

    public IceUdpTransportManager(Conference conference, boolean controlling, int numComponents) throws IOException {
        this(conference, controlling, numComponents, "stream", (String)null);
    }

    public IceUdpTransportManager(Conference conference, boolean controlling, int numComponents, String id) throws IOException {
        this(conference, controlling, numComponents, "stream", id);
    }

    public IceUdpTransportManager(Conference conference, boolean controlling, int numComponents, String iceStreamName, String id) throws IOException {
        this.conference = conference;
        this.id = id;
        this.controlling = controlling;
        this.numComponents = numComponents;
        this.rtcpmux = (numComponents == 1);
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
        this.transportCCEngine = new TransportCCEngine(this.diagnosticContext);
        conference.appendDiagnosticInformation(this.diagnosticContext);
        this.diagnosticContext.put("transport", Integer.valueOf(hashCode()));
        this.dtlsControl = createDtlsControl();
        this.iceAgent = createIceAgent(controlling, iceStreamName, this.rtcpmux);
        this.iceAgent.addStateChangeListener(this.iceAgentStateChangeListener);
        this.iceStream = this.iceAgent.getStream(iceStreamName);
        this.iceStream.addPairChangeListener(this.iceStreamPairChangeListener);
        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.transportCreated(this));
    }

    public IceUdpTransportManager(Conference conference, boolean controlling, String iceStreamName) throws IOException {
        this(conference, controlling, 2, iceStreamName);
    }

    public boolean addChannel(Channel channel) {
        if (this.closed)
            return false;
        if (channel instanceof SctpConnection && this.sctpConnection != null && this.sctpConnection != channel) {
            this.logger.error("Not adding a second SctpConnection to TransportManager.");
            return false;
        }
        if (!super.addChannel(channel))
            return false;
        if (channel instanceof SctpConnection) {
            this.sctpConnection = (SctpConnection)channel;
            if (this.channelForDtls != null && this.channelForDtls instanceof RtpChannel) {
                RtpChannel rtpChannelForDtls = (RtpChannel)this.channelForDtls;
                rtpChannelForDtls.getDatagramFilter(false).setAcceptNonRtp(false);
                rtpChannelForDtls.getDatagramFilter(true).setAcceptNonRtp(false);
            }
            this.channelForDtls = this.sctpConnection;
        } else if (this.channelForDtls == null) {
            this.channelForDtls = channel;
            RtpChannel rtpChannel = (RtpChannel)channel;
            rtpChannel.getDatagramFilter(false).setAcceptNonRtp(true);
            rtpChannel.getDatagramFilter(true).setAcceptNonRtp(!this.rtcpmux);
        }
        if (this.iceConnected)
            channel.transportConnected();
        EventAdmin eventAdmin = this.conference.getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.transportChannelAdded(channel));
        return true;
    }

    private int addRemoteCandidates(List<CandidatePacketExtension> candidates, boolean iceAgentStateIsRunning) {
        Collections.sort(candidates);
        int generation = this.iceAgent.getGeneration();
        int remoteCandidateCount = 0;
        for (CandidatePacketExtension candidate : candidates) {
            if (candidate.getGeneration() != generation)
                continue;
            if (this.rtcpmux && 2 == candidate.getComponent()) {
                this.logger.warn("Received an RTCP candidate, but we're using rtcp-mux. Ignoring.");
                continue;
            }
            Component component = this.iceStream.getComponent(candidate.getComponent());
            TransportAddress relatedAddress = null;
            String relAddr;
            int relPort;
            if ((relAddr = candidate.getRelAddr()) != null && (
                    relPort = candidate.getRelPort()) != -1)
                relatedAddress = new TransportAddress(relAddr, relPort, Transport.parse(candidate.getProtocol()));
            RemoteCandidate relatedCandidate = component.findRemoteCandidate(relatedAddress);
            RemoteCandidate remoteCandidate = new RemoteCandidate(new TransportAddress(candidate.getIP(), candidate.getPort(), Transport.parse(candidate.getProtocol())), component, CandidateType.parse(candidate.getType().toString()), candidate.getFoundation(), candidate.getPriority(), relatedCandidate);
            if (!canReach(component, remoteCandidate))
                continue;
            if (iceAgentStateIsRunning) {
                component.addUpdateRemoteCandidates(remoteCandidate);
            } else {
                component.addRemoteCandidate(remoteCandidate);
            }
            remoteCandidateCount++;
        }
        return remoteCandidateCount;
    }

    private void configureHarvesters(Agent iceAgent, boolean rtcpmux) {
        ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(
                getBundleContext(), ConfigurationService.class);
        boolean disableDynamicHostHarvester = false;
        if (rtcpmux) {
            initializeStaticConfiguration(cfg);
            if (tcpHarvester != null)
                iceAgent.addCandidateHarvester((CandidateHarvester)tcpHarvester);
            if (singlePortHarvesters != null)
                for (CandidateHarvester harvester : singlePortHarvesters) {
                    iceAgent.addCandidateHarvester(harvester);
                    disableDynamicHostHarvester = true;
                }
        }
        if (disableDynamicHostHarvester)
            iceAgent.setUseHostHarvester(false);
    }

    private boolean canReach(Component component, RemoteCandidate remoteCandidate) {
        return component.getLocalCandidates().stream()
                .anyMatch(localCandidate -> localCandidate.canReach((Candidate)remoteCandidate));
    }

    protected void channelPropertyChange(PropertyChangeEvent ev) {
        super.channelPropertyChange(ev);
    }

    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            getChannels().forEach(this::close);
            if (this.dtlsControl != null) {
                this.dtlsControl.start(null);
                this.dtlsControl.cleanup(this);
            }
            if (this.iceStream != null)
                this.iceStream.removePairStateChangeListener(this.iceStreamPairChangeListener);
            if (this.iceAgent != null) {
                this.iceAgent.removeStateChangeListener(this.iceAgentStateChangeListener);
                this.iceAgent.free();
                this.iceAgent = null;
            }
            synchronized (this.connectThreadSyncRoot) {
                if (this.connectThread != null)
                    this.connectThread.interrupt();
            }
            super.close();
        }
    }

    public boolean close(Channel channel) {
        boolean removed = super.close(channel);
        if (removed) {
            if (channel == this.sctpConnection)
                this.sctpConnection = null;
            if (channel == this.channelForDtls) {
                if (this.sctpConnection != null) {
                    this.channelForDtls = this.sctpConnection;
                } else if (channel instanceof RtpChannel) {
                    RtpChannel newChannelForDtls = null;
                    for (Channel c : getChannels()) {
                        if (c instanceof RtpChannel)
                            newChannelForDtls = (RtpChannel)c;
                    }
                    if (newChannelForDtls != null) {
                        newChannelForDtls.getDatagramFilter(false)
                                .setAcceptNonRtp(true);
                        newChannelForDtls.getDatagramFilter(true)
                                .setAcceptNonRtp(!this.rtcpmux);
                    }
                    this.channelForDtls = newChannelForDtls;
                }
                if (channel instanceof RtpChannel) {
                    RtpChannel rtpChannel = (RtpChannel)channel;
                    rtpChannel.getDatagramFilter(false).setAcceptNonRtp(false);
                    rtpChannel.getDatagramFilter(true).setAcceptNonRtp(false);
                }
            }
            try {
                StreamConnector connector = channel.getStreamConnector();
                if (connector != null) {
                    DatagramSocket datagramSocket = connector.getDataSocket();
                    if (datagramSocket != null)
                        datagramSocket.close();
                    datagramSocket = connector.getControlSocket();
                    if (datagramSocket != null)
                        datagramSocket.close();
                    Socket socket = connector.getDataTCPSocket();
                    if (socket != null)
                        socket.close();
                    socket = connector.getControlTCPSocket();
                    if (socket != null)
                        socket.close();
                }
            } catch (IOException ioe) {
                this.logger.info("Failed to close sockets when closing a channel:" + ioe);
            }
            EventAdmin eventAdmin = this.conference.getEventAdmin();
            if (eventAdmin != null)
                eventAdmin.sendEvent(
                        EventFactory.transportChannelRemoved(channel));
            channel.transportClosed();
        }
        if (getChannels().isEmpty())
            close();
        return removed;
    }

    private DtlsControlImpl createDtlsControl() {
        DtlsControlImpl dtlsControl = new DtlsControlImpl(false);
        dtlsControl.registerUser(this);
        dtlsControl.setSetup(this.controlling ? DtlsControl.Setup.ACTPASS : DtlsControl.Setup.ACTIVE);
        dtlsControl.start(MediaType.AUDIO);
        return dtlsControl;
    }

    private Agent createIceAgent(boolean controlling, String iceStreamName, boolean rtcpmux) throws IOException {
        Agent iceAgent = new Agent(this.logger.getLevel(), iceUfragPrefix);
        configureHarvesters(iceAgent, rtcpmux);
        iceAgent.setControlling(controlling);
        iceAgent.setPerformConsentFreshness(true);
        int portBase = portTracker.getPort();
        IceMediaStream iceStream = iceAgent.createMediaStream(iceStreamName);
        iceAgent.createComponent(iceStream, Transport.UDP, portBase, portBase, portBase + 100, keepAliveStrategy, useComponentSocket);
        if (this.numComponents > 1)
            iceAgent.createComponent(iceStream, Transport.UDP, portBase + 1, portBase + 1, portBase + 101, keepAliveStrategy, useComponentSocket);
        int maxAllocatedPort = getMaxAllocatedPort(iceStream, portTracker

                .getMinPort(), portTracker
                .getMaxPort());
        if (maxAllocatedPort > 0) {
            int nextPort = 1 + maxAllocatedPort;
            portTracker.setNextPort(nextPort);
            if (this.logger.isDebugEnabled())
                this.logger.debug("Updating the port tracker min port: " + nextPort);
        }
        return iceAgent;
    }

    private int getMaxAllocatedPort(IceMediaStream iceStream, int min, int max) {
        return
                Math.max(
                        getMaxAllocatedPort(iceStream
                                .getComponent(1), min, max),

                        getMaxAllocatedPort(iceStream
                                .getComponent(2), min, max));
    }

    private int getMaxAllocatedPort(Component component, int min, int max) {
        int maxAllocatedPort = -1;
        if (component != null)
            for (LocalCandidate candidate : component.getLocalCandidates()) {
                int candidatePort = candidate.getTransportAddress().getPort();
                if (min <= candidatePort && candidatePort <= max && maxAllocatedPort < candidatePort)
                    maxAllocatedPort = candidatePort;
            }
        return maxAllocatedPort;
    }

    protected void describe(IceUdpTransportPacketExtension pe) {
        if (!this.closed) {
            pe.setPassword(this.iceAgent.getLocalPassword());
            pe.setUfrag(this.iceAgent.getLocalUfrag());
            for (Component component : this.iceStream.getComponents()) {
                List<LocalCandidate> candidates = component.getLocalCandidates();
                if (candidates != null && !candidates.isEmpty())
                    for (LocalCandidate candidate : candidates) {
                        if (candidate.getTransport() == Transport.TCP && tcpHarvesterMappedPort != -1 && candidate

                                .getTransportAddress().getPort() != tcpHarvesterMappedPort)
                            continue;
                        describe(candidate, pe);
                    }
            }
            String colibriWsUrl = getColibriWsUrl();
            if (colibriWsUrl != null) {
                WebSocketPacketExtension wsPacketExtension = new WebSocketPacketExtension(colibriWsUrl);
                pe.addChildExtension((ExtensionElement)wsPacketExtension);
            }
            if (this.rtcpmux)
                pe.addChildExtension((ExtensionElement)new RtcpmuxPacketExtension());
            describeDtlsControl(pe);
        }
    }

    private String getColibriWsUrl() {
        BundleContext bundleContext = getConference().getVideobridge().getBundleContext();
        ColibriWebSocketService colibriWebSocketService = (ColibriWebSocketService)ServiceUtils.getService(bundleContext, ColibriWebSocketService.class);
        if (colibriWebSocketService != null)
            return colibriWebSocketService.getColibriWebSocketUrl(
                    getConference().getID(), this.id, this.iceAgent

                            .getLocalPassword());
        return null;
    }

    private void describe(LocalCandidate candidate, IceUdpTransportPacketExtension pe) {
        CandidatePacketExtension candidatePE = new CandidatePacketExtension();
        Component component = candidate.getParentComponent();
        candidatePE.setComponent(component.getComponentID());
        candidatePE.setFoundation(candidate.getFoundation());
        candidatePE.setGeneration(component
                .getParentStream().getParentAgent().getGeneration());
        candidatePE.setID(generateCandidateID(candidate));
        candidatePE.setNetwork(0);
        candidatePE.setPriority(candidate.getPriority());
        Transport transport = candidate.getTransport();
        if (transport == Transport.TCP && candidate.isSSL())
            transport = Transport.SSLTCP;
        candidatePE.setProtocol(transport.toString());
        if (transport == Transport.TCP || transport == Transport.SSLTCP)
            candidatePE.setTcpType(candidate.getTcpType().toString());
        candidatePE.setType(
                org.jitsi.xmpp.extensions.jingle.CandidateType.valueOf(candidate.getType().toString()));
        TransportAddress transportAddress = candidate.getTransportAddress();
        candidatePE.setIP(transportAddress.getHostAddress());
        candidatePE.setPort(transportAddress.getPort());
        TransportAddress relatedAddress = candidate.getRelatedAddress();
        if (relatedAddress != null) {
            candidatePE.setRelAddr(relatedAddress.getHostAddress());
            candidatePE.setRelPort(relatedAddress.getPort());
        }
        pe.addChildExtension((ExtensionElement)candidatePE);
    }

    private void describeDtlsControl(IceUdpTransportPacketExtension transportPE) {
        DtlsControlImpl dtlsControl = this.dtlsControl;
        String fingerprint = dtlsControl.getLocalFingerprint();
        String hash = dtlsControl.getLocalFingerprintHashFunction();
        DtlsFingerprintPacketExtension fingerprintPE = (DtlsFingerprintPacketExtension)transportPE.getFirstChildOfType(DtlsFingerprintPacketExtension.class);
        if (fingerprintPE == null) {
            fingerprintPE = new DtlsFingerprintPacketExtension();
            transportPE.addChildExtension((ExtensionElement)fingerprintPE);
        }
        fingerprintPE.setFingerprint(fingerprint);
        fingerprintPE.setHash(hash);
        DtlsControl.Setup setup = dtlsControl.getSetup();
        if (setup != null)
            fingerprintPE.setSetup(setup.toString());
    }

    private synchronized void doStartConnectivityEstablishment(IceUdpTransportPacketExtension transport) {
        if (this.closed)
            return;
        setRtcpmux(transport);
        setRemoteFingerprints(transport);
        IceProcessingState iceAgentState = this.iceAgent.getState();
        if (iceAgentState.isEstablished())
            return;
        boolean iceAgentStateIsRunning = IceProcessingState.RUNNING.equals(iceAgentState);
        if (this.rtcpmux) {
            Component rtcpComponent = this.iceStream.getComponent(2);
            if (rtcpComponent != null)
                this.iceStream.removeComponent(rtcpComponent);
        }
        setRemoteUfragAndPwd(transport);
        List<CandidatePacketExtension> candidates = transport.getChildExtensionsOfType(CandidatePacketExtension.class);
        if (iceAgentStateIsRunning && candidates.isEmpty())
            return;
        int remoteCandidateCount = addRemoteCandidates(candidates, iceAgentStateIsRunning);
        if (iceAgentStateIsRunning) {
            if (remoteCandidateCount != 0)
                this.iceAgent.getStreams()
                        .forEach(stream -> stream.getComponents().forEach(Component::updateRemoteCandidates));
        } else if (remoteCandidateCount != 0)   {
            // Once again because the ICE Agent does not support adding
            // candidates after the connectivity establishment has been started
            // and because multiple transport-info JingleIQs may be used to send
            // the whole set of transport candidates from the remote peer to the
            // local peer, do not really start the connectivity establishment
            // until we have at least one remote candidate per ICE Component.
            for (IceMediaStream stream : iceAgent.getStreams())
            {
                for (Component component : stream.getComponents())
                {
                    if (component.getRemoteCandidateCount() < 1)
                    {
                        remoteCandidateCount = 0;
                        break;
                    }
                }
                if (remoteCandidateCount == 0)
                    break;
            }
            if (remoteCandidateCount != 0)
                iceAgent.startConnectivityEstablishment();
        } else if (this.iceStream.getRemoteUfrag() != null && this.iceStream
                .getRemotePassword() != null) {
            this.logger.info("Starting ICE agent without remote candidates.");
            this.iceAgent.startConnectivityEstablishment();
        }
    }

    private String generateCandidateID(LocalCandidate candidate) {
        StringBuilder candidateID = new StringBuilder();
        candidateID.append(this.conference.getID());
        candidateID.append(Long.toHexString(hashCode()));
        Agent iceAgent = candidate.getParentComponent().getParentStream().getParentAgent();
        candidateID.append(Long.toHexString(iceAgent.hashCode()));
        candidateID.append(Long.toHexString(iceAgent.getGeneration()));
        candidateID.append(Long.toHexString(candidate.hashCode()));
        return candidateID.toString();
    }

    public Conference getConference() {
        return this.conference;
    }

    public int getNumComponents() {
        return this.numComponents;
    }

    public String getLocalUfrag() {
        Agent iceAgent = this.iceAgent;
        return (iceAgent == null) ? null : iceAgent.getLocalUfrag();
    }

    public String getIcePassword() {
        Agent iceAgent = this.iceAgent;
        return (iceAgent == null) ? null : iceAgent.getLocalPassword();
    }

    public IceMediaStream getIceStream() {
        return this.iceStream;
    }

    public boolean isControlling() {
        return this.controlling;
    }

    public boolean isRtcpmux() {
        return this.rtcpmux;
    }

    public BundleContext getBundleContext() {
        return (this.conference != null) ? this.conference.getBundleContext() : null;
    }

    public SrtpControl getSrtpControl(Channel channel) {
        return (SrtpControl)this.dtlsControl;
    }

    public StreamConnector getStreamConnector(Channel channel) {
        IceSocketWrapper rtcpSocket;
        if (!getChannels().contains(channel))
            return null;
        IceSocketWrapper rtpSocket = getSocketForComponent(this.iceStream.getComponent(1));
        if (this.numComponents > 1 && !this.rtcpmux) {
            rtcpSocket = getSocketForComponent(this.iceStream.getComponent(2));
        } else {
            rtcpSocket = rtpSocket;
        }
        if (rtpSocket == null || rtcpSocket == null)
            throw new IllegalStateException("No sockets from ice4j.");
        if (channel instanceof SctpConnection) {
            DatagramSocket udpSocket = rtpSocket.getUDPSocket();
            Socket tcpSocket = rtpSocket.getTCPSocket();
            if (udpSocket instanceof MultiplexingDatagramSocket) {
                MultiplexingDatagramSocket multiplexing = (MultiplexingDatagramSocket)udpSocket;
                try {
                    MultiplexedDatagramSocket multiplexedDatagramSocket = multiplexing.getSocket((DatagramPacketFilter)new DTLSDatagramFilter());
                    return (StreamConnector)new DefaultStreamConnector((DatagramSocket)multiplexedDatagramSocket, null);
                } catch (SocketException se) {
                    this.logger.warn("Failed to create DTLS socket: " + se);
                }
            } else if (tcpSocket instanceof MultiplexingSocket) {
                MultiplexingSocket multiplexing = (MultiplexingSocket)tcpSocket;
                try {
                    MultiplexedSocket multiplexedSocket = multiplexing.getSocket((DatagramPacketFilter)new DTLSDatagramFilter());
                    return (StreamConnector)new DefaultTCPStreamConnector((Socket)multiplexedSocket, null);
                } catch (IOException ioe) {
                    this.logger.warn("Failed to create DTLS socket: " + ioe);
                }
            } else {
                this.logger.warn("No valid sockets from ice4j.");
                return null;
            }
        }
        if (!(channel instanceof RtpChannel))
            return null;
        RtpChannel rtpChannel = (RtpChannel)channel;
        DatagramSocket rtpUdpSocket = rtpSocket.getUDPSocket();
        DatagramSocket rtcpUdpSocket = rtcpSocket.getUDPSocket();
        if (rtpUdpSocket instanceof MultiplexingDatagramSocket && rtcpUdpSocket instanceof MultiplexingDatagramSocket)
            return getUDPStreamConnector(rtpChannel, (MultiplexingDatagramSocket)rtpUdpSocket, (MultiplexingDatagramSocket)rtcpUdpSocket);
        Socket rtpTcpSocket = rtpSocket.getTCPSocket();
        Socket rtcpTcpSocket = rtcpSocket.getTCPSocket();
        if (rtpTcpSocket instanceof MultiplexingSocket && rtcpTcpSocket instanceof MultiplexingSocket)
            return getTCPStreamConnector(rtpChannel, (MultiplexingSocket)rtpTcpSocket, (MultiplexingSocket)rtcpTcpSocket);
        this.logger.warn("No valid sockets from ice4j");
        return null;
    }

    private StreamConnector getUDPStreamConnector(RtpChannel rtpChannel, MultiplexingDatagramSocket rtpSocket, MultiplexingDatagramSocket rtcpSocket) {
        Objects.requireNonNull(rtpSocket, "rtpSocket");
        Objects.requireNonNull(rtcpSocket, "rtcpSocket");
        try {
            MultiplexedDatagramSocket channelRtpSocket = rtpSocket.getSocket(rtpChannel
                    .getDatagramFilter(false));
            MultiplexedDatagramSocket channelRtcpSocket = rtcpSocket.getSocket(rtpChannel
                    .getDatagramFilter(true));
            return (StreamConnector)new DefaultStreamConnector((DatagramSocket)channelRtpSocket, (DatagramSocket)channelRtcpSocket, this.rtcpmux);
        } catch (SocketException se) {
            this.logger.error("An unexpected exception occurred.", se);
            return null;
        }
    }

    private StreamConnector getTCPStreamConnector(RtpChannel rtpChannel, MultiplexingSocket rtpSocket, MultiplexingSocket rtcpSocket) {
        Objects.requireNonNull(rtpSocket, "rtpSocket");
        Objects.requireNonNull(rtcpSocket, "rtcpSocket");
        try {
            MultiplexedSocket channelRtpSocket = rtpSocket.getSocket(rtpChannel
                    .getDatagramFilter(false));
            MultiplexedSocket channelRtcpSocket = rtcpSocket.getSocket(rtpChannel
                    .getDatagramFilter(true));
            return (StreamConnector)new DefaultTCPStreamConnector((Socket)channelRtpSocket, (Socket)channelRtcpSocket, this.rtcpmux);
        } catch (SocketException se) {
            this.logger.error("An unexpected exception occurred.", se);
            return null;
        }
    }

    private IceSocketWrapper getSocketForComponent(Component component) {
        if (useComponentSocket)
            return component.getSocketWrapper();
        CandidatePair selectedPair = component.getSelectedPair();
        return (selectedPair == null) ? null : selectedPair

                .getIceSocketWrapper();
    }

    private MediaStreamTarget getStreamTarget() {
        MediaStreamTarget streamTarget = null;
        InetSocketAddress[] streamTargetAddresses = new InetSocketAddress[2];
        int streamTargetAddressCount = 0;
        Component rtpComponent = this.iceStream.getComponent(1);
        if (rtpComponent != null) {
            CandidatePair selectedPair = rtpComponent.getSelectedPair();
            if (selectedPair != null) {
                TransportAddress transportAddress = selectedPair.getRemoteCandidate().getTransportAddress();
                if (transportAddress != null) {
                    streamTargetAddresses[0] = (InetSocketAddress)transportAddress;
                    streamTargetAddressCount++;
                }
            }
        }
        if (this.rtcpmux) {
            streamTargetAddresses[1] = streamTargetAddresses[0];
            streamTargetAddressCount++;
        } else if (this.numComponents > 1) {
            Component rtcpComponent = this.iceStream.getComponent(2);
            if (rtcpComponent != null) {
                CandidatePair selectedPair = rtcpComponent.getSelectedPair();
                if (selectedPair != null) {
                    TransportAddress transportAddress = selectedPair.getRemoteCandidate().getTransportAddress();
                    if (transportAddress != null) {
                        streamTargetAddresses[1] = (InetSocketAddress)transportAddress;
                        streamTargetAddressCount++;
                    }
                }
            }
        }
        if (streamTargetAddressCount > 0)
            streamTarget = new MediaStreamTarget(streamTargetAddresses[0], streamTargetAddresses[1]);
        return streamTarget;
    }

    public MediaStreamTarget getStreamTarget(Channel channel) {
        return getStreamTarget();
    }

    public String getXmlNamespace() {
        return "urn:xmpp:jingle:transports:ice-udp:1";
    }

    private void iceAgentStateChange(PropertyChangeEvent ev) {
        boolean interrupted = false;
        try {
            IceProcessingState oldState = (IceProcessingState)ev.getOldValue();
            IceProcessingState newState = (IceProcessingState)ev.getNewValue();
            this.logger.info(Logger.Category.STATISTICS, "ice_state_change," +
                    getLoggingId() + " old_state=" + oldState + ",new_state=" + newState);
            EventAdmin eventAdmin = this.conference.getEventAdmin();
            if (eventAdmin != null)
                eventAdmin.sendEvent(
                        EventFactory.transportStateChanged(this, oldState, newState));
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                interrupted = true;
            } else if (t instanceof ThreadDeath) {
                throw (ThreadDeath)t;
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    private void iceStreamPairChange(PropertyChangeEvent ev) {
        if ("PairConsentFreshnessChanged".equals(ev
                .getPropertyName()))
            getChannels().forEach(channel -> channel.touch(Channel.ActivityType.TRANSPORT));
    }

    private void onIceConnected() {
        this.iceConnected = true;
        if (this.conference.includeInStatistics()) {
            Transport transport = getTransport();
            if (transport == null) {
                this.logger.warn("Cannot get transport type.");
            } else {
                Conference.Statistics statistics = this.conference.getStatistics();
                if (transport == Transport.TCP || transport == Transport.SSLTCP) {
                    statistics.totalTcpTransportManagers.incrementAndGet();
                } else if (transport == Transport.UDP) {
                    statistics.totalUdpTransportManagers.incrementAndGet();
                }
            }
        }
        EventAdmin eventAdmin = this.conference.getEventAdmin();
        if (eventAdmin != null)
            eventAdmin.sendEvent(EventFactory.transportConnected(this));
        getChannels().forEach(Channel::transportConnected);
    }

    private static final String PERMANENT_FAILURE_PNAME = Health.class
            .getName() + ".PERMANENT_FAILURE";

    private static BundleContext bundleContext = null;

    private static boolean permanentFailureMode = false;

    private Transport getTransport() {
        Transport transport = null;
        Component component = this.iceStream.getComponent(1);
        if (component != null) {
            CandidatePair selectedPair = component.getSelectedPair();
            if (selectedPair != null)
                transport = selectedPair.getLocalCandidate().getHostAddress().getTransport();
        }
        return transport;
    }

    private void setRemoteFingerprints(IceUdpTransportPacketExtension transport) {
        List<DtlsFingerprintPacketExtension> dfpes = transport.getChildExtensionsOfType(DtlsFingerprintPacketExtension.class);
        if (!dfpes.isEmpty()) {
            Map<String, String> remoteFingerprints = new LinkedHashMap<>();
            boolean setSetup = true;
            DtlsControl.Setup setup = null;
            for (DtlsFingerprintPacketExtension dfpe : dfpes) {
                remoteFingerprints.put(dfpe
                        .getHash(), dfpe
                        .getFingerprint());
                if (setSetup) {
                    String aSetupStr = dfpe.getSetup();
                    DtlsControl.Setup aSetup = null;
                    if (!StringUtils.isNullOrEmpty(aSetupStr, true))
                        try {
                            aSetup = DtlsControl.Setup.parseSetup(aSetupStr);
                        } catch (IllegalArgumentException e) {
                            this.logger.debug("Unable to parse: " + aSetupStr, e);
                        }
                    if (aSetup == null) {
                        setSetup = false;
                        continue;
                    }
                    if (setup == null) {
                        setup = aSetup;
                        continue;
                    }
                    if (!setup.equals(aSetup))
                        setSetup = false;
                }
            }
            if (setSetup && setup != null)
                switch (setup) {
                    case ACTIVE:
                        if (DtlsControl.Setup.ACTIVE.equals(this.dtlsControl.getSetup()))
                            this.dtlsControl.setSetup(DtlsControl.Setup.ACTPASS);
                        break;
                    case PASSIVE:
                        this.dtlsControl.setSetup(DtlsControl.Setup.ACTIVE);
                        break;
                }
            this.dtlsControl.setRemoteFingerprints(remoteFingerprints);
        }
    }

    private void setRemoteUfragAndPwd(IceUdpTransportPacketExtension transport) {
        String ufrag = transport.getUfrag();
        if (ufrag != null)
            this.iceStream.setRemoteUfrag(ufrag);
        String password = transport.getPassword();
        if (password != null)
            this.iceStream.setRemotePassword(password);
    }

    private void setRtcpmux(IceUdpTransportPacketExtension transport) {
        if (transport.isRtcpMux()) {
            this.rtcpmux = true;
            if (this.channelForDtls != null && this.channelForDtls instanceof RtpChannel)
                ((RtpChannel)this.channelForDtls)
                        .getDatagramFilter(true)
                        .setAcceptNonRtp(false);
        }
        this.dtlsControl.setRtcpmux(this.rtcpmux);
    }

    public void startConnectivityEstablishment(IceUdpTransportPacketExtension transport) {
        doStartConnectivityEstablishment(transport);
        synchronized (this.connectThreadSyncRoot) {
            if (this.connectThread == null) {
                this.connectThread = new Thread(() -> {
                    try {
                        wrapupConnectivityEstablishment();
                    } catch (OperationFailedException ofe) {
                        this.logger.info("Failed to connect IceUdpTransportManager: " + ofe);
                        synchronized (this.connectThreadSyncRoot) {
                            this.connectThread = null;
                            return;
                        }
                    }
                    Agent iceAgent = this.iceAgent;
                    if (iceAgent == null)
                        return;
                    IceProcessingState state = iceAgent.getState();
                    if (state.isEstablished()) {
                        onIceConnected();
                    } else {
                        this.logger.log(Level.WARNING, Logger.Category.STATISTICS, "ice_failed," + getLoggingId() + " state=" + state);
                    }
                });
                this.connectThread.setDaemon(true);
                this.connectThread.setName("IceUdpTransportManager connect thread");
                this.connectThread.start();
            }
        }
    }

    private void wrapupConnectivityEstablishment() throws OperationFailedException {
        // Byte code:
        //   0: new java/lang/Object
        //   3: dup
        //   4: invokespecial <init> : ()V
        //   7: astore_1
        //   8: new org/jitsi/videobridge/IceUdpTransportManager$1
        //   11: dup
        //   12: aload_0
        //   13: aload_1
        //   14: invokespecial <init> : (Lorg/jitsi/videobridge/IceUdpTransportManager;Ljava/lang/Object;)V
        //   17: astore_2
        //   18: aload_0
        //   19: getfield iceAgent : Lorg/ice4j/ice/Agent;
        //   22: astore_3
        //   23: aload_3
        //   24: ifnonnull -> 28
        //   27: return
        //   28: aload_3
        //   29: aload_2
        //   30: invokevirtual addStateChangeListener : (Ljava/beans/PropertyChangeListener;)V
        //   33: iconst_0
        //   34: istore #4
        //   36: aload_3
        //   37: invokevirtual getState : ()Lorg/ice4j/ice/IceProcessingState;
        //   40: astore #5
        //   42: aload_1
        //   43: dup
        //   44: astore #6
        //   46: monitorenter
        //   47: getstatic org/ice4j/ice/IceProcessingState.RUNNING : Lorg/ice4j/ice/IceProcessingState;
        //   50: aload #5
        //   52: invokevirtual equals : (Ljava/lang/Object;)Z
        //   55: ifne -> 69
        //   58: getstatic org/ice4j/ice/IceProcessingState.WAITING : Lorg/ice4j/ice/IceProcessingState;
        //   61: aload #5
        //   63: invokevirtual equals : (Ljava/lang/Object;)Z
        //   66: ifeq -> 137
        //   69: aload_1
        //   70: ldc2_w 1000
        //   73: invokevirtual wait : (J)V
        //   76: aload_3
        //   77: invokevirtual getState : ()Lorg/ice4j/ice/IceProcessingState;
        //   80: astore #5
        //   82: aload_0
        //   83: getfield iceAgent : Lorg/ice4j/ice/Agent;
        //   86: ifnonnull -> 134
        //   89: goto -> 137
        //   92: astore #7
        //   94: iconst_1
        //   95: istore #4
        //   97: aload_3
        //   98: invokevirtual getState : ()Lorg/ice4j/ice/IceProcessingState;
        //   101: astore #5
        //   103: aload_0
        //   104: getfield iceAgent : Lorg/ice4j/ice/Agent;
        //   107: ifnonnull -> 134
        //   110: goto -> 137
        //   113: astore #8
        //   115: aload_3
        //   116: invokevirtual getState : ()Lorg/ice4j/ice/IceProcessingState;
        //   119: astore #5
        //   121: aload_0
        //   122: getfield iceAgent : Lorg/ice4j/ice/Agent;
        //   125: ifnonnull -> 131
        //   128: goto -> 137
        //   131: aload #8
        //   133: athrow
        //   134: goto -> 47
        //   137: aload #6
        //   139: monitorexit
        //   140: goto -> 151
        //   143: astore #9
        //   145: aload #6
        //   147: monitorexit
        //   148: aload #9
        //   150: athrow
        //   151: iload #4
        //   153: ifeq -> 162
        //   156: invokestatic currentThread : ()Ljava/lang/Thread;
        //   159: invokevirtual interrupt : ()V
        //   162: aload_3
        //   163: aload_2
        //   164: invokevirtual removeStateChangeListener : (Ljava/beans/PropertyChangeListener;)V
        //   167: aload_0
        //   168: getfield iceAgent : Lorg/ice4j/ice/Agent;
        //   171: ifnonnull -> 186
        //   174: new net/java/sip/communicator/service/protocol/OperationFailedException
        //   177: dup
        //   178: ldc_w 'TransportManager closed'
        //   181: iconst_1
        //   182: invokespecial <init> : (Ljava/lang/String;I)V
        //   185: athrow
        //   186: getstatic org/ice4j/ice/IceProcessingState.FAILED : Lorg/ice4j/ice/IceProcessingState;
        //   189: aload #5
        //   191: invokevirtual equals : (Ljava/lang/Object;)Z
        //   194: ifeq -> 209
        //   197: new net/java/sip/communicator/service/protocol/OperationFailedException
        //   200: dup
        //   201: ldc_w 'ICE failed'
        //   204: iconst_1
        //   205: invokespecial <init> : (Ljava/lang/String;I)V
        //   208: athrow
        //   209: return
        // Line number table:
        //   Java source line number -> byte code offset
        //   #2199	-> 0
        //   #2200	-> 8
        //   #2225	-> 18
        //   #2226	-> 23
        //   #2230	-> 27
        //   #2233	-> 28
        //   #2236	-> 33
        //   #2238	-> 36
        //   #2239	-> 42
        //   #2241	-> 47
        //   #2242	-> 63
        //   #2246	-> 69
        //   #2254	-> 76
        //   #2255	-> 82
        //   #2257	-> 89
        //   #2248	-> 92
        //   #2250	-> 94
        //   #2254	-> 97
        //   #2255	-> 103
        //   #2257	-> 110
        //   #2254	-> 113
        //   #2255	-> 121
        //   #2257	-> 128
        //   #2259	-> 131
        //   #2261	-> 137
        //   #2263	-> 151
        //   #2265	-> 156
        //   #2270	-> 162
        //   #2273	-> 167
        //   #2275	-> 174
        //   #2279	-> 186
        //   #2281	-> 197
        //   #2285	-> 209
        // Local variable table:
        //   start	length	slot	name	descriptor
        //   94	3	7	ie	Ljava/lang/InterruptedException;
        //   0	210	0	this	Lorg/jitsi/videobridge/IceUdpTransportManager;
        //   8	202	1	syncRoot	Ljava/lang/Object;
        //   18	192	2	propertyChangeListener	Ljava/beans/PropertyChangeListener;
        //   23	187	3	iceAgent	Lorg/ice4j/ice/Agent;
        //   36	174	4	interrupted	Z
        //   42	168	5	state	Lorg/ice4j/ice/IceProcessingState;
        // Exception table:
        //   from	to	target	type
        //   47	140	143	finally
        //   69	76	92	java/lang/InterruptedException
        //   69	76	113	finally
        //   92	97	113	finally
        //   113	115	113	finally
        //   143	148	143	finally
    }

    public boolean isConnected() {
        return this.iceConnected;
    }

    private String getLoggingId() {
        return Channel.getLoggingId(this.channelForDtls);
    }

    public TransportCCEngine getTransportCCEngine() {
        return this.transportCCEngine;
    }
}
