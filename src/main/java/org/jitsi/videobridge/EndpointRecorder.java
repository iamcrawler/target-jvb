package org.jitsi.videobridge;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.jitsi.utils.logging.Logger;
import org.json.simple.JSONValue;

@Deprecated
public class EndpointRecorder {
    private static final Logger logger = Logger.getLogger(EndpointRecorder.class);

    private final File file;

    private boolean closed;

    private final Map<String, EndpointInfo> endpoints = new HashMap<>();

    public EndpointRecorder(String filename) throws IOException {
        this.file = new File(filename);
        if (!this.file.createNewFile())
            throw new IOException("File exists or cannot be created: " + this.file);
        if (!this.file.canWrite())
            throw new IOException("Cannot write to file: " + this.file);
        this.closed = false;
    }

    public void updateEndpoint(AbstractEndpoint endpoint) {
        String id = endpoint.getID();
        synchronized (this.endpoints) {
            EndpointInfo endpointInfo = this.endpoints.get(id);
            if (endpointInfo == null) {
                endpointInfo = new EndpointInfo(endpoint);
                this.endpoints.put(id, endpointInfo);
            } else {
                endpointInfo.displayName = endpoint.getDisplayName();
            }
        }
        writeEndpoints();
    }

    public void close() {
        writeEndpoints();
        this.closed = true;
    }

    private void writeEndpoints() {
        if (this.closed)
            return;
        try {
            FileWriter writer = new FileWriter(this.file, false);
            writer.write("[\n");
            synchronized (this.endpoints) {
                int size = this.endpoints.size();
                int idx = 0;
                for (EndpointInfo endpointInfo : this.endpoints.values()) {
                    writer.write("    ");
                    writer.write(endpointInfo.getJSON());
                    if (++idx != size)
                        writer.write(",");
                    writer.write("\n");
                }
            }
            writer.write("]\n");
            writer.close();
        } catch (IOException ioe) {
            logger.warn("Failed to write endpoints: " + ioe);
        }
    }

    private static class EndpointInfo {
        private final String id;

        String displayName;

        private EndpointInfo(AbstractEndpoint endpoint) {
            this.id = endpoint.getID();
            this.displayName = endpoint.getDisplayName();
        }

        private String getJSON() {
            return "{\"id\":\"" +
                    JSONValue.escape(this.id) + "\",\"displayName\":\"" +
                    JSONValue.escape(this.displayName) + "\"}";
        }
    }
}
