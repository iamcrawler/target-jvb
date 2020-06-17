package org.jitsi.videobridge.pubsub;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import net.java.sip.communicator.util.Logger;
import org.jitsi.videobridge.stats.StatsManagerBundleActivator;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smack.packet.id.StanzaIdUtil;
import org.jivesoftware.smackx.pubsub.AccessModel;
import org.jivesoftware.smackx.pubsub.ConfigureForm;
import org.jivesoftware.smackx.pubsub.FormNode;
import org.jivesoftware.smackx.pubsub.FormNodeType;
import org.jivesoftware.smackx.pubsub.Item;
import org.jivesoftware.smackx.pubsub.NodeExtension;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubElementType;
import org.jivesoftware.smackx.pubsub.PublishItem;
import org.jivesoftware.smackx.pubsub.PublishModel;
import org.jivesoftware.smackx.pubsub.packet.PubSub;
import org.jivesoftware.smackx.xdata.Form;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.Jid;
import org.osgi.framework.BundleContext;

public class PubSubPublisher {
    private static final Map<Jid, PubSubPublisher> instances = new ConcurrentHashMap<>();

    private static final Logger logger = Logger.getLogger(PubSubPublisher.class);

    private static final int PACKET_TIMEOUT = 5000;

    public static PubSubPublisher getPubsubManager(Jid serviceName) {
        PubSubPublisher publisher = instances.get(serviceName);
        if (publisher == null) {
            publisher = new PubSubPublisher(serviceName);
            instances.put(serviceName, publisher);
        }
        return publisher;
    }

    public static void handleIQResponse(IQ response) {
        IQ.Type type = response.getType();
        if (IQ.Type.error.equals(type)) {
            PubSubPublisher publisher = instances.get(response.getFrom());
            if (publisher != null)
                publisher.handleErrorResponse(response);
        } else if (IQ.Type.result.equals(type)) {
            PubSubPublisher publisher = instances.get(response.getFrom());
            if (publisher != null) {
                publisher.handleCreateNodeResponse(response);
                publisher.handleConfigureResponse(response);
                publisher.handlePublishResponse(response);
            }
        }
    }

    public static void releasePubsubManager(PubSubPublisher publisher) {
        instances.values().remove(publisher);
        publisher.dispose();
    }

    private List<PubSubResponseListener> listeners = new LinkedList<>();

    private List<String> nodes = new LinkedList<>();

    private Map<String, String> pendingConfigureRequests = new ConcurrentHashMap<>();

    private Map<String, String> pendingCreateRequests = new ConcurrentHashMap<>();

    private Map<String, String> pendingPublishRequests = new ConcurrentHashMap<>();

    private Jid serviceName;

    private Timer timeoutTimer = new Timer();

    private PubSubPublisher(Jid serviceName) {
        this.serviceName = serviceName;
    }

    public void addResponseListener(PubSubResponseListener l) {
        if (l == null)
            throw new NullPointerException("l");
        if (!this.listeners.contains(l))
            this.listeners.add(l);
    }

    private void configureNode(String nodeName) {
        ConfigureForm cfg = new ConfigureForm(DataForm.Type.submit);
        PubSub pubsub = new PubSub();
        cfg.setAccessModel(AccessModel.open);
        cfg.setPersistentItems(false);
        cfg.setPublishModel(PublishModel.open);
        pubsub.setTo(this.serviceName);
        pubsub.setType(IQ.Type.set);
        final String packetID = StanzaIdUtil.newStanzaId();
        pubsub.setStanzaId(packetID);
        pubsub.addExtension((ExtensionElement)new FormNode(FormNodeType.CONFIGURE_OWNER, nodeName, (Form)cfg));
        try {
            send((IQ)pubsub);
        } catch (Exception e) {
            logger.error("Error sending configuration form.");
            fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
            return;
        }
        this.pendingConfigureRequests.put(packetID, nodeName);
        this.timeoutTimer.schedule(new TimerTask() {
            public void run() {
                String nodeName = (String)PubSubPublisher.this.pendingConfigureRequests.remove(packetID);
                if (nodeName != null) {
                    PubSubPublisher.logger.error("Timed out a configuration request (packetID=: " + packetID + " nodeName=" + nodeName + ")");
                    PubSubPublisher.this.fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
                }
            }
        },  5000L);
    }

    public void createNode(String nodeName) throws Exception {
        PubSub request = new PubSub();
        request.setTo(this.serviceName);
        request.setType(IQ.Type.set);
        final String packetID = StanzaIdUtil.newStanzaId();
        request.setStanzaId(packetID);
        request.addExtension((ExtensionElement)new NodeExtension(PubSubElementType.CREATE, nodeName));
        this.pendingCreateRequests.put(packetID, nodeName);
        send((IQ)request);
        this.timeoutTimer.schedule(new TimerTask() {
            public void run() {
                String nodeName = (String)PubSubPublisher.this.pendingCreateRequests.remove(packetID);
                if (nodeName != null)
                    PubSubPublisher.logger.warn("Timed out a create request with ID " + packetID);
            }
        },  5000L);
    }

