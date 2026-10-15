package io.ksmc.wrapper;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Client Server List Ping (protocole Minecraft 1.7+) : renvoie le JSON de statut du serveur. */
final class ServerListPing {
    private ServerListPing() {
    }

    static String ping(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            writeVarInt(h, 0x00);
            writeVarInt(h, -1); // protocole inconnu : accepté pour le statut
            writeString(h, host);
            h.writeShort(port);
            writeVarInt(h, 1); // état suivant : status
            writeVarInt(out, handshake.size());
            out.write(handshake.toByteArray());

            writeVarInt(out, 1);
            writeVarInt(out, 0x00); // status request
            out.flush();

            readVarInt(in); // longueur du paquet
            int id = readVarInt(in);
            if (id != 0x00) {
                throw new IOException("unexpected packet id " + id);
            }
            int length = readVarInt(in);
            if (length <= 0 || length > 1 << 21) {
                throw new IOException("invalid status length " + length);
            }
            byte[] json = new byte[length];
            in.readFully(json);
            return new String(json, IoUtil.UTF8);
        } finally {
            socket.close();
        }
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            byte b = in.readByte();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt too big");
            }
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(IoUtil.UTF8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}
