package net.nginxmanager.stream;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import net.nginxmanager.site.SiteOptions;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스트림 프록시 폼. 값은 nginx 설정에 그대로 들어가므로 형식을 검사한 값만 받는다.
 *
 * @param backends 백엔드 주소(호스트:포트). 공백·쉼표·줄바꿈으로 여러 개
 * @param connectTimeout 백엔드 연결 타임아웃(초). null이면 nginx 기본값(60초)
 * @param idleTimeout 양쪽 모두 아무것도 보내지 않을 때 연결을 끊기까지(초). null이면 nginx 기본값(10분)
 * @param version 수정할 때 읽어 간 버전. 동시 편집 충돌을 막는다
 * @param allowFrom 이 주소에서 오는 연결만 받는다(025, IP·CIDR). 비우면 제한 없음
 * @param denyFrom 이 주소에서 오는 연결을 막는다(025)
 * @param proxyProtocol 백엔드에 PROXY protocol로 클라이언트 주소를 넘긴다(025, TCP만)
 */
public record StreamForm(
        @NotNull
        StreamProtocol protocol,
        @Min(value = 1, message = "{msg.validation.streamPort}") @Max(value = 65535, message = "{msg.validation.streamPort}")
        int listenPort,
        String backends,
        StreamBalancing loadBalancing,
        @Min(value = 1, message = "{msg.validation.timeout}") @Max(value = 3600, message = "{msg.validation.timeout}")
        Integer connectTimeout,
        @Min(value = 1, message = "{msg.validation.streamIdleTimeout}") @Max(value = 86400, message = "{msg.validation.streamIdleTimeout}")
        Integer idleTimeout,
        String note,
        Long version,
        String allowFrom,
        String denyFrom,
        Boolean proxyProtocol) {

    static final int MAX_BACKENDS = 32;

    /** HTTP(80, 443)와 관리용 포트(에이전트 7070, 상태 확인 8081, 관리 화면 9000). UDP는 443(HTTP/3)만 겹친다. */
    static final List<Integer> RESERVED_TCP_PORTS = List.of(80, 443, 7070, 8081, 9000);
    static final List<Integer> RESERVED_UDP_PORTS = List.of(443);

    private static final Pattern BACKEND = Pattern.compile("(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):([0-9]{1,5})");

    static final int MAX_ADDRESSES = 200;

    public StreamForm {
        loadBalancing = loadBalancing == null ? StreamBalancing.ROUND_ROBIN : loadBalancing;
        proxyProtocol = proxyProtocol != null && proxyProtocol && protocol != StreamProtocol.UDP;
    }

    /** 접근 제한·PROXY protocol 없이 쓰던 폼. */
    public StreamForm(StreamProtocol protocol, int listenPort, String backends, StreamBalancing loadBalancing,
                      Integer connectTimeout, Integer idleTimeout, String note, Long version) {
        this(protocol, listenPort, backends, loadBalancing, connectTimeout, idleTimeout, note, version, null, null,
                false);
    }

    public List<String> allowList() {
        return SiteOptions.addresses(allowFrom);
    }

    public List<String> denyList() {
        return SiteOptions.addresses(denyFrom);
    }

    @AssertTrue(message = "{msg.validation.addresses}")
    public boolean isAllowFromValid() {
        return validAddresses(allowList());
    }

    @AssertTrue(message = "{msg.validation.addresses}")
    public boolean isDenyFromValid() {
        return validAddresses(denyList());
    }

    private static boolean validAddresses(List<String> list) {
        return list.size() <= MAX_ADDRESSES && list.stream().allMatch(SiteOptions::isAddress);
    }

    public static StreamForm empty() {
        return new StreamForm(StreamProtocol.TCP, 0, "", StreamBalancing.ROUND_ROBIN, null, null, "", null);
    }

    public List<String> backendList() {
        if (backends == null || backends.isBlank()) {
            return List.of();
        }
        return Arrays.stream(backends.trim().split("[\\s,]+")).distinct().toList();
    }

    String noteNormalized() {
        if (note == null || note.isBlank()) {
            return null;
        }
        String single = note.strip().replaceAll("\\s+", " ");
        return single.length() > 200 ? single.substring(0, 200) : single;
    }

    @AssertTrue(message = "{msg.validation.streamBackends}")
    public boolean isBackendsValid() {
        List<String> list = backendList();
        if (list.isEmpty() || list.size() > MAX_BACKENDS) {
            return false;
        }
        for (String backend : list) {
            Matcher matcher = BACKEND.matcher(backend);
            if (!matcher.matches()) {
                return false;
            }
            int port = Integer.parseInt(matcher.group(3));
            if (port < 1 || port > 65535) {
                return false;
            }
        }
        return true;
    }

    /** nginx 컨테이너의 관리용 포트나 관리 화면으로 넘기면 관리 기능이 밖으로 공개된다. */
    @AssertTrue(message = "{msg.validation.proxyNotManager}")
    public boolean isBackendsNotManager() {
        if (!isBackendsValid()) {
            return true;
        }
        for (String backend : backendList()) {
            Matcher matcher = BACKEND.matcher(backend);
            matcher.matches();
            String host = matcher.group(1);
            int port = Integer.parseInt(matcher.group(3));
            boolean local = host.equals("localhost") || host.startsWith("127.") || host.equals("0.0.0.0")
                    || host.equals("[::1]");
            if (((local || host.equals("nginx")) && (port == 7070 || port == 8081))
                    || ((local || host.equals("manager")) && port == 9000)) {
                return false;
            }
        }
        return true;
    }

    @AssertTrue(message = "{msg.validation.streamReservedPort}")
    public boolean isListenPortFree() {
        if (protocol == null) {
            return true;
        }
        return !(protocol == StreamProtocol.TCP ? RESERVED_TCP_PORTS : RESERVED_UDP_PORTS).contains(listenPort);
    }
}
