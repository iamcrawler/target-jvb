package org.jitsi.videobridge;


import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import net.java.sip.communicator.util.PortTracker;
import org.jitsi.impl.neomedia.rtp.TransportCCEngine;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.SrtpControl;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.utils.logging.Logger;
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.RawUdpTransportPacketExtension;

public abstract class TransportManager {
    private static long nextCandidateID = 1L;

    private static final Logger logger = Logger.getLogger(TransportManager.class);

    public static final int DEFAULT_MIN_PORT = 10001;

    public static final int DEFAULT_MAX_PORT = 20000;

    public static final PortTracker portTracker = new PortTracker(10001, 20000);

    private final PropertyChangeListener channelPropertyChangeListener = new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent ev) {
            TransportManager.this.channelPropertyChange(ev);
        }
    };

    private List<Channel> _channels = Collections.emptyList();

    private final Object _channelsSyncRoot = new Object();

    public boolean addChannel(Channel channel) {
        synchronized (this._channelsSyncRoot) {
            if (!this._channels.contains(channel)) {
                List<Channel> newChannels = new LinkedList<>(this._channels);
                newChannels.add(channel);
                this._channels = newChannels;
                channel.addPropertyChangeListener(this.channelPropertyChangeListener);
                return true;
            }
        }
        return false;
    }

    protected void channelPropertyChange(PropertyChangeEvent ev) {}

    public void close() {
        for (Channel channel : getChannels())
            close(channel);
    }

    public boolean close(Channel channel) {
        if (channel == null)
            return false;
        channel.removePropertyChangeListener(this.channelPropertyChangeListener);
        synchronized (this._channelsSyncRoot) {
            List<Channel> newChannels = new LinkedList<>(this._channels);
            boolean removed = newChannels.remove(channel);
            if (removed) {
                this._channels = newChannels;
                if (getChannels().isEmpty())
                    channel.getContent().getConference().closeTransportManager(this);
            }
            return removed;
        }
    }

    public void describe(ColibriConferenceIQ.ChannelBundle iq) {
        IceUdpTransportPacketExtension pe = iq.getTransport();
        String namespace = getXmlNamespace();
        if (pe == null || !namespace.equals(pe.getNamespace())) {
            if ("urn:xmpp:jingle:transports:ice-udp:1".equals(namespace)) {
                pe = new IceUdpTransportPacketExtension();
            } else if ("urn:xmpp:jingle:transports:raw-udp:1".equals(namespace)) {
                RawUdpTransportPacketExtension rawUdpTransportPacketExtension = new RawUdpTransportPacketExtension();
            } else {
                pe = null;
            }
            iq.setTransport(pe);
        }
        if (pe != null)
            describe(pe);
    }

    public void describe(ColibriConferenceIQ.ChannelCommon iq) {
        IceUdpTransportPacketExtension pe = iq.getTransport();
        String namespace = getXmlNamespace();
        if (pe == null || !namespace.equals(pe.getNamespace())) {
            if ("urn:xmpp:jingle:transports:ice-udp:1".equals(namespace)) {
                pe = new IceUdpTransportPacketExtension();
            } else if ("urn:xmpp:jingle:transports:raw-udp:1".equals(namespace)) {
                RawUdpTransportPacketExtension rawUdpTransportPacketExtension = new RawUdpTransportPacketExtension();
            } else {
                pe = null;
            }
            iq.setTransport(pe);
        }
        if (pe != null)
            describe(pe);
    }

    protected abstract void describe(IceUdpTransportPacketExtension paramIceUdpTransportPacketExtension);

    protected String generateCandidateID() {
        long candidateID;
        synchronized (TransportManager.class) {
            candidateID = nextCandidateID++;
        }
        return Long.toHexString(candidateID);
    }

    protected List<Channel> getChannels() {
        return this._channels;
    }

    public abstract SrtpControl getSrtpControl(Channel paramChannel);

    public abstract StreamConnector getStreamConnector(Channel paramChannel);

    public abstract MediaStreamTarget getStreamTarget(Channel paramChannel);

    public abstract String getXmlNamespace();

    public abstract void startConnectivityEstablishment(IceUdpTransportPacketExtension paramIceUdpTransportPacketExtension);

    public void payloadTypesChanged(RtpChannel channel) {
        checkPayloadTypes(channel);
    }

    private void checkPayloadTypes(RtpChannel channel) {
        for (Channel c : getChannels()) {
            if (!(c instanceof RtpChannel) || c == channel)
                continue;
            for (int pt1 : ((RtpChannel)c).getReceivePTs()) {
                for (int pt2 : channel.getReceivePTs()) {
                    if (pt1 == pt2)
                        logger.warn("The same PT (" + pt1 + ") used by two channels in the same bundle.");
                }
            }
        }
    }

    public abstract boolean isConnected();

    public TransportCCEngine getTransportCCEngine() {
        return null;
    }
}
