package io.ksmc.api.module;

/** Version de l'API des modules exposée par ce controller (SemVer : majeure = rupture de compatibilité). */
public final class KsmcApiVersion {

    public static final SemVer CURRENT = new SemVer(1, 0, 0, null);

    private KsmcApiVersion() {
    }
}
