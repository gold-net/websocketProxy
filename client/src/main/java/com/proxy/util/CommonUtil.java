package com.proxy.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.internal.AppendableCharSequence;

import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpConstants.*;

public class CommonUtil {

    public static void encodeCommandResponse(HttpResponse res, ByteBuf buf) {
        buf.writeBytes((res.protocolVersion().protocolName() + "/" + res.protocolVersion().majorVersion()
                + "." + res.protocolVersion().minorVersion()).getBytes(StandardCharsets.UTF_8));
        buf.writeByte(SP);
        buf.writeBytes((res.status().code() + " " + res.status().reasonPhrase()).getBytes(StandardCharsets.UTF_8));
        ByteBufUtil.writeShortBE(buf, (CR << 8) | LF);
        ByteBufUtil.writeShortBE(buf, (CR << 8) | LF);
    }

    public static byte getSeed(byte[] bytes) {
        return bytes[3];
    }

    public static String[] splitInitialLine(AppendableCharSequence sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        aStart = findNonSPLenient(sb, 0);
        aEnd = findSPLenient(sb, aStart);

        bStart = findNonSPLenient(sb, aEnd);
        bEnd = findSPLenient(sb, bStart);

        cStart = findNonSPLenient(sb, bEnd);
        cEnd = findEndOfString(sb);

        return new String[]{
                sb.subStringUnsafe(aStart, aEnd),
                sb.subStringUnsafe(bStart, bEnd),
                cStart < cEnd ? sb.subStringUnsafe(cStart, cEnd) : ""};
    }

    public static int findNonSPLenient(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            char c = sb.charAtUnsafe(result);
            // See https://tools.ietf.org/html/rfc7230#section-3.5
            if (isSPLenient(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                // Any other whitespace delimiter is invalid
                throw new IllegalArgumentException("Invalid separator");
            }
            return result;
        }
        return sb.length();
    }

    public static int findSPLenient(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            if (isSPLenient(sb.charAtUnsafe(result))) {
                return result;
            }
        }
        return sb.length();
    }

    public static boolean isSPLenient(char c) {
        // See https://tools.ietf.org/html/rfc7230#section-3.5
        return c == ' ' || c == (char) 0x09 || c == (char) 0x0B || c == (char) 0x0C || c == (char) 0x0D;
    }

    public static int findEndOfString(AppendableCharSequence sb) {
        for (int result = sb.length() - 1; result > 0; --result) {
            if (!Character.isWhitespace(sb.charAtUnsafe(result))) {
                return result + 1;
            }
        }
        return 0;
    }

    public static String splitHeader(AppendableCharSequence sb) {
        final int length = sb.length();
        int nameStart;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;

        nameStart = findNonWhitespace(sb, 0, false);
        for (nameEnd = nameStart; nameEnd < length; nameEnd++) {
            char ch = sb.charAtUnsafe(nameEnd);
            if (ch == ':') {
                break;
            }
        }

        if (nameEnd == length) {
            // There was no colon present at all.
            throw new IllegalArgumentException("No colon found");
        }

        for (colonEnd = nameEnd; colonEnd < length; colonEnd++) {
            if (sb.charAtUnsafe(colonEnd) == ':') {
                colonEnd++;
                break;
            }
        }

        // name = sb.subStringUnsafe(nameStart, nameEnd);
        valueStart = findNonWhitespace(sb, colonEnd, true);
        if (valueStart == length) {
            return "";
        } else {
            valueEnd = findEndOfString(sb);
            return sb.subStringUnsafe(valueStart, valueEnd);
        }
    }

    public static int findNonWhitespace(AppendableCharSequence sb, int offset, boolean validateOWS) {
        for (int result = offset; result < sb.length(); ++result) {
            char c = sb.charAtUnsafe(result);
            if (!Character.isWhitespace(c)) {
                return result;
            } else if (validateOWS && !isOWS(c)) {
                // Only OWS is supported for whitespace
                throw new IllegalArgumentException("Invalid separator, only a single space or horizontal tab allowed," +
                        " but received a '" + c + "' (0x" + Integer.toHexString(c) + ")");
            }
        }
        return sb.length();
    }

    public static boolean isOWS(char ch) {
        return ch == ' ' || ch == (char) 0x09;
    }

}
