package io.ksmc.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * JWT HS256 minimal (algorithme imposé : un en-tête {@code alg} différent est refusé). Revendications : {@code sub},
 * {@code typ} ({@code access} ou {@code refresh}), {@code iat}, {@code exp}, et des revendications libres.
 */
public final class Jwt {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final SecretKeySpec key;
    private final Clock clock;

    public Jwt(byte[] key, Clock clock) {
        if (key.length < 32) {
            throw new IllegalArgumentException("clé JWT trop courte (32 octets minimum)");
        }
        this.key = new SecretKeySpec(key, "HmacSHA256");
        this.clock = clock;
    }

    public String sign(String subject, String type, Duration ttl, Map<String, Object> extra) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>(extra);
        claims.put("sub", subject);
        claims.put("typ", type);
        claims.put("iat", now);
        claims.put("exp", now + ttl.toSeconds());
        try {
            String payload = ENC.encodeToString(JSON.writeValueAsBytes(claims));
            String signingInput = HEADER + "." + payload;
            return signingInput + "." + ENC.encodeToString(mac(signingInput));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Revendications d'un jeton valide du type attendu (signature, algorithme, expiration), sinon vide. */
    public Optional<Map<String, Object>> verify(String token, String expectedType) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !HEADER.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            byte[] expected = mac(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, DEC.decode(parts[2]))) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = JSON.readValue(DEC.decode(parts[1]), Map.class);
            Object exp = claims.get("exp");
            if (!(exp instanceof Number n) || clock.instant().getEpochSecond() >= n.longValue()
                    || !expectedType.equals(claims.get("typ")) || !(claims.get("sub") instanceof String)) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (IllegalArgumentException | java.io.IOException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
