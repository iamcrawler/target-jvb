package org.jitsi.videobridge.version;

import org.jitsi.utils.version.Version;
import org.jitsi.utils.version.VersionImpl;

public class CurrentVersionImpl {
    public static final int VERSION_MAJOR = 0;

    public static final int VERSION_MINOR = 1;

    public static final String PRE_RELEASE_ID = null;

    public static final String NIGHTLY_BUILD_ID = "1132";

    static final Version VERSION = (Version)new VersionImpl("JVB", 0, 1, "1132", PRE_RELEASE_ID);
}
