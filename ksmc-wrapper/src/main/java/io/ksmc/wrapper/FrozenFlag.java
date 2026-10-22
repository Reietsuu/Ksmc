package io.ksmc.wrapper;

import java.io.File;

/**
 * Fichier d'état {@code .ksmc/frozen} écrit par le bridge quand son chien de garde déclare le serveur figé.
 * Le wrapper le supprime au démarrage ; les sondes en tiennent compte.
 */
final class FrozenFlag {
    static final String PATH = ".ksmc/frozen";

    private final File file;
    private final long graceMillis;

    /** @param graceMillis délai après lequel un process figé encore vivant fait échouer {@code /live} */
    FrozenFlag(File serverDir, long graceMillis) {
        this.file = new File(serverDir, PATH);
        this.graceMillis = graceMillis;
    }

    void clear() {
        if (file.exists() && !file.delete()) {
            Log.warn("impossible de supprimer " + file);
        }
    }

    boolean frozen() {
        return file.exists();
    }

    /** Figé depuis plus que le délai de grâce (le process aurait dû s'arrêter). */
    boolean overdue(long now) {
        return file.exists() && now - file.lastModified() > graceMillis;
    }
}
