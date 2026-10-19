package io.ksmc.controller.auth;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;

/** Hachage des mots de passe : argon2id (19 Mio, 2 itérations, recommandations OWASP), format PHC. */
public final class PasswordHasher {

    private static final Argon2Function ARGON2 = Argon2Function.getInstance(19 * 1024, 2, 1, 32, Argon2.ID);
    public static final int MIN_LENGTH = 10;

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return Password.hash(password).addRandomSalt(16).with(ARGON2).getResult();
    }

    public static boolean verify(String password, String hash) {
        if (password == null || hash == null) {
            return false;
        }
        try {
            return Password.check(password, hash).with(Argon2Function.getInstanceFromHash(hash));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Lève {@link IllegalArgumentException} si le mot de passe est trop faible. */
    public static void requireStrong(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("le mot de passe doit faire au moins " + MIN_LENGTH + " caractères");
        }
    }
}
