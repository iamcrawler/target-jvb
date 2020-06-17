package org.jitsi.videobridge.rest.ssi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Locale;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

public class HttpServletResponseWrapper implements HttpServletResponse {
    private ByteArrayServletOutputStream outputStream;

    private final HttpServletResponse servletResponse;

    public HttpServletResponseWrapper(HttpServletResponse servletResponse) {
        this.servletResponse = servletResponse;
    }

    public void addCookie(Cookie cookie) {
        this.servletResponse.addCookie(cookie);
    }

    public void addDateHeader(String s, long l) {
        this.servletResponse.addDateHeader(s, l);
    }

    public void addHeader(String s, String s1) {
        this.servletResponse.addHeader(s, s1);
    }

    public void addIntHeader(String s, int i) {
        this.servletResponse.addIntHeader(s, i);
    }

    public boolean containsHeader(String s) {
        return this.servletResponse.containsHeader(s);
    }

    public String encodeRedirectURL(String s) {
        return this.servletResponse.encodeRedirectURL(s);
    }

    public String encodeRedirectUrl(String s) {
        return this.servletResponse.encodeRedirectUrl(s);
    }

    public String encodeURL(String s) {
        return this.servletResponse.encodeURL(s);
    }

    public String encodeUrl(String s) {
        return this.servletResponse.encodeURL(s);
    }

    public void flushBuffer() throws IOException {
        this.servletResponse.flushBuffer();
    }

    public int getBufferSize() {
        return this.servletResponse.getBufferSize();
    }

    public String getCharacterEncoding() {
        return this.servletResponse.getCharacterEncoding();
    }

    byte[] getContent() {
        return (this.outputStream == null) ? null : this.outputStream.resContent

                .toByteArray();
    }

    public String getContentType() {
        return this.servletResponse.getContentType();
    }

    public String getHeader(String s) {
        return this.servletResponse.getHeader(s);
    }

    public Collection<String> getHeaderNames() {
        return this.servletResponse.getHeaderNames();
    }

    public Collection<String> getHeaders(String s) {
        return this.servletResponse.getHeaders(s);
    }

    public Locale getLocale() {
        return this.servletResponse.getLocale();
    }

    public ServletOutputStream getOutputStream() throws IOException {
        if (this.outputStream == null)
            this.outputStream = new ByteArrayServletOutputStream();
        return this.outputStream;
    }

    public int getStatus() {
        return this.servletResponse.getStatus();
    }

    public PrintWriter getWriter() throws IOException {
        return this.servletResponse.getWriter();
    }

    public boolean isCommitted() {
        return this.servletResponse.isCommitted();
    }

    public void reset() {
        this.servletResponse.reset();
    }

    public void resetBuffer() {
        this.servletResponse.resetBuffer();
    }

    public void setBufferSize(int bufferSize) {
        this.servletResponse.setBufferSize(bufferSize);
    }

    public void setCharacterEncoding(String characterEncoding) {
        this.servletResponse.setCharacterEncoding(characterEncoding);
    }

    public void setContentLength(int contentLength) {
        this.servletResponse.setContentLength(contentLength);
    }

    public void setContentLengthLong(long contentLength) {
        this.servletResponse.setContentLengthLong(contentLength);
    }

    public void setContentType(String contentType) {
        this.servletResponse.setContentType(contentType);
    }

    public void setDateHeader(String s, long l) {
        this.servletResponse.setDateHeader(s, l);
    }

    public void setHeader(String s, String s1) {
        this.servletResponse.setHeader(s, s1);
    }

    public void setIntHeader(String s, int i) {
        this.servletResponse.setIntHeader(s, i);
    }

    public void setLocale(Locale locale) {
        this.servletResponse.setLocale(locale);
    }

    public void setStatus(int i) {
        this.servletResponse.setStatus(i);
    }

    public void setStatus(int i, String s) {
        this.servletResponse.setStatus(i, s);
    }

    public void sendError(int i) throws IOException {
        this.servletResponse.sendError(i);
    }

    public void sendError(int i, String s) throws IOException {
        this.servletResponse.sendError(i, s);
    }

    public void sendRedirect(String s) throws IOException {
        this.servletResponse.sendRedirect(s);
    }

    private static class ByteArrayServletOutputStream extends ServletOutputStream {
        final ByteArrayOutputStream resContent = new ByteArrayOutputStream();

        public void write(int b) throws IOException {
            this.resContent.write(b);
        }

        public boolean isReady() {
            return true;
        }

        public void setWriteListener(WriteListener writeListener) {}

        private ByteArrayServletOutputStream() {}
    }
}
