package org.jitsi.videobridge.stats;

import org.osgi.framework.BundleContext;

class BundleContextHolder2 {
    private BundleContext bundleContext;

    protected void bundleContextChanged(BundleContext oldValue, BundleContext newValue) {}

    protected synchronized BundleContext getBundleContext() {
        return this.bundleContext;
    }

    void start(BundleContext bundleContext) throws Exception {
        BundleContext oldValue = null, newValue = null;
        synchronized (this) {
            if (this.bundleContext != bundleContext) {
                oldValue = this.bundleContext;
                this.bundleContext = bundleContext;
                newValue = this.bundleContext;
            }
        }
        if (oldValue != newValue)
            bundleContextChanged(oldValue, newValue);
    }

    synchronized void stop(BundleContext bundleContext) throws Exception {
        BundleContext oldValue = null, newValue = null;
        synchronized (this) {
            if (this.bundleContext == bundleContext) {
                oldValue = this.bundleContext;
                this.bundleContext = null;
                newValue = this.bundleContext;
            }
        }
        if (oldValue != newValue)
            bundleContextChanged(oldValue, newValue);
    }
}
