package org.jitsi.videobridge.xmpp;


import java.util.Collection;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.meet.OSGi;
import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.xmpp.component.ComponentBase;
import org.jitsi.xmpp.util.IQUtils;
import org.jivesoftware.smack.packet.IQ;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
//import org.xmpp.packet.IQ;
//import org.jivesoftware.smack.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

public class ComponentImpl extends ComponentBase implements BundleActivator {
    private static final Logger logger = Logger.getLogger(ComponentImpl.class);

    private static final String DESCRIPTION = "Jitsi Videobridge Jabber Component";

    private static final String NAME = "JitsiVideobridge";

    public static final String SUBDOMAIN = "jitsi-videobridge";

    public static Collection<ComponentImpl> getComponents(BundleContext bundleContext) {
        return ServiceUtils2.getServices(bundleContext, ComponentImpl.class);
    }

    private final XmppCommon common = new XmppCommon();

    public ComponentImpl(String host, int port, String domain, String subDomain, String secret) {
        super(host, port, domain, subDomain, secret);
    }

    protected String[] discoInfoFeatureNamespaces() {
        return (String[])XmppCommon.FEATURES.clone();
    }

    protected String discoInfoIdentityCategoryType() {
        return "conference";
    }

    public String getDescription() {
        return "Jitsi Videobridge Jabber Component";
    }

    public String getName() {
        return "JitsiVideobridge";
    }

    public Videobridge getVideobridge() {
        return this.common.getVideobridge();
    }

    private org.xmpp.packet.IQ handleIQ(org.xmpp.packet.IQ iq) throws Exception {
        try {
            org.xmpp.packet.IQ resultIQ;
            IQ smackIQ = IQUtils.convert(iq);
            if (smackIQ == null) {
                if (iq.isRequest()) {
                    org.xmpp.packet.IQ error = new org.xmpp.packet.IQ(org.xmpp.packet.IQ.Type.error, iq.getID());
                    error.setFrom(iq.getTo());
                    error.setTo(iq.getFrom());
                    error.setError(new PacketError(PacketError.Condition.bad_request, PacketError.Type.modify, "Failed to parse incoming stanza"));
                    return error;
                }
                logger.error("Failed to convert stanza: " + iq.toXML());
            }
            IQ resultSmackIQ = this.common.handleIQ(smackIQ);
            if (resultSmackIQ == null) {
                resultIQ = null;
            } else {
                resultIQ = IQUtils.convert(resultSmackIQ);
            }
            return resultIQ;
        } catch (Exception e) {
            logger.error("Failed to handle IQ with id=" + ((iq == null) ? "null" : iq

                    .getID()), e);
            throw e;
        }
    }

    protected void handleIQErrorImpl(org.xmpp.packet.IQ iq) {
        super.handleIQErrorImpl(iq);
        try {
            handleIQ(iq);
        } catch (Exception e) {
            logger.error("An error occurred while trying to handle an 'error' IQ.", e);
        }
    }

    protected org.xmpp.packet.IQ handleIQGetImpl(org.xmpp.packet.IQ iq) throws Exception {
        org.xmpp.packet.IQ resultIQ = handleIQ(iq);
        return (resultIQ == null) ? super.handleIQGetImpl(iq) : resultIQ;
    }

    protected void handleIQResultImpl(org.xmpp.packet.IQ iq) {
        super.handleIQResultImpl(iq);
        try {
            handleIQ(iq);
        } catch (Exception e) {
            logger.error("An error occurred while trying to handle a 'result' IQ.", e);
        }
    }

    protected org.xmpp.packet.IQ handleIQSetImpl(org.xmpp.packet.IQ iq) throws Exception {
        org.xmpp.packet.IQ resultIQ = handleIQ(iq);
        return (resultIQ == null) ? super.handleIQSetImpl(iq) : resultIQ;
    }

    public void postComponentShutdown() {
        super.postComponentShutdown();
        OSGi.stop(this);
    }

    public void postComponentStart() {
        super.postComponentStart();
        OSGi.start(this);
    }

    public void send(IQ iq) throws Exception {
        try {
            Jid from = iq.getFrom();
            if (from == null || from.length() == 0) {
                JID fromJID = getJID();
                if (fromJID != null)
                    iq.setFrom(JidCreate.from(fromJID.toString()));
            }
            org.xmpp.packet.IQ iQ = IQUtils.convert(iq);
            send((Packet)iQ);
            if (logger.isDebugEnabled())
                logger.debug("SENT: " + iQ.toXML());
        } catch (Exception e) {
            logger.error("Failed to send an IQ with id=" + ((iq == null) ? "null" : iq

                    .getStanzaId()), e);
            throw e;
        }
    }

    public void start(BundleContext bundleContext) throws Exception {
        this.common.start(bundleContext);
        Collection<ComponentImpl> components = getComponents(bundleContext);
        if (!components.contains(this))
            bundleContext.registerService(ComponentImpl.class, this, null);
        ConfigurationService config = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        loadConfig(config, "org.jitsi.videobridge");
        if (!isPingTaskStarted())
            startPingTask();
    }

    public void stop(BundleContext bundleContext) throws Exception {
        try {
            Collection<ServiceReference<ComponentImpl>> serviceReferences = bundleContext.getServiceReferences(ComponentImpl.class, null);
            if (serviceReferences != null)
                for (ServiceReference<ComponentImpl> serviceReference : serviceReferences) {
                    Object service = bundleContext.getService(serviceReference);
                    if (service == this)
                        bundleContext.ungetService(serviceReference);
                }
        } finally {
            this.common.stop(bundleContext);
        }
    }
}
