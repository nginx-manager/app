package net.nginxmanager.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigVersionRepository extends JpaRepository<ConfigVersion, Long> {

    Optional<ConfigVersion> findFirstByOrderByIdDesc();

    /** 되돌리기에 쓸 수 있는 버전 중 이 글자를 담은 파일이 있는지(인증서 디렉터리를 지워도 되는지 볼 때 쓴다). */
    boolean existsByFilesContentContaining(String text);
}
