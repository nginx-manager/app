package net.nginxmanager.config;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 원문 편집기에서 새로 만들 수 있는 파일 경로(설정 루트 기준). 폼 없는 모듈(njs 스크립트, mail·stream include,
 * Perl 모듈, XSLT 스타일시트 등)을 쓰기 위한 것이라 루트 밖과 관리도구가 직접 관리하는 디렉터리는 받지 않는다.
 */
public final class NewFilePath {

    /** 경로 조각마다 영문·숫자·. _ - 만. 숨김 파일(.으로 시작)과 ..은 받지 않는다. */
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

    /** 인증서·키, 비밀번호 파일은 관리도구가 쓰는 곳이다. */
    static final List<String> RESERVED_DIRS = List.of("certs/", "htpasswd/");

    private NewFilePath() {
    }

    public static boolean isValid(String path) {
        return path != null && path.length() <= 200 && PATH.matcher(path).matches() && !path.contains("..")
                && RESERVED_DIRS.stream().noneMatch(dir -> path.startsWith(dir) || (path + "/").equals(dir));
    }

    /** 지울 수 없는 파일: nginx가 시작에 꼭 필요로 하는 파일. */
    public static boolean isProtected(String path) {
        return path.equals("nginx.conf") || path.equals("mime.types");
    }
}
