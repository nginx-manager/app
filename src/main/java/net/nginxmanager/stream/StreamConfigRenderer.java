package net.nginxmanager.stream;

import org.springframework.stereotype.Component;

import java.util.Locale;

/** 스트림 폼 값으로 사람이 읽을 수 있는 stream 설정을 만든다. 값 검증은 {@link StreamForm}이 먼저 끝낸다. */
@Component
public class StreamConfigRenderer {

    static final String HEADER = "# nginx-manager: 스트림 화면으로 만든 설정입니다. 원문을 고치면 이 스트림은 원문 편집 전용으로 바뀝니다.\n";

    public String render(StreamForm form) {
        StringBuilder out = new StringBuilder(HEADER);
        String note = form.noteNormalized();
        if (note != null) {
            out.append("# ").append(note).append('\n');
        }
        String upstream = upstreamName(form.protocol(), form.listenPort());
        out.append("upstream ").append(upstream).append(" {\n");
        String directive = form.loadBalancing().directive();
        if (directive != null) {
            out.append("    ").append(directive).append(";\n");
        }
        for (String backend : form.backendList()) {
            out.append("    server ").append(backend).append(";\n");
        }
        out.append("}\n\n")
                .append("server {\n")
                .append("    listen ").append(form.listenPort()).append(form.protocol() == StreamProtocol.UDP ? " udp" : "")
                .append(";\n")
                .append("    proxy_pass ").append(upstream).append(";\n");
        // 접근 제한(025): 사이트와 같은 순서. 막을 주소가 먼저 걸린다
        for (String address : form.denyList()) {
            out.append("    deny ").append(address).append(";\n");
        }
        for (String address : form.allowList()) {
            out.append("    allow ").append(address).append(";\n");
        }
        if (!form.allowList().isEmpty()) {
            out.append("    deny all;\n");
        }
        if (form.proxyProtocol()) {
            out.append("    proxy_protocol on;\n");
        }
        if (form.connectTimeout() != null) {
            out.append("    proxy_connect_timeout ").append(form.connectTimeout()).append("s;\n");
        }
        if (form.idleTimeout() != null) {
            out.append("    proxy_timeout ").append(form.idleTimeout()).append("s;\n");
        }
        out.append("}\n");
        return out.toString();
    }

    static String upstreamName(StreamProtocol protocol, int port) {
        return "stream_" + protocol.name().toLowerCase(Locale.ROOT) + "_" + port;
    }
}
