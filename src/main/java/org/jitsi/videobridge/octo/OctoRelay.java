package org.jitsi.videobridge.octo;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import org.ice4j.socket.MultiplexingDatagramSocket;
import org.jitsi.utils.logging.Logger;

public class OctoRelay {
    private static final Logger logger = Logger.getLogger(OctoRelay.class);

    private static final int SO_RCVBUF = 10485760;

    private MultiplexingDatagramSocket socket;

    private String relayId;

    private String publicAddress;

    private int port;

    public OctoRelay(String address, int port) throws UnknownHostException, SocketException {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(address), port);
        DatagramSocket s = new DatagramSocket(addr);
        s.setReceiveBufferSize(10485760);
        logger.info("Initialized OctoRelay with address " + addr + ". Receive buffer size " + s
                .getReceiveBufferSize() + " (asked for " + 10485760 + ").");
        this.socket = new MultiplexingDatagramSocket(s, true) {
            public void setReceiveBufferSize(int size) {}
        };
        this.port = port;
        String id = address + ":" + port;
        setRelayId(id);
    }

    void stop() {
        try {
            this.socket.close();
        } catch (Exception e) {
            logger.warn("Failed to stop OctoRelay: ", e);
        }
    }

    public String getId() {
        return this.relayId;
    }

    public void setRelayId(String id) {
        this.relayId = id;
    }

    public void setPublicAddress(String address) {
        this.publicAddress = address;
        String id = this.publicAddress + ":" + this.port;
        setRelayId(id);
    }

    public MultiplexingDatagramSocket getSocket() {
        return this.socket;
    }
}
