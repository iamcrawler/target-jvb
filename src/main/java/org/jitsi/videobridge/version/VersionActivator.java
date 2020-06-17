package org.jitsi.videobridge.version;

import org.jitsi.utils.version.Version;
import org.jitsi.version.AbstractVersionActivator;

public class VersionActivator extends AbstractVersionActivator {
    protected Version getCurrentVersion() {
        return CurrentVersionImpl.VERSION;
    }
}