    private void dispose() {
        this.timeoutTimer.cancel();
        this.timeoutTimer = null;
        this.listeners = null;
        this.nodes = null;
        this.pendingConfigureRequests = null;
        this.pendingCreateRequests = null;
        this.pendingPublishRequests = null;
        this.serviceName = null;
    }

    private void fireResponseCreateEvent(PubSubResponseListener.Response type) {
        for (PubSubResponseListener l : this.listeners)
            l.onCreateNodeResponse(type);
    }

    private void fireResponsePublishEvent(PubSubResponseListener.Response type, IQ iq) {
        for (PubSubResponseListener l : this.listeners)
            l.onPublishResponse(type, iq);
    }

    private void handleConfigureResponse(IQ response) {
        if (this.pendingConfigureRequests.remove(response.getStanzaId()) != null)
            fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
    }

    private void handleCreateNodeResponse(IQ response) {
        String packetID = response.getStanzaId();
        String nodeName = this.pendingCreateRequests.remove(packetID);
        if (nodeName != null) {
            this.nodes.add(nodeName);
            configureNode(nodeName);
        }
    }

    private void handleErrorResponse(IQ response) {
        XMPPError err = response.getError();
        String packetID = response.getStanzaId();
        if (err != null) {
            XMPPError.Type errType = err.getType();
            XMPPError.Condition errCondition = err.getCondition();
            if ((XMPPError.Type.CANCEL.equals(errType) && (XMPPError.Condition.conflict
                    .equals(errCondition) || XMPPError.Condition.forbidden
                    .equals(errCondition))) || (XMPPError.Type.AUTH

                    .equals(errType) && XMPPError.Condition.forbidden
                    .equals(errCondition))) {
                if (XMPPError.Condition.forbidden.equals(errCondition))
                    logger.warn("Creating node failed with <forbidden/> error. Continuing anyway.");
                String str = this.pendingCreateRequests.remove(packetID);
                logger.info("PubSub node already exists (packetID=" + packetID + " nodeName=" + str + ")");
                if (str != null) {
                    this.nodes.add(str);
                    fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
                    return;
                }
            }
        }
        StringBuilder errMsg = new StringBuilder("Error received");
        String nodeName;
        if ((nodeName = this.pendingCreateRequests.remove(packetID)) != null) {
            fireResponseCreateEvent(PubSubResponseListener.Response.FAIL);
            errMsg.append(" when creating the node: ");
        } else if ((nodeName = this.pendingConfigureRequests.remove(packetID)) != null) {
            fireResponseCreateEvent(PubSubResponseListener.Response.SUCCESS);
            errMsg.append(" when configuring the node: ");
        } else if ((nodeName = this.pendingPublishRequests.remove(packetID)) != null) {
            fireResponsePublishEvent(PubSubResponseListener.Response.FAIL, response);
            errMsg.append(" when publishing to the node: ");
        } else {
            nodeName = null;
        }
        if (nodeName != null)
            errMsg.append(nodeName);
        errMsg.append(".");
        if (err != null)
            errMsg.append(" Message: ").append(err.getDescriptiveText())
                    .append(". Condition: ").append(err.getCondition())
                    .append(". For packet with id: ").append(packetID)
                    .append(".");
        logger.error(errMsg);
    }

    private void handlePublishResponse(IQ response) {
        if (this.pendingPublishRequests.remove(response.getStanzaId()) != null)
            fireResponsePublishEvent(PubSubResponseListener.Response.SUCCESS, response);
    }

    public void publish(String nodeName, String itemId, ExtensionElement ext) throws Exception {
        if (!this.nodes.contains(nodeName))
            throw new IllegalArgumentException("The node doesn't exists");
        PubSub packet = new PubSub();
        packet.setTo(this.serviceName);
        packet.setType(IQ.Type.set);
        final String packetID = StanzaIdUtil.newStanzaId();
        packet.setStanzaId(packetID);
        PayloadItem<ExtensionElement> item = new PayloadItem(itemId, ext);
        packet.addExtension((ExtensionElement)new PublishItem(nodeName, (Item)item));
        this.pendingPublishRequests.put(packetID, nodeName);
        this.timeoutTimer.schedule(new TimerTask() {
            public void run() {
                String nodeName = (String)PubSubPublisher.this.pendingPublishRequests.remove(packetID);
                if (nodeName != null)
                    PubSubPublisher.logger.error("Timed out a publish request: " + nodeName);
            }
        },  5000L);
        send((IQ)packet);
    }

    public void removeResponseListener(PubSubResponseListener l) {
        this.listeners.remove(l);
    }

    private void send(IQ iq) throws Exception {
        BundleContext bundleContext = StatsManagerBundleActivator.getBundleContext();
        if (bundleContext != null) {
            Collection<ComponentImpl> components = ComponentImpl.getComponents(bundleContext);
            for (ComponentImpl component : components)
                component.send(iq);
        }
    }
}
