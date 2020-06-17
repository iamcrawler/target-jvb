package org.jitsi.videobridge.stats;

import java.util.Collection;
import net.java.sip.communicator.util.Logger;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jitsi.xmpp.extensions.colibri.ColibriStatsIQ;
import org.jivesoftware.smack.packet.IQ;
import org.jxmpp.jid.Jid;
import org.osgi.framework.BundleContext;

public class ColibriStatsTransport extends StatsTransport {
    private static final Logger logger = Logger.getLogger(ColibriStatsTransport.class);

    private static IQ buildStatsIQ(Statistics statistics) {
        ColibriStatsIQ iq = Statistics.toXmppIq(statistics);
        iq.setType(IQ.Type.result);
        return (IQ)iq;
    }

    public void publishStatistics(Statistics stats) {
        BundleContext bundleContext = getBundleContext();
        if (bundleContext != null) {
            Collection<Videobridge> videobridges = Videobridge.getVideobridges(bundleContext);
            IQ statsIQ = null;
            for (Videobridge videobridge : videobridges) {
                Collection<ComponentImpl> components = videobridge.getComponents();
                if (!components.isEmpty()) {
                    Conference[] conferences = videobridge.getConferences();
                    if (conferences.length != 0) {
                        if (statsIQ == null)
                            statsIQ = buildStatsIQ(stats);
                        for (Conference conference : conferences) {
                            Jid focus = conference.getLastKnowFocus();
                            if (focus != null) {
                                statsIQ.setTo(focus);
                                for (ComponentImpl component : components) {
                                    try {
                                        component.send(statsIQ);
                                    } catch (Exception ex) {
                                        logger.error("Failed to publish statistics.");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
