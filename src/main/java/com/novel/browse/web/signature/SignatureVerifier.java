package com.novel.browse.web.signature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Signature algorithm helper.
 *
 * <p>Canonical string (no trailing newline):
 * <pre>
 * METHOD \n requestURI \n timestamp \n nonce \n bodySha256Hex
 * </pre>
 * Signature = lowercase hex of {@code HMAC-SHA256(secret, canonical)}.
 */
public final class SignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private SignatureVerifier() {
    }

    /** Canonical string that both client and server sign */
    public static String canonical(String method, String requestUri, String timestamp,
                                   String nonce, String bodyHash) {
        return String.join("\n", method.toUpperCase(), requestUri, timestamp, nonce, bodyHash);
    }

    /** Lowercase hex of HMAC-SHA256 */
    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    /** Lowercase hex of SHA-256; {@code new byte[0]} hashes to the well-known empty digest */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Constant-time comparison to avoid timing leaks */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
