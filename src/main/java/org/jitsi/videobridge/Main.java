package org.jitsi.videobridge;

import org.jitsi.cmd.CmdLine;
import org.jitsi.meet.ComponentMain;
import org.jitsi.meet.OSGiBundleConfig;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.videobridge.osgi.JvbBundleConfig;
import org.jitsi.videobridge.xmpp.ComponentImpl;
import org.jitsi.xmpp.component.ComponentBase;

public class Main {
    private static final String APIS_ARG_NAME = "--apis";

    private static final String DOMAIN_ARG_NAME = "--domain";

    private static final String HOST_ARG_NAME = "--host";

    private static final String HOST_ARG_VALUE = "localhost";

    private static final String MAX_PORT_ARG_NAME = "--max-port";

    private static final int MAX_PORT_ARG_VALUE = 20000;

    private static final String MIN_PORT_ARG_NAME = "--min-port";

    private static final int MIN_PORT_ARG_VALUE = 10001;

    private static final String PORT_ARG_NAME = "--port";

    private static final int PORT_ARG_VALUE = 5275;

    private static final String SECRET_ARG_NAME = "--secret";

    private static final String SUBDOMAIN_ARG_NAME = "--subdomain";

    public static void main(String[] args) throws Exception {
        CmdLine cmdLine = new CmdLine();
        cmdLine.parse(args);
        String apis = cmdLine.getOptionValue("--apis", "xmpp");
        String domain = cmdLine.getOptionValue("--domain", null);
        int maxPort = cmdLine.getIntOptionValue("--max-port", 20000);
        int minPort = cmdLine.getIntOptionValue("--min-port", 10001);
        int port = cmdLine.getIntOptionValue("--port", 5275);
        String secret = cmdLine.getOptionValue("--secret", "");
        String subdomain = cmdLine.getOptionValue("--subdomain", "jitsi-videobridge");
        String host = cmdLine.getOptionValue("--host", (domain == null) ? "localhost" : domain);
        System.setProperty("org.jitsi.videobridge.rest",

                Boolean.toString(apis.contains("rest")));
        System.setProperty("org.jitsi.videobridge.xmpp",

                Boolean.toString(apis.contains("xmpp")));
        String maxPort_ = String.valueOf(maxPort);
        String minPort_ = String.valueOf(minPort);
        System.setProperty("net.java.sip.communicator.service.media.MAX_PORT_NUMBER", maxPort_);
        System.setProperty("net.java.sip.communicator.service.media.MIN_PORT_NUMBER", minPort_);
        System.setProperty(MediaService.ENABLE_H264_FORMAT_PNAME, "true");
        TransportManager.portTracker.tryRange(minPort_, maxPort_);
        ComponentMain main = new ComponentMain();
        JvbBundleConfig osgiBundles = new JvbBundleConfig();
        if (apis.contains("xmpp")) {
            ComponentImpl component = new ComponentImpl(host, port, domain, subdomain, secret);
            main.runMainProgramLoop((ComponentBase)component, (OSGiBundleConfig)osgiBundles);
        } else {
            main.runMainProgramLoop((OSGiBundleConfig)osgiBundles);
        }
    }
}
