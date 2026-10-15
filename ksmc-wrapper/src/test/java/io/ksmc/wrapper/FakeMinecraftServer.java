package io.ksmc.wrapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Faux serveur Minecraft pour les tests : répond au Server List Ping et s'arrête sur {@code stop}
 * (sauf si lancé avec {@code ignore-stop}).
 */
public final class FakeMinecraftServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        boolean ignoreStop = args.length > 1 && args[1].equals("ignore-stop");
        ServerSocket server = new ServerSocket(port);
        Thread accept = new Thread(() -> {
            while (true) {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    int len = ServerListPing.readVarInt(in);
                    in.readFully(new byte[len]);
                    ServerListPing.readVarInt(in);
                    ServerListPing.readVarInt(in);
                    byte[] json = "{\"players\":{\"online\":3,\"max\":20}}".getBytes("UTF-8");
                    ByteArrayOutputStream pkt = new ByteArrayOutputStream();
                    DataOutputStream p = new DataOutputStream(pkt);
                    ServerListPing.writeVarInt(p, 0);
                    ServerListPing.writeVarInt(p, json.length);
                    p.write(json);
                    ServerListPing.writeVarInt(out, pkt.size());
                    out.write(pkt.toByteArray());
                    out.flush();
                } catch (Exception e) {
                    if (server.isClosed()) {
                        return;
                    }
                }
            }
        });
        accept.setDaemon(true);
        accept.start();
        System.out.println("Done! fake server on " + port);
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = stdin.readLine()) != null) {
            System.out.println("> " + line);
            if (line.equals("stop") && !ignoreStop) {
                server.close();
                System.exit(0);
            }
        }
        Thread.sleep(Long.MAX_VALUE);
    }
}
