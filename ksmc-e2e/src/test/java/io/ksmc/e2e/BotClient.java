package io.ksmc.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Joueur bot minimal (protocole 1.8, n° 47, mode hors ligne) : connexion, réponses aux keep-alive, compression.
 * Suffit à traverser le réseau (proxy → serveur, changement de serveur, expulsion) sans client Minecraft.
 */
final class BotClient implements AutoCloseable {
    static final int PROTOCOL_1_8 = 47;

    private final String name;
    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;
    private int threshold = -1;
    private volatile boolean playing;
    private volatile String disconnectReason;
    private volatile int joins;
    private final Thread reader;

    private BotClient(String name, Socket socket) throws IOException {
        this.name = name;
        this.socket = socket;
        this.out = new DataOutputStream(socket.getOutputStream());
        this.in = new DataInputStream(socket.getInputStream());
        this.reader = new Thread(this::readLoop, "bot-" + name);
        this.reader.setDaemon(true);
    }

    /** Se connecte et commence la phase de login (asynchrone : voir {@link #playing()}). */
    static BotClient connect(String host, int port, String name) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 10_000);
        BotClient bot = new BotClient(name, s);
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(handshake);
        writeVarInt(h, 0x00);
        writeVarInt(h, PROTOCOL_1_8);
        writeString(h, host);
        h.writeShort(port);
        writeVarInt(h, 2);
        bot.send(handshake.toByteArray());
        ByteArrayOutputStream login = new ByteArrayOutputStream();
        DataOutputStream l = new DataOutputStream(login);
        writeVarInt(l, 0x00);
        writeString(l, name);
        bot.send(login.toByteArray());
        bot.reader.start();
        return bot;
    }

    /** Vrai après « Join Game » (le joueur est sur un serveur). */
    boolean playing() {
        return playing && disconnectReason == null;
    }

    /** Nombre de « Join Game » / « Respawn » de changement de serveur reçus. */
    int joins() {
        return joins;
    }

    String disconnectReason() {
        return disconnectReason;
    }

    private void readLoop() {
        boolean login = true;
        try {
            while (!socket.isClosed()) {
                DataInputStream p = new DataInputStream(new ByteArrayInputStream(readPacket()));
                int id = readVarInt(p);
                if (login) {
                    switch (id) {
                        case 0x00 -> disconnectReason = readString(p);
                        case 0x02 -> login = false; // Login Success
                        case 0x03 -> threshold = readVarInt(p); // Set Compression
                        default -> { }
                    }
                    if (disconnectReason != null) {
                        return;
                    }
                    continue;
                }
                switch (id) {
                    case 0x00 -> { // Keep Alive
                        int keepAlive = readVarInt(p);
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        DataOutputStream d = new DataOutputStream(b);
                        writeVarInt(d, 0x00);
                        writeVarInt(d, keepAlive);
                        send(b.toByteArray());
                    }
                    case 0x01 -> { // Join Game
                        playing = true;
                        joins++;
                    }
                    case 0x07 -> joins++; // Respawn (changement de serveur par le proxy)
                    case 0x40 -> { // Disconnect
                        disconnectReason = readString(p);
                        return;
                    }
                    default -> { }
                }
            }
        } catch (IOException e) {
            if (disconnectReason == null) {
                disconnectReason = "connexion fermée : " + e.getMessage();
            }
        }
    }

    private byte[] readPacket() throws IOException {
        int length = readVarInt(in);
        byte[] data = new byte[length];
        in.readFully(data);
        if (threshold < 0) {
            return data;
        }
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
        int uncompressed = readVarInt(d);
        byte[] rest = d.readAllBytes();
        if (uncompressed == 0) {
            return rest;
        }
        Inflater inflater = new Inflater();
        inflater.setInput(rest);
        byte[] result = new byte[uncompressed];
        try {
            int n = 0;
            while (n < uncompressed && !inflater.finished()) {
                n += inflater.inflate(result, n, uncompressed - n);
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        return result;
    }

    private synchronized void send(byte[] packet) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream f = new DataOutputStream(frame);
        if (threshold < 0) {
            writeVarInt(f, packet.length);
            f.write(packet);
        } else if (packet.length < threshold) {
            writeVarInt(f, packet.length + 1);
            writeVarInt(f, 0);
            f.write(packet);
        } else {
            Deflater deflater = new Deflater();
            deflater.setInput(packet);
            deflater.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                z.write(buf, 0, deflater.deflate(buf));
            }
            deflater.end();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeVarInt(new DataOutputStream(body), packet.length);
            body.write(z.toByteArray());
            writeVarInt(f, body.size());
            f.write(body.toByteArray());
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    static void writeVarInt(OutputStream o, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            o.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        o.write(value);
    }

    static int readVarInt(InputStream i) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = i.read();
            if (b < 0) {
                throw new EOFException();
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt trop long");
    }

    static void writeString(OutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(o, b.length);
        o.write(b);
    }

    static String readString(DataInputStream i) throws IOException {
        byte[] b = new byte[readVarInt(i)];
        i.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    @Override
    public String toString() {
        return name + (playing ? " (en jeu)" : "") + (disconnectReason != null ? " déconnecté : " + disconnectReason : "");
    }
}
