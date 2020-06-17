package org.jitsi.videobridge.stats;

import java.util.Collection;
import java.util.Iterator;
import net.java.sip.communicator.util.Logger;
import org.jitsi.videobridge.pubsub.PubSubPublisher;
import org.jitsi.videobridge.pubsub.PubSubResponseListener;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.XMPPError;
import org.jxmpp.jid.Jid;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;

public class PubSubStatsTransport extends StatsTransport implements PubSubResponseListener {
    private static final Logger logger = Logger.getLogger(PubSubStatsTransport.class);

    private String itemId;

    private final String nodeName;

    private PubSubPublisher publisher;

    private final ServiceListener serviceListener = this::serviceChanged;

    private final Jid serviceName;

    public PubSubStatsTransport(Jid serviceName, String nodeName) {
        this.serviceName = serviceName;
        this.nodeName = nodeName;
    }

    protected void bundleContextChanged(BundleContext oldValue, BundleContext newValue) {
        super.bundleContextChanged(oldValue, newValue);
        if (oldValue != null)
            oldValue.removeServiceListener(this.serviceListener);
        if (newValue != null)
            newValue.addServiceListener(this.serviceListener);
        initOrDispose(null);
    }

    private void dispose() {
        if (this.publisher != null) {
            this.publisher.removeResponseListener(this);
            PubSubPublisher.releasePubsubManager(this.publisher);
            this.publisher = null;
        }
    }

    private void init() {
        if (this.publisher == null) {
            Iterator<ComponentImpl> components = ComponentImpl.getComponents(getBundleContext()).iterator();
            if (components.hasNext())
                this.itemId = ((ComponentImpl)components.next()).getJID().toString();
            this.publisher = PubSubPublisher.getPubsubManager(this.serviceName);
            this.publisher.addResponseListener(this);
            try {
                this.publisher.createNode(this.nodeName);
            } catch (Exception ex) {
                logger.error("Failed to create PubSub node: " + this.nodeName);
                dispose();
            }
        }
    }

    private void initOrDispose(ComponentImpl unregistering) {
        boolean init, dispose;
        BundleContext bundleContext = getBundleContext();
        if (bundleContext == null) {
            init = false;
            dispose = true;
        } else {
            Collection<ComponentImpl> components = ComponentImpl.getComponents(bundleContext);
            int componentCount = components.size();
            if (unregistering == null) {
                init = (componentCount > 0);
                dispose = !init;
            } else {
                init = false;
                if (components.contains(unregistering))
                    componentCount--;
                dispose = (componentCount < 1);
            }
        }
        if (init) {
            init();
        } else if (dispose) {
            dispose();
        }
    }

    public void onCreateNodeResponse(PubSubResponseListener.Response response) {
        if (PubSubResponseListener.Response.FAIL.equals(response))
            dispose();
    }

    public void onPublishResponse(PubSubResponseListener.Response type, IQ iq) {
        if (PubSubResponseListener.Response.FAIL.equals(type)) {
            XMPPError err = iq.getError();
            if (err != null && XMPPError.Type.CANCEL
                    .equals(err.getType()) && XMPPError.Condition.item_not_found
                    .equals(err
                            .getCondition())) {
                PubSubPublisher publisher = this.publisher;
                if (publisher != null) {
                    String nodeName = this.nodeName;
                    try {
                        publisher.createNode(nodeName);
                        return;
                    } catch (Exception ex) {
                        logger.error("Failed to re-create PubSub node: " + nodeName);
                    }
                }
            }
            dispose();
        }
    }

    public void publishStatistics(Statistics stats) {
        PubSubPublisher publisher = this.publisher;
        if (publisher != null)
            try {
                publisher.publish(this.nodeName, this.itemId,
                        (ExtensionElement)Statistics.toXmppExtensionElement(stats));
            } catch (IllegalArgumentException e) {
                logger.error("Failed to publish to PubSub node: " + this.nodeName + " - it does not exist yet");
            } catch (Exception e) {
                logger.error("Failed to publish to PubSub node: " + this.nodeName, e);
                dispose();
            }
    }

    private void serviceChanged(ServiceEvent ev) {
        int type = ev.getType();
        if (type == 1 || type == 4) {
            BundleContext bundleContext = getBundleContext();
            if (bundleContext != null) {
                Object service = null;
                try {
                    service = bundleContext.getService(ev.getServiceReference());
                } catch (IllegalArgumentException|IllegalStateException|SecurityException ex) {
                    logger.debug("An unexpected exception occurred.", ex);
                }
                if (service instanceof ComponentImpl)
                    initOrDispose((type == 4) ? (ComponentImpl)service : null);
            }
        }
    }
}
