package org.jitsi.videobridge.stats;

import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.videobridge.xmpp.ClientConnectionImpl;
import org.jivesoftware.smack.packet.ExtensionElement;

public class MucStatsTransport extends StatsTransport {
    private static final Logger logger = Logger.getLogger(MucStatsTransport.class);

    private ClientConnectionImpl getUserConnectionBundleActivator() {
        return (ClientConnectionImpl)ServiceUtils.getService(
                getBundleContext(), ClientConnectionImpl.class);
    }

    public void publishStatistics(Statistics stats) {
        ClientConnectionImpl clientConnectionImpl = getUserConnectionBundleActivator();
        if (clientConnectionImpl != null) {
            if (logger.isDebugEnabled())
                logger.debug("Publishing statistics through MUC: " + stats);
            clientConnectionImpl
                    .setPresenceExtension((ExtensionElement)Statistics.toXmppExtensionElement(stats));
        } else {
            logger.warn("Can not publish via presence, no ClientConnectionImpl.");
        }
    }
}
