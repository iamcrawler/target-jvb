package org.jitsi.videobridge;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import net.java.sip.communicator.util.Logger;
import org.jitsi.sctp4j.SctpSocket;

public class WebRtcDataStream {
    private static final Logger logger = Logger.getLogger(WebRtcDataStream.class);

    private final SctpConnection sctpConnection;

    private final SctpSocket socket;

    private final int sid;

    private final String label;

    private boolean acknowledged;

    private DataCallback dataCallback;

    WebRtcDataStream(SctpConnection connection, SctpSocket socket, int sid, String label, boolean acknowledged) {
        this.sctpConnection = connection;
        this.socket = socket;
        this.sid = sid;
        this.label = label;
        this.acknowledged = acknowledged;
    }

    public String getLabel() {
        return this.label;
    }

    public SctpConnection getSctpConnection() {
        return this.sctpConnection;
    }

    public int getSid() {
        return this.sid;
    }

    public boolean isAcknowledged() {
        return this.acknowledged;
    }

    protected void ackReceived() {
        this.acknowledged = true;
        logger.trace("Channel on sid: " + this.sid + " is now acknowledged");
    }

    public void onStringMsg(String stringMsg) {
        if (this.dataCallback != null) {
            this.dataCallback.onStringData(this, stringMsg);
        } else {
            logger.error(
                    String.format("Unprocessed data on %s (SID=%d) - no callback registered", new Object[] { this.sctpConnection.getLoggingId(),
                            Integer.valueOf(this.sid) }));
        }
    }

    public void sendString(String strMsg) throws IOException {
        try {
            byte[] bytes = strMsg.getBytes("UTF-8");
            int res = this.socket.send(bytes, true, this.sid, 51);
            if (res != bytes.length)
                throw new IOException("Failed to send the data");
        } catch (UnsupportedEncodingException e) {
            throw new IOException(e);
        }
    }

    public void onBinaryMsg(byte[] binMsg) {
        if (this.dataCallback != null) {
            this.dataCallback.onBinaryData(this, binMsg);
        } else {
            logger.error(
                    String.format("Unprocessed data on %s (SID=%d) - no callback registered", new Object[] { this.sctpConnection.getLoggingId(),
                            Integer.valueOf(this.sid) }));
        }
    }

    public void sendBinary(byte[] bytes) throws IOException {
        int res = this.socket.send(bytes, true, this.sid, 53);
        if (res != bytes.length)
            throw new IOException("Failed to send the data");
    }

    public void setDataCallback(DataCallback dataCallback) {
        this.dataCallback = dataCallback;
    }

    public DataCallback getDataCallback() {
        return this.dataCallback;
    }

    public static interface DataCallback {
        default void onStringData(WebRtcDataStream src, String msg) {}

        default void onBinaryData(WebRtcDataStream src, byte[] data) {}
    }
}
