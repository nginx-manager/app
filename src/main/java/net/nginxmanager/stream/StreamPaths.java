package net.nginxmanager.stream;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 스트림 설정 파일 위치. 설정 루트 기준 상대 경로이며 nginx.conf의 stream 블록이 이 디렉터리를 include한다. */
public final class StreamPaths {

    public static final String DIR = "stream.d/";
    private static final Pattern FILE = Pattern.compile("stream\\.d/(tcp|udp)-([0-9]{1,5})\\.conf");

    private StreamPaths() {
    }

    public static String of(StreamProtocol protocol, int port) {
        return DIR + protocol.name().toLowerCase(Locale.ROOT) + "-" + port + ".conf";
    }

    /** 스트림 화면이 쓰는 이름 규칙의 파일이면 그 프로토콜·포트, 아니면 null. */
    static Key keyOf(String path) {
        Matcher matcher = FILE.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        int port = Integer.parseInt(matcher.group(2));
        if (port < 1 || port > 65535) {
            return null;
        }
        return new Key(StreamProtocol.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)), port);
    }

    record Key(StreamProtocol protocol, int port) {
    }
}
