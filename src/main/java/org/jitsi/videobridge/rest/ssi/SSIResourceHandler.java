package org.jitsi.videobridge.rest.ssi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.util.ConfigUtils;

public class SSIResourceHandler extends ResourceHandler {
    private static final String JETTY_SSI_RESOURCE_HANDLER_PATHS = ".jetty.SSIResourceHandler.paths";

    private static final String OLD_PREFIX = "org.jitsi.videobridge.rest";

    private static final String SSI_CMD_START = "<!--#";

    private static final String SSI_CMD_END = "-->";

    private static final String SSI_CMD_INCLUDE = "include";

    private static final String SSI_PARAM_VIRTUAL = "virtual";

    private static final String SSI_PARAM_FILE = "file";

    protected final ConfigurationService cfg;

    private final List<String> ssiPaths;

    public SSIResourceHandler(ConfigurationService cfg) {
        this.cfg = cfg;
        String paths = ConfigUtils.getString(cfg, "org.jitsi.videobridge.rest.jetty.SSIResourceHandler.paths", "org.jitsi.videobridge.rest.jetty.SSIResourceHandler.paths", null);
        if (paths == null) {
            this.ssiPaths = Collections.emptyList();
        } else {
            this.ssiPaths = Arrays.asList(paths.split(";"));
        }
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (this.ssiPaths.contains(target)) {
            handleSSIRequest(target, baseRequest, request, response);
        } else {
            super.handle(target, baseRequest, request, response);
        }
    }

    private void handleSSIRequest(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        HttpServletResponseWrapper servletResponseWrapper = new HttpServletResponseWrapper(response);
        super.handle(target, baseRequest, request, servletResponseWrapper);
        byte[] processedResult = processContentForServerSideIncludes(servletResponseWrapper
                .getContent());
        response.setContentLength(processedResult.length);
        response.getOutputStream().write(processedResult);
    }

    private byte[] processContentForServerSideIncludes(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (content == null)
            return out.toByteArray();
        Scanner scanner = (new Scanner(new ByteArrayInputStream(content), "UTF-8")).useDelimiter("(?<=\n)|(?!\n)(?<=\r)");
        Charset charset = StandardCharsets.UTF_8;
        while (scanner.hasNext()) {
            String line = scanner.next();
            int startIx = line.indexOf("<!--#");
            if (startIx != -1) {
                int endIx = line.indexOf("-->", startIx + "<!--#"

                        .length());
                if (endIx != -1) {
                    String cmd = line.substring(startIx + "<!--#"
                            .length(), endIx);
                    out.write(line
                            .substring(0, startIx).getBytes(charset));
                    if (!processSSICmd(cmd, out)) {
                        out.write("<!--#".getBytes(charset));
                        out.write(cmd.getBytes(charset));
                        out.write("-->".getBytes(charset));
                    }
                    out.write(line
                            .substring(endIx + "-->"
                                    .length(), line
                                    .length())
                            .getBytes(charset));
                    continue;
                }
            }
            out.write(line.getBytes(charset));
        }
        return out.toByteArray();
    }

    private boolean processSSICmd(String cmd, OutputStream out) throws IOException {
        if (cmd.startsWith("include") && cmd.contains("=")) {
            String parameterName = cmd.substring("include".length(), cmd.indexOf("=")).trim();
            if (!"virtual".equals(parameterName) &&
                    !"file".equals(parameterName))
                return false;
            String fileToInclude = cmd.substring(cmd.indexOf("=") + 1).trim();
            fileToInclude = fileToInclude.replaceAll("\\\"", "");
            if ("virtual".equals(parameterName)) {
                if (!fileToInclude.startsWith("/"))
                    fileToInclude = "/" + fileToInclude;
                String property = ".jetty.ResourceHandler.alias." + fileToInclude;
                String aliasValue = ConfigUtils.getString(this.cfg, "org.jitsi.videobridge.rest" + property, "org.jitsi.videobridge.rest" + property, null);
                if (aliasValue != null)
                    fileToInclude = aliasValue;
            }
            Resource r = Resource.newResource(fileToInclude);
            if (r.exists()) {
                r.writeTo(out, 0L, r.length());
                return true;
            }
        }
        return false;
    }
}
