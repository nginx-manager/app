package net.nginxmanager.stream;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StreamProxyRepository extends JpaRepository<StreamProxy, Long> {

    Optional<StreamProxy> findByConfigPath(String configPath);

    boolean existsByProtocolAndListenPort(StreamProtocol protocol, int listenPort);

    List<StreamProxy> findAllByOrderByListenPortAscProtocolAsc();

    void deleteByConfigPath(String configPath);
}
