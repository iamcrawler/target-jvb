
package org.jitsi.videobridge;
/*      */
/*      */ 
/*      */ import java.beans.PropertyChangeEvent;
/*      */ import java.beans.PropertyChangeListener;
/*      */ import java.io.IOException;
/*      */ import java.net.DatagramPacket;
/*      */ import java.net.InetAddress;
/*      */ import java.net.InetSocketAddress;
/*      */ import java.net.URI;
/*      */ import java.util.ArrayList;
/*      */ import java.util.Collection;
/*      */ import java.util.Collections;
/*      */ import java.util.HashSet;
/*      */ import java.util.List;
/*      */ import java.util.Map;
/*      */ import java.util.Set;
/*      */ import javax.media.rtp.RTPConnector;
/*      */ import javax.media.rtp.SessionAddress;
/*      */ import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.JingleUtils;
/*      */ import net.java.sip.communicator.util.ServiceUtils;
/*      */ import org.jitsi.eventadmin.EventAdmin;
/*      */ import org.jitsi.impl.neomedia.MediaStreamImpl;
/*      */ import org.jitsi.impl.neomedia.RTPConnectorInputStream;
/*      */ import org.jitsi.impl.neomedia.SSRCFactoryImpl;
/*      */ import org.jitsi.impl.neomedia.rtp.MediaStreamTrackDesc;
/*      */ import org.jitsi.impl.neomedia.rtp.MediaStreamTrackReceiver;
/*      */ import org.jitsi.impl.neomedia.rtp.TransportCCEngine;
/*      */ import org.jitsi.impl.neomedia.transform.PacketTransformer;
/*      */ import org.jitsi.impl.neomedia.transform.TransformEngine;
/*      */ import org.jitsi.impl.neomedia.transform.zrtp.ZrtpRawPacket;
/*      */ import org.jitsi.service.configuration.ConfigurationService;
/*      */ import org.jitsi.service.neomedia.MediaDirection;
/*      */ import org.jitsi.service.neomedia.MediaService;
/*      */ import org.jitsi.service.neomedia.MediaStream;
/*      */ import org.jitsi.service.neomedia.MediaStreamTarget;
/*      */ import org.jitsi.service.neomedia.RTPExtension;
/*      */ import org.jitsi.service.neomedia.RawPacket;
/*      */ import org.jitsi.service.neomedia.RetransmissionRequester;
/*      */ import org.jitsi.service.neomedia.SSRCFactory;
/*      */ import org.jitsi.service.neomedia.StreamConnector;
/*      */ import org.jitsi.service.neomedia.device.MediaDevice;
/*      */ import org.jitsi.service.neomedia.format.MediaFormat;
/*      */ import org.jitsi.service.neomedia.recording.Recorder;
/*      */ import org.jitsi.service.neomedia.recording.Synchronizer;
/*      */ import org.jitsi.service.neomedia.stats.MediaStreamStats2;
/*      */ import org.jitsi.service.neomedia.stats.SendTrackStats;
/*      */ import org.jitsi.util.RTPUtils;
/*      */ import org.jitsi.utils.DatagramPacketFilter;
/*      */ import org.jitsi.utils.MediaType;
/*      */ import org.jitsi.utils.event.WeakReferencePropertyChangeListener;
/*      */ import org.jitsi.utils.logging.DiagnosticContext;
/*      */ import org.jitsi.utils.logging.Logger;
/*      */ import org.jitsi.videobridge.transform.RtpChannelTransformEngine;
/*      */ import org.jitsi.videobridge.xmpp.ComponentImpl;
/*      */ import org.jitsi.videobridge.xmpp.MediaStreamTrackFactory;
/*      */ import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
/*      */ import org.jitsi.xmpp.extensions.colibri.RTPLevelRelayType;
/*      */ import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
/*      */ import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension;
/*      */ import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension;
/*      */ import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension;
/*      */ import org.jivesoftware.smack.packet.IQ;
/*      */ import org.jxmpp.jid.Jid;
/*      */ import org.osgi.framework.BundleContext;
/*      */ import org.osgi.framework.InvalidSyntaxException;
/*      */ import org.osgi.framework.ServiceReference;
/*      */ 
/*      */ public class RtpChannel extends Channel implements PropertyChangeListener {
/*      */   private static final String DISABLE_ABS_SEND_TIME_PNAME = "org.jitsi.videobridge.DISABLE_ABS_SEND_TIME";
/*      */   
/*   76 */   private static final Logger classLogger = Logger.getLogger(RtpChannel.class);
/*      */   
/*   83 */   private static final long[] NO_RECEIVE_SSRCS = new long[0];
/*      */   
/*   89 */   private static final int[] EMPTY_INT_ARRAY = new int[0];
/*      */   
/*      */   private static final int MAX_RECEIVE_SSRCS = 50;
/*      */   
/*      */   protected final ConferenceSpeechActivity conferenceSpeechActivity;
/*      */   
/*      */   static RtpChannel getChannel(MediaStream stream) {
/*  108 */     return (RtpChannel)stream.getProperty(RtpChannel.class.getName());
/*      */   }
/*      */   
/*  122 */   private final RtpChannelDatagramFilter[] datagramFilters = new RtpChannelDatagramFilter[2];
/*      */   
/*      */   private final long initialLocalSSRC;
/*      */   
/*  136 */   private final PropertyChangeListener propertyChangeListener = (PropertyChangeListener)new WeakReferencePropertyChangeListener(this);
/*      */   
/*  142 */   private int[] receivePTs = new int[0];
/*      */   
/*  155 */   private long[] receiveSSRCs = NO_RECEIVE_SSRCS;
/*      */   
/*  161 */   private final Object receiveSSRCsSyncRoot = new Object();
/*      */   
/*      */   private RTPLevelRelayType rtpLevelRelayType;
/*      */   
/*      */   private MediaStream stream;
/*      */   
/*  181 */   private final Object streamSyncRoot = new Object();
/*      */   
/*      */   private boolean streamClosed = false;
/*      */   
/*  192 */   private final PropertyChangeListener streamPropertyChangeListener = new PropertyChangeListener() {
/*      */       public void propertyChange(PropertyChangeEvent ev) {
/*  198 */         RtpChannel.this.streamPropertyChange(ev);
/*      */       }
/*      */     };
/*      */   
/*  212 */   private final SessionAddress streamTarget = new SessionAddress();
/*      */   
/*  217 */   RtpChannelTransformEngine transformEngine = null;
/*      */   
/*      */   private final Logger logger;
/*      */   
/*      */   private boolean verifyRemoteAddress = true;
/*      */   
/*  240 */   protected final Statistics statistics = new Statistics();
/*      */   
/*  245 */   private Set<Integer> signaledSSRCs = new HashSet<>();
/*      */   
/*      */   public RtpChannel(Content content, String id, String channelBundleId, String transportNamespace, Boolean initiator) {
/*  274 */     super(content, id, channelBundleId, transportNamespace, initiator);
/*  276 */     this
/*  277 */       .logger = Logger.getLogger(classLogger, content
/*      */         
/*  279 */         .getConference().getLogger());
/*  286 */     this.initialLocalSSRC = Videobridge.RANDOM.nextLong() & 0xFFFFFFFFL;
/*  288 */     Conference conference = content.getConference();
/*  289 */     conference.addPropertyChangeListener(this.propertyChangeListener);
/*  291 */     this.conferenceSpeechActivity = conference.getSpeechActivity();
/*  292 */     if (this.conferenceSpeechActivity != null)
/*  298 */       this.conferenceSpeechActivity.addPropertyChangeListener(this.propertyChangeListener); 
/*  302 */     content.addPropertyChangeListener(this.propertyChangeListener);
/*  304 */     if ("urn:xmpp:jingle:transports:ice-udp:1".equals(this.transportNamespace))
/*  307 */       this.verifyRemoteAddress = false; 
/*  310 */     touch();
/*      */   }
/*      */   
/*      */   protected boolean acceptControlInputStreamDatagramPacket(DatagramPacket p) {
/*      */     boolean accept;
/*  330 */     InetAddress ctrlAddr = this.streamTarget.getControlAddress();
/*  331 */     int ctrlPort = this.streamTarget.getControlPort();
/*  334 */     if (ctrlAddr == null) {
/*  336 */       this.streamTarget.setControlHostAddress(p.getAddress());
/*  337 */       this.streamTarget.setControlPort(p.getPort());
/*  339 */       InetAddress dataAddr = this.streamTarget.getDataAddress();
/*  340 */       int dataPort = this.streamTarget.getDataPort();
/*  342 */       if (dataAddr != null) {
/*  344 */         ctrlAddr = this.streamTarget.getControlAddress();
/*  345 */         ctrlPort = this.streamTarget.getControlPort();
/*  347 */         this.stream.setTarget(new MediaStreamTarget(dataAddr, dataPort, ctrlAddr, ctrlPort));
/*      */       } 
/*  353 */       accept = true;
/*      */     } else {
/*  359 */       accept = (!this.verifyRemoteAddress || (ctrlAddr.equals(p.getAddress()) && ctrlPort == p.getPort()));
/*      */     } 
/*  362 */     if (accept) {
/*  365 */       touch(Channel.ActivityType.PAYLOAD);
/*  371 */       if (p.getLength() > 8) {
/*  373 */         byte[] data = p.getData();
/*  374 */         int offset = p.getOffset();
/*  375 */         byte b0 = data[offset];
/*  377 */         if ((b0 & 0xC0) >>> 6 == 2 && (data[offset + 1] & 0xFF) == 203) {
/*  381 */           int sc = b0 & 0x1F;
/*  383 */           if (sc > 0) {
/*  394 */             int ssrc = RTPUtils.readInt(data, offset + 4);
/*  396 */             if (removeReceiveSSRC(ssrc))
/*  398 */               notifyFocus(); 
/*      */           } 
/*      */         } 
/*      */       } 
/*      */     } 
/*  405 */     return accept;
/*      */   }
/*      */   
/*      */   protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p) {
/*      */     boolean accept;
/*  424 */     InetAddress dataAddr = this.streamTarget.getDataAddress();
/*  425 */     int dataPort = this.streamTarget.getDataPort();
/*  428 */     if (dataAddr == null) {
/*      */       MediaStreamTarget newStreamTarget;
/*  430 */       this.streamTarget.setDataHostAddress(p.getAddress());
/*  431 */       this.streamTarget.setDataPort(p.getPort());
/*  432 */       dataAddr = this.streamTarget.getDataAddress();
/*  433 */       dataPort = this.streamTarget.getDataPort();
/*  435 */       InetAddress ctrlAddr = this.streamTarget.getControlAddress();
/*  436 */       int ctrlPort = this.streamTarget.getControlPort();
/*  439 */       if (ctrlAddr == null) {
/*  441 */         newStreamTarget = new MediaStreamTarget(new InetSocketAddress(dataAddr, dataPort), null);
/*      */       } else {
/*  448 */         newStreamTarget = new MediaStreamTarget(dataAddr, dataPort, ctrlAddr, ctrlPort);
/*      */       } 
/*  453 */       this.stream.setTarget(newStreamTarget);
/*  455 */       accept = true;
/*      */     } else {
/*  461 */       accept = (!this.verifyRemoteAddress || (dataAddr.equals(p.getAddress()) && dataPort == p.getPort()));
/*      */     } 
/*  464 */     if (accept) {
/*  467 */       touch(Channel.ActivityType.PAYLOAD);
/*  473 */       if (p.getLength() >= 12) {
/*  475 */         byte[] data = p.getData();
/*  476 */         int off = p.getOffset();
/*  477 */         int v = (data[off] & 0xC0) >>> 6;
/*  486 */         if (v == 0) {
/*  488 */           if ((data[off] & 0x10) == 16) {
/*  490 */             byte[] zrtpMagicCookie = ZrtpRawPacket.ZRTP_MAGIC;
/*  492 */             if (data[off + 4] == zrtpMagicCookie[0] && data[off + 5] == zrtpMagicCookie[1] && data[off + 6] == zrtpMagicCookie[2] && data[off + 7] == zrtpMagicCookie[3])
/*  497 */               accept = false; 
/*      */           } 
/*  501 */         } else if (v == 2) {
/*      */           boolean notify;
/*  511 */           int ssrc = RTPUtils.readInt(data, off + 8);
/*      */           try {
/*  516 */             notify = addReceiveSSRC(ssrc, true);
/*  518 */           } catch (SizeExceededException see) {
/*  522 */             accept = false;
/*  523 */             notify = false;
/*      */           } 
/*  531 */           if (notify && getContent().isRecording()) {
/*  533 */             Recorder recorder = getContent().getRecorder();
/*  535 */             if (recorder != null) {
/*  537 */               AbstractEndpoint endpoint = getEndpoint();
/*  539 */               if (endpoint != null) {
/*  542 */                 Synchronizer synchronizer = recorder.getSynchronizer();
/*  544 */                 if (synchronizer != null)
/*  546 */                   synchronizer.setEndpoint(ssrc & 0xFFFFFFFFL, endpoint
/*      */                       
/*  548 */                       .getID()); 
/*      */               } 
/*      */             } 
/*      */           } 
/*  560 */           if (RTPLevelRelayType.MIXER.equals(getRTPLevelRelayType())) {
/*  563 */             Map<Byte, MediaFormat> payloadTypes = this.stream.getDynamicRTPPayloadTypes();
/*  565 */             if (payloadTypes != null) {
/*  567 */               int pt = data[off + 1] & Byte.MAX_VALUE;
/*  568 */               MediaFormat format = payloadTypes.get(Byte.valueOf((byte)pt));
/*  570 */               if (format != null && 
/*  571 */                 !format.equals(this.stream.getFormat())) {
/*  573 */                 this.stream.setFormat(format);
/*  574 */                 synchronized (this.streamSyncRoot) {
/*  576 */                   this.stream.setDirection(MediaDirection.SENDRECV);
/*      */                 } 
/*  578 */                 notify = true;
/*      */               } 
/*      */             } 
/*      */           } 
/*  583 */           if (notify)
/*  585 */             notifyFocus(); 
/*      */         } 
/*      */       } 
/*      */     } 
/*  591 */     return accept;
/*      */   }
/*      */   
/*      */   private boolean addReceiveSSRC(int receiveSSRC, boolean checkLimit) throws SizeExceededException {
/*  614 */     synchronized (this.receiveSSRCsSyncRoot) {
/*  617 */       long now = System.currentTimeMillis();
/*  620 */       int length = this.receiveSSRCs.length;
/*  622 */       for (int i = 0; i < length; i += 2) {
/*  624 */         if ((int)this.receiveSSRCs[i] == receiveSSRC) {
/*  626 */           this.receiveSSRCs[i + 1] = now;
/*  632 */           return false;
/*      */         } 
/*      */       } 
/*  636 */       if (checkLimit && length >= 25)
/*  638 */         throw new SizeExceededException(); 
/*  642 */       long[] newReceiveSSRCs = new long[length + 2];
/*  644 */       System.arraycopy(this.receiveSSRCs, 0, newReceiveSSRCs, 0, length);
/*  645 */       newReceiveSSRCs[length] = 0xFFFFFFFFL & receiveSSRC;
/*  646 */       newReceiveSSRCs[length + 1] = now;
/*  647 */       this.receiveSSRCs = newReceiveSSRCs;
/*  649 */       return true;
/*      */     } 
/*      */   }
/*      */   
/*      */   protected void closeStream() {
/*  660 */     if (!this.streamClosed && this.stream != null) {
/*  662 */       MediaStreamStats2 mss = this.stream.getMediaStreamStats();
/*  663 */       this.statistics.bytesReceived = mss.getReceiveStats().getBytes();
/*  664 */       this.statistics.bytesSent = mss.getSendStats().getBytes();
/*  665 */       this.statistics.packetsReceived = mss.getReceiveStats().getPackets();
/*  666 */       this.statistics.packetsSent = mss.getSendStats().getPackets();
/*  667 */       this.stream.setProperty(RtpChannel.class.getName(), null);
/*  668 */       removeStreamListeners();
/*  669 */       this.stream.close();
/*  671 */       this.streamClosed = true;
/*      */     } 
/*      */   }
/*      */   
/*      */   public void describe(ColibriConferenceIQ.ChannelCommon commonIq) {
/*  687 */     ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel)commonIq;
/*  697 */     iq.setRTPLevelRelayType(getRTPLevelRelayType());
/*  699 */     super.describe((ColibriConferenceIQ.ChannelCommon)iq);
/*  701 */     iq.setDirection(
/*  702 */         (this.stream.getDirection() != null) ? this.stream
/*  703 */         .getDirection().toString() : null);
/*  706 */     iq.setLastN(null);
/*  708 */     long initialLocalSSRC = getInitialLocalSSRC();
/*  710 */     if (initialLocalSSRC != -1L) {
/*  712 */       SourcePacketExtension source = new SourcePacketExtension();
/*  714 */       source.setSSRC(initialLocalSSRC);
/*  715 */       iq.addSource(source);
/*      */     } 
/*  717 */     iq.setSSRCs(getReceiveSSRCs());
/*      */   }
/*      */   
/*      */   protected void dominantSpeakerChanged() {}
/*      */   
/*      */   public RtpChannelDatagramFilter getDatagramFilter(boolean rtcp) {
/*      */     RtpChannelDatagramFilter datagramFilter;
/*  741 */     int index = rtcp ? 1 : 0;
/*  743 */     synchronized (this.datagramFilters) {
/*  745 */       datagramFilter = this.datagramFilters[index];
/*  746 */       if (datagramFilter == null)
/*  748 */         this.datagramFilters[index] = datagramFilter = new RtpChannelDatagramFilter(this, rtcp); 
/*      */     } 
/*  753 */     return datagramFilter;
/*      */   }
/*      */   
/*      */   private long getInitialLocalSSRC() {
/*  766 */     switch (getRTPLevelRelayType()) {
/*      */       case MIXER:
/*  769 */         return this.initialLocalSSRC;
/*      */       case TRANSLATOR:
/*  771 */         return getContent().getInitialLocalSSRC();
/*      */     } 
/*  773 */     return -1L;
/*      */   }
/*      */   
/*      */   private MediaService getMediaService() {
/*  784 */     return getContent().getMediaService();
/*      */   }
/*      */   
/*      */   public int[] getDefaultReceiveSSRCs() {
/*  800 */     return EMPTY_INT_ARRAY;
/*      */   }
/*      */   
/*      */   public int[] getReceiveSSRCs() {
/*  812 */     long[] receiveSSRCsField = this.receiveSSRCs;
/*  813 */     int length = receiveSSRCsField.length;
/*  815 */     if (length == 0)
/*  817 */       return ColibriConferenceIQ.NO_SSRCS; 
/*  821 */     int[] receiveSSRCs = new int[length / 2];
/*  823 */     for (int src = 0, dst = 0; src < length; src += 2, dst++)
/*  825 */       receiveSSRCs[dst] = (int)receiveSSRCsField[src]; 
/*  827 */     return receiveSSRCs;
/*      */   }
/*      */   
/*      */   public RTPLevelRelayType getRTPLevelRelayType() {
/*  848 */     if (this.rtpLevelRelayType == null)
/*  849 */       setRTPLevelRelayType(RTPLevelRelayType.TRANSLATOR);
/*  850 */     return this.rtpLevelRelayType;
/*      */   }
/*      */   
/*      */   public MediaStream getStream() {
/*  862 */     return this.stream;
/*      */   }
/*      */   
/*      */   public SessionAddress getStreamTarget() {
/*  877 */     return this.streamTarget;
/*      */   }
/*      */   
/*      */   public void initialize() throws IOException {
/*  889 */     initialize((RTPLevelRelayType)null);
/*      */   }
/*      */   
/*      */   void initialize(RTPLevelRelayType rtpLevelRelayType) throws IOException {
/*  895 */     super.initialize();
/*  897 */     MediaService mediaService = getMediaService();
/*  898 */     MediaType mediaType = getContent().getMediaType();
/*  900 */     synchronized (this.streamSyncRoot) {
/*  902 */       TransportManager transportManager = getTransportManager();
/*  904 */       TransportCCEngine transportCCEngine = transportManager.getTransportCCEngine();
/*  906 */       this
/*  907 */         .stream = mediaService.createMediaStream(null, mediaType, 
/*      */           
/*  910 */           getSrtpControl());
/*  915 */       this.stream.addPropertyChangeListener(this.streamPropertyChangeListener);
/*  916 */       this.stream.setName(getID());
/*  917 */       this.stream.setProperty(RtpChannel.class.getName(), this);
/*  919 */       if (this.stream instanceof MediaStreamImpl) {
/*  921 */         MediaStreamImpl streamImpl = (MediaStreamImpl)this.stream;
/*  923 */         DiagnosticContext diagnosticContext = streamImpl.getDiagnosticContext();
/*  925 */         getContent().getConference()
/*  926 */           .appendDiagnosticInformation(diagnosticContext);
/*      */       } 
/*  929 */       if (this.transformEngine != null)
/*  931 */         this.stream.setExternalTransformer((TransformEngine)this.transformEngine); 
/*  933 */       if (transportCCEngine != null)
/*  935 */         this.stream.setTransportCCEngine(transportCCEngine); 
/*  938 */       this.logger.info(Logger.Category.STATISTICS, "create_stream," + 
/*  939 */           getLoggingId());
/*  953 */       if (rtpLevelRelayType != null)
/*  955 */         setRTPLevelRelayType(rtpLevelRelayType); 
/*  962 */       if (transportManager.isConnected())
/*  964 */         transportConnected(); 
/*      */     } 
/*      */   }
/*      */   
/*      */   private Iterable<TransformChainManipulator> getTransformChainManipulators() {
/*      */     try {
/*  978 */       ArrayList<TransformChainManipulator> manipulators = new ArrayList<>();
/*  981 */       BundleContext bundleContext = getBundleContext();
/*  984 */       Collection<ServiceReference<TransformChainManipulator>> serviceReferences = bundleContext.getServiceReferences(TransformChainManipulator.class, null);
/*  989 */       for (ServiceReference<TransformChainManipulator> serviceReference : serviceReferences) {
/*  992 */         TransformChainManipulator transformChainManipulators = (TransformChainManipulator)bundleContext.getService(serviceReference);
/*  993 */         if (transformChainManipulators != null)
/*  995 */           manipulators.add(transformChainManipulators); 
/*      */       } 
/*  998 */       return manipulators;
/* 1001 */     } catch (InvalidSyntaxException e) {
/* 1003 */       this.logger.warn("Cannot fetch TransformChainManipulators", (Throwable)e);
/* 1004 */       return null;
/*      */     } 
/*      */   }
/*      */   
/*      */   RtpChannelTransformEngine initializeTransformerEngine() {
/* 1017 */     Iterable<TransformChainManipulator> manipulators = getTransformChainManipulators();
/* 1019 */     this.transformEngine = new RtpChannelTransformEngine(this, manipulators);
/* 1022 */     if (this.stream != null)
/* 1024 */       this.stream.setExternalTransformer((TransformEngine)this.transformEngine); 
/* 1026 */     return this.transformEngine;
/*      */   }
/*      */   
/*      */   protected void configureStream(MediaStream stream) {
/* 1037 */     RetransmissionRequester retransmissionRequester = stream.getRetransmissionRequester();
/* 1038 */     if (retransmissionRequester != null)
/* 1040 */       retransmissionRequester
/* 1041 */         .setSenderSsrc(getContent().getInitialLocalSSRC()); 
/*      */   }
/*      */   
/*      */   protected void maybeStartStream() throws IOException {
/* 1060 */     synchronized (this.streamSyncRoot) {
/* 1062 */       if (this.stream == null)
/*      */         return; 
/*      */     } 
/* 1068 */     configureStream(this.stream);
/* 1070 */     MediaStreamTarget streamTarget = createStreamTarget();
/* 1071 */     StreamConnector connector = getStreamConnector();
/* 1072 */     if (connector == null) {
/* 1074 */       this.logger.info("Not starting stream, connector is null");
/*      */       return;
/*      */     } 
/* 1078 */     if (streamTarget != null) {
/* 1080 */       InetSocketAddress dataAddr = streamTarget.getDataAddress();
/* 1081 */       if (dataAddr == null) {
/* 1083 */         this.logger.info("Not starting stream, the target's data address is null");
/*      */         return;
/*      */       } 
/* 1088 */       this.streamTarget.setDataHostAddress(dataAddr.getAddress());
/* 1089 */       this.streamTarget.setDataPort(dataAddr.getPort());
/* 1091 */       InetSocketAddress ctrlAddr = streamTarget.getControlAddress();
/* 1092 */       if (ctrlAddr != null) {
/* 1094 */         this.streamTarget.setControlHostAddress(ctrlAddr.getAddress());
/* 1095 */         this.streamTarget.setControlPort(ctrlAddr.getPort());
/*      */       } 
/* 1098 */       this.stream.setTarget(streamTarget);
/*      */     } 
/* 1100 */     this.stream.setConnector(connector);
/* 1102 */     Content content = getContent();
/* 1103 */     Conference conference = content.getConference();
/* 1105 */     if (!this.stream.isStarted()) {
/* 1114 */       if (RTPLevelRelayType.MIXER.equals(getRTPLevelRelayType()))
/* 1116 */         this.stream.setSSRCFactory((SSRCFactory)new SSRCFactoryImpl(this.initialLocalSSRC)); 
/* 1119 */       synchronized (this.streamSyncRoot) {
/* 1121 */         this.stream.start();
/*      */       } 
/* 1124 */       EventAdmin eventAdmin = conference.getEventAdmin();
/* 1125 */       if (eventAdmin != null)
/* 1127 */         eventAdmin.sendEvent(EventFactory.streamStarted(this)); 
/*      */     } 
/* 1131 */     if (this.logger.isTraceEnabled())
/* 1133 */       this.logger.debug(Logger.Category.STATISTICS, "ch_direction," + 
/* 1134 */           getLoggingId() + " direction=" + this.stream
/* 1135 */           .getDirection()); 
/*      */   }
/*      */   
/*      */   private void notifyFocus() {
/* 1146 */     Content content = getContent();
/* 1147 */     Conference conference = content.getConference();
/* 1148 */     Jid focus = conference.getFocus();
/* 1150 */     if (focus == null)
/*      */       return; 
/* 1156 */     Collection<ComponentImpl> components = conference.getVideobridge().getComponents();
/* 1158 */     if (!components.isEmpty())
/*      */       try {
/* 1162 */         ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();
/* 1164 */         conference.describeShallow(conferenceIQ);
/* 1167 */         ColibriConferenceIQ.Content contentIQ = conferenceIQ.getOrCreateContent(content.getName());
/* 1168 */         ColibriConferenceIQ.Channel channelIQ = new ColibriConferenceIQ.Channel();
/* 1171 */         describe((ColibriConferenceIQ.ChannelCommon)channelIQ);
/* 1172 */         contentIQ.addChannel(channelIQ);
/* 1174 */         conferenceIQ.setTo(focus);
/* 1175 */         conferenceIQ.setType(IQ.Type.set);
/* 1177 */         for (ComponentImpl component : components)
/* 1179 */           component.send((IQ)conferenceIQ); 
/* 1182 */       } catch (Throwable t) {
/* 1186 */         if (t instanceof InterruptedException) {
/* 1188 */           Thread.currentThread().interrupt();
/* 1190 */         } else if (t instanceof ThreadDeath) {
/* 1192 */           throw (ThreadDeath)t;
/*      */         } 
/*      */       }  
/*      */   }
/*      */   
/*      */   protected void onEndpointChanged(AbstractEndpoint oldValue, AbstractEndpoint newValue) {
/* 1205 */     super.onEndpointChanged(oldValue, newValue);
/* 1207 */     if (oldValue != null) {
/* 1209 */       oldValue.removeChannel(this);
/* 1210 */       oldValue.removePropertyChangeListener(this);
/*      */     } 
/* 1212 */     if (newValue != null) {
/* 1214 */       newValue.addChannel(this);
/* 1215 */       newValue.addPropertyChangeListener(this);
/*      */     } 
/*      */   }
/*      */   
/*      */   public void propertyChange(PropertyChangeEvent ev) {
/* 1230 */     Object source = ev.getSource();
/* 1232 */     if (this.conferenceSpeechActivity == source && this.conferenceSpeechActivity != null) {
/* 1235 */       String propertyName = ev.getPropertyName();
/* 1237 */       if (ConferenceSpeechActivity.DOMINANT_ENDPOINT_PROPERTY_NAME
/* 1238 */         .equals(propertyName))
/* 1240 */         dominantSpeakerChanged(); 
/*      */     } 
/*      */   }
/*      */   
/*      */   private boolean removeReceiveSSRC(int receiveSSRC) {
/* 1256 */     boolean removed = false;
/* 1258 */     synchronized (this.receiveSSRCsSyncRoot) {
/* 1261 */       int length = this.receiveSSRCs.length;
/* 1263 */       if (length == 2) {
/* 1265 */         if ((int)this.receiveSSRCs[0] == receiveSSRC) {
/* 1267 */           this.receiveSSRCs = NO_RECEIVE_SSRCS;
/* 1268 */           removed = true;
/*      */         } 
/*      */       } else {
/* 1273 */         for (int i = 0; i < length; i += 2) {
/* 1275 */           if ((int)this.receiveSSRCs[i] == receiveSSRC) {
/* 1277 */             long[] newReceiveSSRCs = new long[length - 2];
/* 1279 */             if (i != 0)
/* 1281 */               System.arraycopy(this.receiveSSRCs, 0, newReceiveSSRCs, 0, i); 
/* 1286 */             if (i != newReceiveSSRCs.length)
/* 1288 */               System.arraycopy(this.receiveSSRCs, i + 2, newReceiveSSRCs, i, newReceiveSSRCs.length - i); 
/* 1293 */             this.receiveSSRCs = newReceiveSSRCs;
/* 1294 */             removed = true;
/*      */             break;
/*      */           } 
/*      */         } 
/*      */       } 
/*      */     } 
/* 1302 */     return removed;
/*      */   }
/*      */   
/*      */   protected void removeStreamListeners() {
/*      */     try {
/* 1317 */       this.stream.removePropertyChangeListener(this.streamPropertyChangeListener);
/* 1320 */     } catch (Throwable t) {
/* 1322 */       if (t instanceof InterruptedException) {
/* 1324 */         Thread.currentThread().interrupt();
/* 1326 */       } else if (t instanceof ThreadDeath) {
/* 1328 */         throw (ThreadDeath)t;
/*      */       } 
/*      */     } 
/*      */   }
/*      */   
/*      */   protected void rtpLevelRelayTypeChanged(RTPLevelRelayType oldValue, RTPLevelRelayType newValue) {}
/*      */   
/*      */   boolean rtpTranslatorWillWrite(boolean data, RawPacket pkt, RtpChannel source) {
/* 1366 */     return true;
/*      */   }
/*      */   
/*      */   void endpointMessageTransportConnected() {}
/*      */   
/*      */   public void setDirection(MediaDirection direction) {
/* 1393 */     if (this.streamTarget.getDataAddress() != null)
/* 1395 */       this.stream.setDirection(direction); 
/* 1398 */     touch();
/*      */   }
/*      */   
/*      */   public void setLastN(int lastN) {}
/*      */   
/*      */   public void setPayloadTypes(List<PayloadTypePacketExtension> payloadTypes) {
/* 1429 */     if (payloadTypes != null && !payloadTypes.isEmpty()) {
/* 1431 */       MediaService mediaService = getMediaService();
/* 1433 */       if (mediaService != null) {
/* 1435 */         int payloadTypeCount = payloadTypes.size();
/* 1437 */         this.receivePTs = new int[payloadTypeCount];
/* 1438 */         this.stream.clearDynamicRTPPayloadTypes();
/* 1439 */         for (int i = 0; i < payloadTypeCount; i++) {
/* 1442 */           PayloadTypePacketExtension payloadType = payloadTypes.get(i);
/* 1444 */           this.receivePTs[i] = payloadType.getID();
/* 1447 */           MediaFormat mediaFormat = JingleUtils.payloadTypeToMediaFormat(payloadType, mediaService, null);
/* 1452 */           if (mediaFormat != null)
/* 1454 */             this.stream.addDynamicRTPPayloadType(
/* 1455 */                 (byte)payloadType.getID(), mediaFormat); 
/*      */         } 
/* 1460 */         TransportManager transportManager = getTransportManager();
/* 1461 */         if (transportManager != null)
/* 1463 */           transportManager.payloadTypesChanged(this); 
/*      */       } 
/*      */     } 
/* 1469 */     touch();
/*      */   }
/*      */   
/*      */   public void setRtpHeaderExtensions(Collection<RTPHdrExtPacketExtension> rtpHeaderExtensions) {
/* 1482 */     if (rtpHeaderExtensions != null && rtpHeaderExtensions.size() > 0) {
/* 1484 */       this.stream.clearRTPExtensions();
/* 1487 */       for (RTPHdrExtPacketExtension rtpHdrExtPacketExtension : rtpHeaderExtensions)
/* 1489 */         addRtpHeaderExtension(rtpHdrExtPacketExtension); 
/*      */     } 
/*      */   }
/*      */   
/*      */   protected void addRtpHeaderExtension(RTPHdrExtPacketExtension rtpHdrExtPacketExtension) {
/* 1505 */     URI uri = rtpHdrExtPacketExtension.getURI();
/* 1506 */     if (uri == null) {
/* 1508 */       this.logger.warn("Failed to add an RTP header extension with an invalid URI: " + rtpHdrExtPacketExtension
/*      */           
/* 1511 */           .getAttribute("uri"));
/*      */       return;
/*      */     } 
/* 1516 */     Byte id = Byte.valueOf(rtpHdrExtPacketExtension.getID());
/* 1517 */     if (id == null) {
/* 1519 */       this.logger.warn("Failed to add an RTP header extension with an invalid ID: " + rtpHdrExtPacketExtension
/*      */           
/* 1521 */           .getID());
/*      */       return;
/*      */     } 
/* 1525 */     if ("http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time".equals(uri.toString())) {
/* 1528 */       ConfigurationService cfg = (ConfigurationService)ServiceUtils.getService(
/* 1529 */           getBundleContext(), ConfigurationService.class);
/* 1532 */       if (cfg != null && cfg
/* 1533 */         .getBoolean("org.jitsi.videobridge.DISABLE_ABS_SEND_TIME", false))
/*      */         return; 
/*      */     } 
/* 1540 */     MediaStream stream = getStream();
/* 1541 */     if (stream != null)
/* 1543 */       stream.addRTPExtension(id.byteValue(), new RTPExtension(uri)); 
/*      */   }
/*      */   
/*      */   public void setRTPLevelRelayType(RTPLevelRelayType rtpLevelRelayType) {
/* 1559 */     if (rtpLevelRelayType == null)
/* 1561 */       throw new NullPointerException("rtpLevelRelayType"); 
/* 1564 */     if (this.rtpLevelRelayType == null) {
/*      */       MediaDevice device;
/* 1566 */       RTPLevelRelayType oldValue = null;
/* 1568 */       this.rtpLevelRelayType = rtpLevelRelayType;
/* 1570 */       RTPLevelRelayType newValue = getRTPLevelRelayType();
/* 1579 */       switch (newValue) {
/*      */         case MIXER:
/* 1582 */           device = getContent().getMixer();
/* 1584 */           this.stream.setDevice(device);
/* 1591 */           if (this.stream.getFormat() == null)
/* 1593 */             this.stream.setDirection(MediaDirection.RECVONLY); 
/*      */           break;
/*      */         case TRANSLATOR:
/* 1598 */           this.stream.setRTPTranslator(getContent().getRTPTranslator());
/*      */           break;
/*      */         default:
/* 1602 */           throw new IllegalStateException("rtpLevelRelayType");
/*      */       } 
/* 1605 */       rtpLevelRelayTypeChanged(oldValue, newValue);
/* 1607 */     } else if (!this.rtpLevelRelayType.equals(rtpLevelRelayType)) {
/*      */     
/*      */     } 
/* 1616 */     touch();
/*      */   }
/*      */   
/*      */   void speechActivityEndpointsChanged(List<AbstractEndpoint> endpoints) {}
/*      */   
/*      */   private void streamPropertyChange(PropertyChangeEvent ev) {
/* 1651 */     String propertyName = ev.getPropertyName();
/* 1652 */     String prefix = MediaStreamImpl.class.getName() + ".rtpConnector";
/* 1654 */     if (propertyName.startsWith(prefix)) {
/* 1657 */       String rtpConnectorPropertyName = propertyName.substring(prefix.length());
/* 1658 */       Object newValue = ev.getNewValue();
/* 1660 */       if (rtpConnectorPropertyName.equals("")) {
/* 1662 */         if (newValue instanceof RTPConnector)
/* 1664 */           streamRTPConnectorChanged((RTPConnector)ev
/* 1665 */               .getOldValue(), (RTPConnector)newValue); 
/* 1669 */       } else if (newValue instanceof RTPConnectorInputStream) {
/*      */         DatagramPacketFilter datagramPacketFilter;
/* 1673 */         if (rtpConnectorPropertyName.equals(".controlInputStream")) {
/* 1675 */           datagramPacketFilter = new DatagramPacketFilter() {
/*      */               public boolean accept(DatagramPacket p) {
/* 1681 */                 return RtpChannel.this
/* 1682 */                   .acceptControlInputStreamDatagramPacket(p);
/*      */               }
/*      */             };
/* 1686 */         } else if (rtpConnectorPropertyName.equals(".dataInputStream")) {
/* 1688 */           datagramPacketFilter = new DatagramPacketFilter() {
/*      */               public boolean accept(DatagramPacket p) {
/* 1694 */                 return RtpChannel.this.acceptDataInputStreamDatagramPacket(p);
/*      */               }
/*      */             };
/*      */         } else {
/* 1700 */           datagramPacketFilter = null;
/*      */         } 
/* 1702 */         if (datagramPacketFilter != null)
/* 1704 */           ((RTPConnectorInputStream)newValue)
/* 1705 */             .addDatagramPacketFilter(datagramPacketFilter); 
/*      */       } 
/*      */     } 
/*      */   }
/*      */   
/*      */   protected void streamRTPConnectorChanged(RTPConnector oldValue, RTPConnector newValue) {}
/*      */   
/*      */   public void setSources(List<SourcePacketExtension> sources) {
/* 1740 */     if (sources == null || sources.isEmpty())
/*      */       return; 
/* 1745 */     synchronized (this.receiveSSRCsSyncRoot) {
/* 1748 */       Set<Integer> oldSignaledSSRCs = new HashSet<>(this.signaledSSRCs);
/* 1751 */       Set<Integer> newSignaledSSRCs = new HashSet<>();
/* 1752 */       for (SourcePacketExtension source : sources) {
/* 1754 */         long ssrc = source.getSSRC();
/* 1755 */         if (ssrc != -1L)
/* 1757 */           newSignaledSSRCs.add(Integer.valueOf((int)ssrc)); 
/*      */       } 
/* 1762 */       Set<Integer> addedSSRCs = new HashSet<>(newSignaledSSRCs);
/* 1763 */       addedSSRCs.removeAll(oldSignaledSSRCs);
/* 1764 */       if (!addedSSRCs.isEmpty()) {
/* 1767 */         Recorder recorder = null;
/* 1768 */         Synchronizer synchronizer = null;
/* 1769 */         AbstractEndpoint endpoint = null;
/* 1770 */         if (getContent().isRecording()) {
/* 1772 */           recorder = getContent().getRecorder();
/* 1773 */           synchronizer = recorder.getSynchronizer();
/* 1774 */           endpoint = getEndpoint();
/*      */         } 
/* 1777 */         for (Integer addedSSRC : addedSSRCs) {
/*      */           try {
/* 1783 */             addReceiveSSRC(addedSSRC.intValue(), false);
/* 1788 */             if (recorder != null && endpoint != null && synchronizer != null)
/* 1790 */               synchronizer.setEndpoint(addedSSRC
/* 1791 */                   .intValue() & 0xFFFFFFFFL, endpoint
/* 1792 */                   .getID()); 
/* 1795 */           } catch (SizeExceededException see) {
/* 1798 */             this.logger.error("An unexpected exception occurred.", see);
/*      */           } 
/*      */         } 
/*      */       } 
/* 1804 */       oldSignaledSSRCs.removeAll(newSignaledSSRCs);
/* 1805 */       if (!oldSignaledSSRCs.isEmpty())
/* 1807 */         for (Integer removedSSRC : oldSignaledSSRCs)
/* 1809 */           removeReceiveSSRC(removedSSRC.intValue());  
/* 1814 */       this.signaledSSRCs = newSignaledSSRCs;
/*      */     } 
/* 1818 */     touch();
/*      */   }
/*      */   
/*      */   public void setSourceGroups(List<SourceGroupPacketExtension> sourceGroups) {}
/*      */   
/*      */   public int[] getReceivePTs() {
/* 1838 */     return this.receivePTs;
/*      */   }
/*      */   
/*      */   public boolean expire() {
/* 1853 */     if (!super.expire())
/* 1856 */       return false; 
/* 1859 */     updateStatisticsOnExpire();
/* 1861 */     RtpChannelTransformEngine rtpChannelTransformEngine = this.transformEngine;
/* 1862 */     if (rtpChannelTransformEngine != null) {
/* 1864 */       PacketTransformer t = rtpChannelTransformEngine.getRTPTransformer();
/* 1865 */       if (t != null)
/* 1867 */         t.close(); 
/* 1870 */       t = rtpChannelTransformEngine.getRTCPTransformer();
/* 1871 */       if (t != null)
/* 1873 */         t.close(); 
/*      */     } 
/* 1877 */     return true;
/*      */   }
/*      */   
/*      */   private void updateStatisticsOnExpire() {
/* 1886 */     Conference conference = getContent().getConference();
/* 1887 */     if (conference != null && conference.includeInStatistics()) {
/* 1890 */       Conference.Statistics conferenceStatistics = conference.getStatistics();
/* 1891 */       conferenceStatistics.totalChannels.incrementAndGet();
/* 1893 */       long lastPayloadActivityTime = getLastPayloadActivityTime();
/* 1894 */       long lastTransportActivityTime = getLastTransportActivityTime();
/* 1896 */       if (lastTransportActivityTime == 0L)
/* 1899 */         conferenceStatistics.totalNoTransportChannels.incrementAndGet(); 
/* 1902 */       if (lastPayloadActivityTime == 0L)
/* 1905 */         conferenceStatistics.totalNoPayloadChannels.incrementAndGet(); 
/* 1908 */       updatePacketsAndBytes(conferenceStatistics);
/* 1910 */       MediaStream stream = this.stream;
/*      */       SendTrackStats sendTrackStats;
/* 1912 */       if (stream != null && (
/* 1913 */         sendTrackStats = stream.getMediaStreamStats().getSendStats()) != null)
/* 1916 */         this.logger.info(Logger.Category.STATISTICS, "expire_ch_stats," + 
/* 1917 */             getLoggingId() + " bRecv=" + this.statistics.bytesReceived + ",bSent=" + this.statistics.bytesSent + ",pRecv=" + this.statistics.packetsReceived + ",pSent=" + this.statistics.packetsSent + ",bRetr=" + sendTrackStats
/*      */             
/* 1923 */             .getBytesRetransmitted() + ",bNotRetr=" + sendTrackStats
/*      */             
/* 1925 */             .getBytesNotRetransmitted() + ",pRetr=" + sendTrackStats
/*      */             
/* 1927 */             .getPacketsRetransmitted() + ",pNotRetr=" + sendTrackStats
/*      */             
/* 1929 */             .getPacketsNotRetransmitted() + ",pMiss=" + sendTrackStats
/*      */             
/* 1931 */             .getPacketsMissingFromCache()); 
/*      */     } 
/*      */   }
/*      */   
/*      */   protected void updatePacketsAndBytes(Conference.Statistics conferenceStatistics) {
/* 1945 */     if (conferenceStatistics != null) {
/* 1947 */       conferenceStatistics.totalBytesReceived
/* 1948 */         .addAndGet(this.statistics.bytesReceived);
/* 1949 */       conferenceStatistics.totalBytesSent
/* 1950 */         .addAndGet(this.statistics.bytesSent);
/* 1951 */       conferenceStatistics.totalPacketsReceived
/* 1952 */         .addAndGet(this.statistics.packetsReceived);
/* 1953 */       conferenceStatistics.totalPacketsSent
/* 1954 */         .addAndGet(this.statistics.packetsSent);
/*      */     } 
/*      */   }
/*      */   
/*      */   public ConferenceSpeechActivity getConferenceSpeechActivity() {
/* 1963 */     return this.conferenceSpeechActivity;
/*      */   }
/*      */   
/*      */   public boolean setPacketDelay(int packetDelay) {
/* 1982 */     RtpChannelTransformEngine engine = this.transformEngine;
/* 1983 */     if (engine == null)
/* 1985 */       engine = initializeTransformerEngine(); 
/* 1987 */     return engine.setPacketDelay(packetDelay);
/*      */   }
/*      */   
/*      */   public RtpChannelTransformEngine getTransformEngine() {
/* 1997 */     return this.transformEngine;
/*      */   }
/*      */   
/*      */   public boolean setRtpEncodingParameters(List<SourcePacketExtension> sources, List<SourceGroupPacketExtension> sourceGroups) {
/* 2017 */     boolean changed = false;
/* 2021 */     if (sources == null)
/* 2023 */       sources = Collections.emptyList(); 
/* 2026 */     if (sourceGroups == null)
/* 2028 */       sourceGroups = Collections.emptyList(); 
/* 2031 */     if (sources.isEmpty() && sourceGroups.isEmpty())
/* 2033 */       return false; 
/* 2036 */     setSources(sources);
/* 2037 */     setSourceGroups(sourceGroups);
/* 2040 */     MediaStreamTrackReceiver mediaStreamTrackReceiver = this.stream.getMediaStreamTrackReceiver();
/* 2042 */     if (mediaStreamTrackReceiver != null) {
/* 2045 */       MediaStreamTrackDesc[] newTracks = MediaStreamTrackFactory.createMediaStreamTracks(mediaStreamTrackReceiver, sources, sourceGroups);
/* 2049 */       changed = mediaStreamTrackReceiver.setMediaStreamTracks(newTracks);
/*      */     } 
/* 2052 */     if (changed)
/* 2054 */       getContent().getChannels().stream()
/* 2055 */         .filter(c -> (c != this && c instanceof RtpChannel))
/* 2056 */         .forEach(c -> ((RtpChannel)c).updateBitrateController()); 
/* 2060 */     return changed;
/*      */   }
/*      */   
/*      */   protected void updateBitrateController() {}
/*      */   
/*      */   public String getLoggingId() {
/* 2081 */     return getLoggingId(this);
/*      */   }
/*      */   
/*      */   public static String getLoggingId(RtpChannel rtpChannel) {
/* 2093 */     String channelId = Channel.getLoggingId(rtpChannel);
/* 2094 */     MediaStream stream = (rtpChannel == null) ? null : rtpChannel.getStream();
/* 2095 */     return channelId + ",stream=" + ((stream == null) ? "null" : 
/* 2096 */       ""+Integer.valueOf(stream.hashCode()));
/*      */   }
/*      */   
/*      */   private static class SizeExceededException extends Exception {
/*      */     private SizeExceededException() {}
/*      */   }
/*      */   
/*      */   protected class Statistics {
/* 2114 */     public long bytesSent = -1L;
/*      */     
/* 2120 */     public long bytesReceived = -1L;
/*      */     
/* 2126 */     public long packetsSent = -1L;
/*      */     
/* 2132 */     public long packetsReceived = -1L;
/*      */   }
/*      */ }


/* Location:              /Users/liqian/Downloads/jitsi-videobridge-linux-x64-1130.zip!/jitsi-videobridge-linux-x64-1130/jitsi-videobridge.jar!/org/jitsi/videobridge/RtpChannel.class
 * Java compiler version: 8 (52.0)
 * JD-Core Version:       1.1.3
 */