package net.nginxmanager.stream;

import net.nginxmanager.site.OtherTcpPorts;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 사이트를 저장할 때 TCP 스트림이 쓰는 포트와 겹치는지 본다(FR-102). */
@Component
class StreamTcpPorts implements OtherTcpPorts {

    private final StreamProxyRepository streamRepository;

    StreamTcpPorts(StreamProxyRepository streamRepository) {
        this.streamRepository = streamRepository;
    }

    @Override
    public Optional<String> usedBy(int port) {
        return streamRepository.existsByProtocolAndListenPort(StreamProtocol.TCP, port)
                ? Optional.of(StreamPaths.of(StreamProtocol.TCP, port))
                : Optional.empty();
    }
}
