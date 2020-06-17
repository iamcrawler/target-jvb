package org.jitsi.videobridge.octo;

import org.jitsi.util.RTPUtils;
import org.jitsi.utils.MediaType;

public class OctoPacket {
    public static final int OCTO_HEADER_LENGTH = 8;

    public static final int OCTO_MEDIA_TYPE_AUDIO = 0;

    public static final int OCTO_MEDIA_TYPE_VIDEO = 1;

    public static final int OCTO_MEDIA_TYPE_DATA = 2;

    private static int getMediaTypeId(MediaType mediaType) {
        switch (mediaType) {
            case AUDIO:
                return 0;
            case VIDEO:
                return 1;
            case DATA:
                return 2;
        }
        return -1;
    }

    public static void writeHeaders(byte[] buf, int off, boolean r, MediaType mediaType, int s, String conferenceId, String endpointId) {
        buf[off] = 0;
        if (r)
            buf[off] = (byte)(buf[off] | 0x80);
        buf[off] = (byte)(buf[off] | (getMediaTypeId(mediaType) & 0x3) << 5);
        buf[off] = (byte)(buf[off] | (s & 0x3) << 3);
        writeConferenceId(conferenceId, buf, off, 8);
        writeEndpointId(endpointId, buf, off, 8);
    }

    public static String readConferenceId(byte[] buf, int off, int len) {
        assertMinLen(buf, off, len);
        int cid = RTPUtils.readUint24AsInt(buf, off + 1);
        return Integer.toHexString(cid);
    }

    public static MediaType readMediaType(byte[] buf, int off, int len) {
        assertMinLen(buf, off, len);
        int mediaType = (buf[off] & 0x60) >> 5;
        switch (mediaType) {
            case 0:
                return MediaType.AUDIO;
            case 1:
                return MediaType.VIDEO;
            case 2:
                return MediaType.DATA;
        }
        return null;
    }

    public static boolean readRflag(byte[] buf, int off, int len) {
        assertMinLen(buf, off, len);
        return ((buf[off] & 0x80) != 0);
    }

    public static String readEndpointId(byte[] buf, int off, int len) {
        assertMinLen(buf, off, len);
        long eid = RTPUtils.readUint32AsLong(buf, off + 4);
        return Long.toHexString(eid);
    }

    public static void writeConferenceId(String conferenceId, byte[] buf, int off, int len) {
        assertMinLen(buf, off, len);
        int cid = Integer.parseInt(conferenceId, 16);
        RTPUtils.writeUint24(buf, off + 1, cid);
    }

    public static void writeEndpointId(String endpointId, byte[] buf, int off, int len) {
        assertMinLen(buf, off, len);
        long eid = Long.parseLong(endpointId, 16);
        RTPUtils.writeInt(buf, off + 4, (int)eid);
    }

    private static void assertMinLen(byte[] buf, int off, int len) {
        if (!verifyMinLength(buf, off, len, 8))
            throw new IllegalArgumentException("Invalid Octo packet.");
    }

    private static boolean verifyMinLength(byte[] buf, int off, int len, int minLen) {
        return (buf != null && off >= 0 && len >= minLen && minLen >= 0 && off + len < buf.length);
    }
}
