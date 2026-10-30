package net.nginxmanager.user;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.OptionalLong;

/** 인증 앱의 6자리 시간 기반 코드(RFC 6238, HMAC-SHA1, 30초). */
final class Totp {

    static final long STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    static String newSecret(SecureRandom random) {
        byte[] key = new byte[20];
        random.nextBytes(key);
        return base32(key);
    }

    static long step(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    static String code(String secret, long step) {
        return code(base32Decode(secret), step, DIGITS);
    }

    static String code(byte[] key, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            int mod = (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", binary % mod);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 앞뒤 한 칸(시계 차이)까지 맞춰 본다. 맞으면 그 칸을 돌려준다. {@code lastStep} 이하의 칸은 이미 쓴 코드라 받지 않는다.
     */
    static OptionalLong verify(String secret, String input, long epochSeconds, Long lastStep) {
        String code = input == null ? "" : input.replaceAll("\\s", "");
        if (!code.matches("[0-9]{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long now = step(epochSeconds);
        for (long s = now - 1; s <= now + 1; s++) {
            if (lastStep != null && s <= lastStep) {
                continue;
            }
            if (constantTimeEquals(code(secret, s), code)) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    /** 인증 앱이 QR 코드로 읽는 주소. */
    static String uri(String issuer, String username, String secret) {
        String label = encode(issuer) + ":" + encode(username);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
                b.getBytes(StandardCharsets.US_ASCII));
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] base32Decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
