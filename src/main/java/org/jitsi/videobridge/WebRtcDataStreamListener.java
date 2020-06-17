package org.jitsi.videobridge;

public interface WebRtcDataStreamListener {
    default void onChannelOpened(SctpConnection source, WebRtcDataStream channel) {}
}
