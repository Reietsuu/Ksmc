package io.ksmc.wrapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Utilitaires fichiers/ZIP compatibles Java 8. */
final class IoUtil {
    static final Charset UTF8 = Charset.forName("UTF-8");

    private IoUtil() {
    }

    static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    static String readString(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readString(in);
        } finally {
            in.close();
        }
    }

    static String readString(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return new String(bos.toByteArray(), UTF8);
    }

    static void writeString(File f, String content) throws IOException {
        mkdirs(f.getParentFile());
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(UTF8));
        } finally {
            out.close();
        }
    }

    static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Fichier {@code name} sous {@code dir}, en refusant de sortir du dossier. */
    static File safeChild(File dir, String name) throws IOException {
        File target = new File(dir, name).getCanonicalFile();
        String root = dir.getCanonicalPath() + File.separator;
        if (!target.getPath().startsWith(root)) {
            throw new IOException("path outside of " + dir + ": " + name);
        }
        return target;
    }

    /** Extrait un ZIP dans un dossier (les fichiers existants sont remplacés). Renvoie le nombre de fichiers. */
    static int unzip(InputStream raw, File dest) throws IOException {
        ZipInputStream zip = new ZipInputStream(raw);
        int count = 0;
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            File target = safeChild(dest, e.getName());
            if (e.isDirectory()) {
                mkdirs(target);
                continue;
            }
            mkdirs(target.getParentFile());
            OutputStream out = new FileOutputStream(target);
            try {
                copy(zip, out);
            } finally {
                out.close();
            }
            count++;
        }
        return count;
    }

    /** Copie récursive d'un dossier dans un autre (remplacement des fichiers existants). */
    static int copyTree(File src, File dest) throws IOException {
        int count = 0;
        File[] children = src.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            File target = new File(dest, child.getName());
            if (child.isDirectory()) {
                mkdirs(target);
                count += copyTree(child, target);
            } else {
                mkdirs(dest);
                InputStream in = new FileInputStream(child);
                try {
                    OutputStream out = new FileOutputStream(target);
                    try {
                        copy(in, out);
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                count++;
            }
        }
        return count;
    }

    /** Zippe un dossier en excluant les chemins relatifs correspondant aux motifs glob ({@code *}, {@code **}, {@code ?}). */
    static void zipTree(File dir, OutputStream out, List<String> excludes) throws IOException {
        List<Pattern> patterns = new ArrayList<Pattern>();
        for (String glob : excludes) {
            patterns.add(globToRegex(glob));
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        zipInto(dir, "", zip, patterns);
        zip.finish();
    }

    private static void zipInto(File dir, String prefix, ZipOutputStream zip, List<Pattern> excludes) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children);
        for (File child : children) {
            String rel = prefix + child.getName();
            if (matches(rel, excludes) || (child.isDirectory() && matches(rel + "/", excludes))) {
                continue;
            }
            if (child.isDirectory()) {
                zipInto(child, rel + "/", zip, excludes);
            } else {
                zip.putNextEntry(new ZipEntry(rel));
                InputStream in = new FileInputStream(child);
                try {
                    copy(in, zip);
                } finally {
                    in.close();
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean matches(String path, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        // Un motif de dossier ("logs/") exclut tout son contenu.
        String regex = sb.toString();
        if (glob.endsWith("/")) {
            regex += ".*";
        }
        return Pattern.compile(regex);
    }
}
