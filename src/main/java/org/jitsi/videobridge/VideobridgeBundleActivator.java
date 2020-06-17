package org.jitsi.videobridge;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class VideobridgeBundleActivator implements BundleActivator {
    private ServiceRegistration<Videobridge> serviceRegistration;

    public void start(BundleContext bundleContext) throws Exception {
        Videobridge videobridge = new Videobridge();
        videobridge.start(bundleContext);
        ServiceRegistration<Videobridge> serviceRegistration = null;
        try {
            serviceRegistration = bundleContext.registerService(Videobridge.class, videobridge, null);
        } finally {
            if (serviceRegistration == null) {
                videobridge.stop(bundleContext);
            } else {
                this.serviceRegistration = serviceRegistration;
            }
        }
    }

    public void stop(BundleContext bundleContext) throws Exception {
        ServiceRegistration<Videobridge> serviceRegistration = this.serviceRegistration;
        this.serviceRegistration = null;
        Videobridge videobridge = null;
        try {
            videobridge = (Videobridge)bundleContext.getService(serviceRegistration.getReference());
        } finally {
            serviceRegistration.unregister();
            if (videobridge != null)
                videobridge.stop(bundleContext);
        }
    }
}
