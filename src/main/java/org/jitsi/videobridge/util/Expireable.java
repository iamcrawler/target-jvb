package org.jitsi.videobridge.util;

public interface Expireable {
    boolean shouldExpire();

    void safeExpire();
}
