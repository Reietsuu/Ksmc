package io.ksmc.e2e;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Server List Ping (protocole 1.7+) : ce que fait un client Minecraft dans la liste des serveurs. */
final class ServerListPing {
    private ServerListPing() {
    }

    static String ping(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(hs);
            varInt(h, 0);
            varInt(h, 770);
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            varInt(h, hostBytes.length);
            h.write(hostBytes);
            h.writeShort(port);
            varInt(h, 1);
            varInt(out, hs.size());
            out.write(hs.toByteArray());
            varInt(out, 1);
            varInt(out, 0);
            out.flush();
            readVarInt(in);
            readVarInt(in);
            byte[] json = new byte[readVarInt(in)];
            in.readFully(json);
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    private static void varInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = in.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
}
