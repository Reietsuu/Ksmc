package io.ksmc.wrapper;

import io.ksmc.wire.Wire;
import io.ksmc.wire.manifest.ServiceManifest;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sous-commande {@code run} : lance le serveur Minecraft et le supervise jusqu'à son arrêt. */
final class Runner {
    private final WrapperEnv env;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean deployed = new AtomicBoolean();
    private volatile Process process;
    private Writer stdin;
    private ServiceManifest manifest;

    Runner(WrapperEnv env) {
        this.env = env;
    }

    int run() throws Exception {
        manifest = Wire.fromJson(IoUtil.readString(env.manifestFile()), ServiceManifest.class);
        List<String> command = command(manifest);
        Log.info("lancement : " + command);

        ProcessBuilder pb = new ProcessBuilder(command).directory(env.serverDir);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
        process = pb.start();
        stdin = new OutputStreamWriter(process.getOutputStream(), IoUtil.UTF8);

        HealthServer health = new HealthServer(env.healthPort, new Callable<Boolean>() {
            public Boolean call() {
                return process.isAlive();
            }
        }, manifest.network.port);
        health.start();
        startStdinRelay();
        final CloudLink link = new CloudLink(env, new CloudLink.CommandSink() {
            public void send(String command) throws IOException {
                if (command.startsWith(DEPLOY_COMMAND)) {
                    deployNow(command.substring(DEPLOY_COMMAND.length()).trim());
                } else {
                    sendCommand(command);
                }
            }
        });
        link.startAsync();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                stopGracefully();
            }
        }, "ksmc-stop"));

        int code = process.waitFor();
        Log.info("le serveur s'est arrêté (code " + code + ")");
        deployOnStop();
        link.close();
        health.stop();
        return code;
    }

    static List<String> command(ServiceManifest m) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        cmd.addAll(m.launch.jvmOptions);
        cmd.addAll(m.launch.args);
        return cmd;
    }

    /** Envoie une commande console au serveur. */
    synchronized void sendCommand(String command) throws IOException {
        if (process == null || !process.isAlive()) {
            throw new IOException("server is not running");
        }
        stdin.write(command + "\n");
        stdin.flush();
    }

    /** Relaie l'entrée standard du conteneur ({@code kubectl attach -it}) vers le serveur. */
    private void startStdinRelay() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, IoUtil.UTF8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sendCommand(line);
                    }
                } catch (IOException ignored) {
                    // stdin fermé ou serveur arrêté
                }
            }
        }, "ksmc-stdin");
        t.setDaemon(true);
        t.start();
    }

    /** SIGTERM : commande d'arrêt propre, attente, puis arrêt forcé. */
    void stopGracefully() {
        if (!stopping.compareAndSet(false, true) || process == null || !process.isAlive()) {
            return;
        }
        int timeout = manifest.launch.stopTimeoutSeconds;
        Log.info("arrêt demandé : '" + manifest.launch.stopCommand + "' (délai " + timeout + " s)");
        try {
            sendCommand(manifest.launch.stopCommand);
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                Log.warn("délai dépassé, arrêt forcé");
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception e) {
            process.destroyForcibly();
        }
        deployOnStop();
    }

    /** Commande de contrôle (envoyée par le controller, jamais transmise au serveur) : sauvegarde vers un template. */
    static final String DEPLOY_COMMAND = "ksmc:deploy ";

    /** Sauvegarde immédiate du dossier du service vers un template (en arrière-plan). */
    void deployNow(final String template) throws IOException {
        if (!template.matches("[A-Za-z0-9._/-]+") || template.contains("..")) {
            throw new IOException("template invalide : " + template);
        }
        final String url = "/internal/v1/services/" + env.namespace + "/" + env.service + "/deploy?template=" + template;
        final List<String> excludes = manifest == null || manifest.deployOnStop == null ? new ArrayList<String>()
                : new ArrayList<String>(manifest.deployOnStop.excludes);
        Thread t = new Thread(new Runnable() {
            public void run() {
                deploy(url, excludes);
            }
        }, "ksmc-deploy");
        t.setDaemon(true);
        t.start();
    }

    /** Sauvegarde du dossier du service vers un template, si configurée. */
    void deployOnStop() {
        if (manifest.deployOnStop == null || !deployed.compareAndSet(false, true)) {
            return;
        }
        deploy(manifest.deployOnStop.url, new ArrayList<String>(manifest.deployOnStop.excludes));
    }

    private void deploy(String url, List<String> excludeList) {
        File zip = new File(env.stateDir(), "deploy-" + System.nanoTime() + ".zip");
        try {
            List<String> excludes = new ArrayList<String>(excludeList);
            excludes.add(".ksmc/");
            OutputStream out = new FileOutputStream(zip);
            try {
                IoUtil.zipTree(env.serverDir, out, excludes);
            } finally {
                out.close();
            }
            new InternalClient(env).put(url, zip);
            Log.info("dossier du service sauvegardé vers le template");
        } catch (IOException e) {
            Log.warn("échec de la sauvegarde vers le template : " + e.getMessage());
        } finally {
            zip.delete();
        }
    }
}
