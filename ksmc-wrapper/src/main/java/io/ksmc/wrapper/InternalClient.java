package io.ksmc.wrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** Client HTTP (Java 8) de l'API interne du controller, authentifié par le jeton projeté du Pod. */
class InternalClient {
    /** Le controller n'est pas prêt à servir la ressource (ex. logiciel en cours de construction). */
    static final class RetryLaterException extends IOException {
        final int seconds;

        RetryLaterException(String message, int seconds) {
            super(message);
            this.seconds = seconds;
        }
    }

    static final class HttpException extends IOException {
        final int status;
        final String body;

        HttpException(int status, String message, String body) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }

    private final WrapperEnv env;

    InternalClient(WrapperEnv env) {
        this.env = env;
    }

    String absolute(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String base = env.controllerUrl.endsWith("/") ? env.controllerUrl.substring(0, env.controllerUrl.length() - 1)
                : env.controllerUrl;
        return base + (url.startsWith("/") ? url : "/" + url);
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        String abs = absolute(url);
        HttpURLConnection c = (HttpURLConnection) new URL(abs).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(300000);
        c.setRequestProperty("User-Agent", "ksmc-wrapper");
        if (abs.startsWith(env.controllerUrl) && env.tokenFile.isFile()) {
            c.setRequestProperty("Authorization", "Bearer " + IoUtil.readString(env.tokenFile).trim());
        }
        return c;
    }

    private static void check(HttpURLConnection c, String url) throws IOException {
        int status = c.getResponseCode();
        if (status == 503) {
            String retry = c.getHeaderField("Retry-After");
            String body = errorBody(c);
            throw new RetryLaterException(body.isEmpty() ? "service unavailable: " + url : body,
                    retry == null ? 5 : Integer.parseInt(retry.trim()));
        }
        if (status / 100 != 2) {
            String body = errorBody(c);
            throw new HttpException(status, "HTTP " + status + " for " + url + (body.isEmpty() ? "" : ": " + body), body);
        }
    }

    private static String errorBody(HttpURLConnection c) {
        try {
            InputStream err = c.getErrorStream();
            return err == null ? "" : IoUtil.readString(err).trim();
        } catch (IOException e) {
            return "";
        }
    }

    String getString(String url) throws IOException {
        HttpURLConnection c = open(url, "GET");
        try {
            check(c, url);
            return IoUtil.readString(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** Télécharge vers {@code target} (via un fichier temporaire) en vérifiant le sha256 s'il est fourni. */
    void download(String url, File target, String sha256) throws IOException {
        HttpURLConnection c = open(url, "GET");
        File tmp = new File(target.getParentFile(), "." + target.getName() + ".part");
        try {
            check(c, url);
            IoUtil.mkdirs(target.getParentFile());
            MessageDigest md = IoUtil.sha256();
            InputStream in = new DigestInputStream(c.getInputStream(), md);
            OutputStream out = new FileOutputStream(tmp);
            try {
                IoUtil.copy(in, out);
            } finally {
                out.close();
                in.close();
            }
            String actual = IoUtil.hex(md.digest());
            if (sha256 != null && !sha256.isEmpty() && !sha256.equalsIgnoreCase(actual)) {
                throw new IOException("sha256 mismatch for " + url + ": expected " + sha256 + ", got " + actual);
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("cannot replace " + target);
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("cannot move " + tmp + " to " + target);
            }
        } finally {
            c.disconnect();
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    /** Envoie un fichier en PUT. */
    void put(String url, File file) throws IOException {
        HttpURLConnection c = open(url, "PUT");
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(file.length());
            c.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream out = c.getOutputStream();
            InputStream in = new FileInputStream(file);
            try {
                IoUtil.copy(in, out);
            } finally {
                in.close();
                out.close();
            }
            check(c, url);
        } finally {
            c.disconnect();
        }
    }
}
