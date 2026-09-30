package ca.northline.merchants.integration;

import ca.northline.merchants.application.DnsResolver;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The DNS wire format (RFC 1035 § 4) for DNS over HTTPS (RFC 8484 {@code application/dns-message}): one question out,
 * the answer section back — A, AAAA, CNAME and TXT records; everything else is skipped. Name compression is read.
 */
final class DnsWire {
    private DnsWire() {}

    static final int NOERROR = 0;
    static final int SERVFAIL = 2;
    static final int NXDOMAIN = 3;

    /** One answer record: owner name, type code, TTL and the decoded data (name, address or joined TXT). */
    record ResourceRecord(String name, int type, long ttl, String data) {}

    record Response(int rcode, boolean truncated, List<ResourceRecord> answers) {
        Response {
            answers = List.copyOf(answers);
        }
    }

    static int code(DnsResolver.Type type) {
        return switch (type) {
            case A -> 1;
            case CNAME -> 5;
            case TXT -> 16;
            case AAAA -> 28;
        };
    }

    static DnsResolver.@Nullable Type type(int code) {
        return switch (code) {
            case 1 -> DnsResolver.Type.A;
            case 5 -> DnsResolver.Type.CNAME;
            case 16 -> DnsResolver.Type.TXT;
            case 28 -> DnsResolver.Type.AAAA;
            default -> null;
        };
    }

    /** A recursive query (RD) for {@code name}/{@code type}, class IN; id 0 as RFC 8484 § 4.1 recommends. */
    static byte[] query(String name, DnsResolver.Type type) {
        return query(0, name, type);
    }

    static byte[] query(int id, String name, DnsResolver.Type type) {
        var out = new ByteArrayOutputStream();
        short16(out, id);
        short16(out, 0x0100); // RD
        short16(out, 1);
        short16(out, 0);
        short16(out, 0);
        short16(out, 0);
        writeName(out, name);
        short16(out, code(type));
        short16(out, 1); // IN
        return out.toByteArray();
    }

    static Response parse(byte[] message) {
        var in = ByteBuffer.wrap(message);
        if (message.length < 12) {
            throw new IllegalArgumentException("DNS message shorter than its header");
        }
        in.getShort(); // id
        var flags = Short.toUnsignedInt(in.getShort());
        var questions = Short.toUnsignedInt(in.getShort());
        var answers = Short.toUnsignedInt(in.getShort());
        in.getShort();
        in.getShort();
        for (int i = 0; i < questions; i++) {
            readName(message, in);
            in.position(in.position() + 4);
        }
        var records = new ArrayList<ResourceRecord>();
        for (int i = 0; i < answers; i++) {
            var owner = readName(message, in);
            var type = Short.toUnsignedInt(in.getShort());
            in.getShort(); // class
            var ttl = Integer.toUnsignedLong(in.getInt());
            var length = Short.toUnsignedInt(in.getShort());
            var start = in.position();
            var data = switch (type) {
                case 1, 28 -> address(message, start, length);
                case 5 -> readName(message, ByteBuffer.wrap(message).position(start));
                case 16 -> text(message, start, length);
                default -> null;
            };
            if (data != null) {
                records.add(new ResourceRecord(owner, type, ttl, data));
            }
            in.position(start + length);
        }
        return new Response(flags & 0x0F, (flags & 0x0200) != 0, records);
    }

    static void writeName(ByteArrayOutputStream out, String name) {
        var n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (!n.isEmpty()) {
            for (var label : n.split("\\.")) {
                var bytes = label.getBytes(StandardCharsets.US_ASCII);
                if (bytes.length == 0 || bytes.length > 63) {
                    throw new IllegalArgumentException("Bad DNS label in " + name);
                }
                out.write(bytes.length);
                out.writeBytes(bytes);
            }
        }
        out.write(0);
    }

    static void short16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** Reads a (possibly compressed) name at the buffer's position and leaves it after the name. */
    static String readName(byte[] message, ByteBuffer in) {
        var labels = new ArrayList<String>();
        var position = in.position();
        var jumped = false;
        for (int guard = 0; guard < 128; guard++) {
            var length = Byte.toUnsignedInt(message[position]);
            if (length == 0) {
                if (!jumped) {
                    in.position(position + 1);
                }
                return String.join(".", labels).toLowerCase(Locale.ROOT);
            }
            if ((length & 0xC0) == 0xC0) {
                var pointer = ((length & 0x3F) << 8) | Byte.toUnsignedInt(message[position + 1]);
                if (!jumped) {
                    in.position(position + 2);
                }
                jumped = true;
                position = pointer;
                continue;
            }
            labels.add(new String(message, position + 1, length, StandardCharsets.US_ASCII));
            position += 1 + length;
        }
        throw new IllegalArgumentException("DNS name compression loop");
    }

    private static String address(byte[] message, int start, int length) {
        try {
            var bytes = new byte[length];
            System.arraycopy(message, start, bytes, 0, length);
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Bad address record", e);
        }
    }

    /** TXT data: its character-strings joined (long values are split into 255-byte strings). */
    private static String text(byte[] message, int start, int length) {
        var out = new StringBuilder();
        var position = start;
        while (position < start + length) {
            var n = Byte.toUnsignedInt(message[position]);
            out.append(new String(message, position + 1, n, StandardCharsets.UTF_8));
            position += 1 + n;
        }
        return out.toString();
    }
}
