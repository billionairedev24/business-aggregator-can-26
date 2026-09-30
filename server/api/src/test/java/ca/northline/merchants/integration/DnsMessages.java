package ca.northline.merchants.integration;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds DNS responses for the resolver tests (the server side of {@link DnsWire}). */
final class DnsMessages {
    private DnsMessages() {}

    /** One answer record to encode; {@code data} = address, name (CNAME) or text (TXT, split into 255-byte strings). */
    record Answer(String name, int type, String data) {}

    /** The question of a query: its id, name and type code. */
    record Question(int id, String name, int type) {}

    static Question question(byte[] query) {
        var in = ByteBuffer.wrap(query);
        var id = Short.toUnsignedInt(in.getShort());
        in.position(12);
        var name = DnsWire.readName(query, in);
        return new Question(id, name, Short.toUnsignedInt(in.getShort()));
    }

    /** A response to {@code query} with the question echoed, compression pointers to it for owner names that match. */
    static byte[] response(byte[] query, int rcode, List<Answer> answers) {
        var q = question(query);
        var out = new ByteArrayOutputStream();
        DnsWire.short16(out, q.id());
        DnsWire.short16(out, 0x8180 | rcode); // QR, RD, RA
        DnsWire.short16(out, 1);
        DnsWire.short16(out, answers.size());
        DnsWire.short16(out, 0);
        DnsWire.short16(out, 0);
        DnsWire.writeName(out, q.name());
        DnsWire.short16(out, q.type());
        DnsWire.short16(out, 1);
        for (var a : answers) {
            if (a.name().equals(q.name())) {
                DnsWire.short16(out, 0xC00C); // pointer to the question's name
            } else {
                DnsWire.writeName(out, a.name());
            }
            DnsWire.short16(out, a.type());
            DnsWire.short16(out, 1);
            out.writeBytes(new byte[] {0, 0, 1, 44}); // TTL 300
            var data = rdata(a);
            DnsWire.short16(out, data.length);
            out.writeBytes(data);
        }
        return out.toByteArray();
    }

    private static byte[] rdata(Answer a) {
        var out = new ByteArrayOutputStream();
        switch (a.type()) {
            case 1, 28 -> {
                try {
                    out.writeBytes(InetAddress.getByName(a.data()).getAddress());
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            case 5 -> DnsWire.writeName(out, a.data());
            case 16 -> {
                var bytes = a.data().getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < bytes.length; i += 255) {
                    var n = Math.min(255, bytes.length - i);
                    out.write(n);
                    out.write(bytes, i, n);
                }
            }
            default -> throw new IllegalArgumentException("type " + a.type());
        }
        return out.toByteArray();
    }
}
