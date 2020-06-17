package org.jitsi.videobridge.cc;


public class RtpState {
    public final long ssrc;

    public final long maxTimestamp;

    public final int maxSequenceNumber;

    public final long transmittedBytes;

    public final long transmittedPackets;

    public RtpState(long transmittedBytes, long transmittedPackets, long ssrc, int maxSequenceNumber, long maxTimestamp) {
        this.ssrc = ssrc;
        this.transmittedBytes = transmittedBytes;
        this.transmittedPackets = transmittedPackets;
        this.maxSequenceNumber = maxSequenceNumber;
        this.maxTimestamp = maxTimestamp;
    }
}
