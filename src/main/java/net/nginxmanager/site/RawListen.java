package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 원문 설정의 listen 지시어에서 포트를 읽는다. 해석하지 않는 형식(unix 소켓 등)은 건너뛴다. */
final class RawListen {

    private static final Pattern LISTEN = Pattern.compile("(?<![\\w$])listen\\s+([^\\s;]+)([^;]*)");
    private static final Pattern COMMENT = Pattern.compile("#[^\\n]*");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");

    private RawListen() {
    }

    /** 첫 번째 HTTP(ssl·quic가 아닌) listen 포트. 없으면 첫 번째 listen 포트. */
    static Optional<Integer> httpPort(String content) {
        List<Integer> plain = new ArrayList<>();
        List<Integer> any = new ArrayList<>();
        Matcher m = LISTEN.matcher(COMMENT.matcher(content).replaceAll(""));
        while (m.find()) {
            Optional<Integer> port = portOf(m.group(1));
            if (port.isEmpty()) {
                continue;
            }
            any.add(port.get());
            String params = " " + m.group(2) + " ";
            if (!params.contains(" ssl ") && !params.contains(" quic ")) {
                plain.add(port.get());
            }
        }
        return plain.stream().findFirst().or(() -> any.stream().findFirst());
    }

    private static Optional<Integer> portOf(String address) {
        if (address.startsWith("unix:")) {
            return Optional.empty();
        }
        String port = address;
        int bracket = address.lastIndexOf(']');
        int colon = address.lastIndexOf(':');
        if (colon > bracket) {
            port = address.substring(colon + 1);
        } else if (bracket >= 0 || address.contains(".") || address.equals("*")) {
            // 주소만 적으면 80이다
            return Optional.of(80);
        }
        if (!PORT.matcher(port).matches()) {
            return Optional.empty();
        }
        int value = Integer.parseInt(port);
        return value >= 1 && value <= 65535 ? Optional.of(value) : Optional.empty();
    }
}
