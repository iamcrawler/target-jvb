package org.jitsi.videobridge.octo;

import java.io.IOException;
import java.net.UnknownHostException;
import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.NetworkUtils;
import net.java.sip.communicator.util.ServiceUtils;
import org.jitsi.service.configuration.ConfigurationService;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class OctoRelayService implements BundleActivator {
    private static final Logger logger = Logger.getLogger(OctoRelayService.class);

    public static final String ADDRESS_PNAME = "org.jitsi.videobridge.octo.BIND_ADDRESS";

    public static final String PUBLIC_ADDRESS_PNAME = "org.jitsi.videobridge.octo.PUBLIC_ADDRESS";

    public static final String PORT_PNAME = "org.jitsi.videobridge.octo.BIND_PORT";

    private OctoRelay relay;

    private ConfigurationService cfg;

    public OctoRelay getRelay() {
        return this.relay;
    }

    public void start(BundleContext bundleContext) {
        this
                .cfg = (ConfigurationService)ServiceUtils.getService(bundleContext, ConfigurationService.class);
        String address = this.cfg.getString("org.jitsi.videobridge.octo.BIND_ADDRESS", null);
        String publicAddress = this.cfg.getString("org.jitsi.videobridge.octo.PUBLIC_ADDRESS", address);
        int port = this.cfg.getInt("org.jitsi.videobridge.octo.BIND_PORT", -1);
        if (address != null && NetworkUtils.isValidPortNumber(port)) {
            try {
                this.relay = new OctoRelay(address, port);
                this.relay.setPublicAddress(publicAddress);
                bundleContext
                        .registerService(OctoRelayService.class.getName(), this, null);
            } catch (UnknownHostException|java.net.SocketException e) {
                logger.error("Failed to initialize Octo relay with address " + address + ":" + port + ". ", e);
            }
        } else {
            logger.info("Octo relay not configured.");
        }
    }

    public void stop(BundleContext bundleContext) throws Exception {
        if (this.relay != null)
            this.relay.stop();
    }

    public String getRelayId() {
        return this.relay.getId();
    }
}
