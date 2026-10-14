package net.nginxmanager.site;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 사이트 비밀번호 보호(auth_basic)용 htpasswd 파일. 해시는 nginx가 crypt(3) 없이 직접 읽는 {SSHA}(솔트 붙인 SHA-1)를
 * 쓴다. 요청마다 검사하므로 bcrypt처럼 느린 해시는 쓰지 않는다.
 */
public final class Htpasswd {

    /** 설정 루트 기준 디렉터리. 원문 편집기로 새 파일을 만들 수 없는 곳이다. */
    public static final String DIR = "htpasswd/";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Htpasswd() {
    }

    public static String path(String primaryDomain) {
        return DIR + primaryDomain;
    }

    /**
     * 폼에 적은 사용자로 새 htpasswd 내용을 만든다. 비밀번호를 적지 않은 사용자는 지금 해시를 그대로 쓴다.
     *
     * @return 새 내용. 사용자가 없으면 null
     * @throws IllegalArgumentException 비밀번호도 없고 저장된 해시도 없는 사용자가 있으면(이름은 메시지로)
     */
    static String merge(String current, Map<String, String> entries) {
        Map<String, String> existing = parse(current);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String name = entry.getKey();
            String password = entry.getValue();
            if (password != null) {
                result.put(name, hash(password));
            } else if (existing.containsKey(name)) {
                result.put(name, existing.get(name));
            } else {
                throw new IllegalArgumentException(name);
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        return result.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining("\n", "", "\n"));
    }

    static Map<String, String> parse(String content) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (content == null) {
            return entries;
        }
        for (String line : content.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                entries.put(line.substring(0, colon), line.substring(colon + 1));
            }
        }
        return entries;
    }

    static String hash(String password) {
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        return hash(password, salt);
    }

    static String hash(String password, byte[] salt) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            sha1.update(salt);
            byte[] digest = sha1.digest();
            byte[] combined = new byte[digest.length + salt.length];
            System.arraycopy(digest, 0, combined, 0, digest.length);
            System.arraycopy(salt, 0, combined, digest.length, salt.length);
            return "{SSHA}" + Base64.getEncoder().encodeToString(combined);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 비밀번호가 해시와 맞는지(테스트·확인용). */
    static boolean matches(String password, String hashed) {
        byte[] combined = Base64.getDecoder().decode(hashed.substring("{SSHA}".length()));
        byte[] salt = new byte[combined.length - 20];
        System.arraycopy(combined, 20, salt, 0, salt.length);
        return hash(password, salt).equals(hashed);
    }
}
