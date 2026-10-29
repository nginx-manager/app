package net.nginxmanager.site;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * @param roots 설정 루트 말고 가져올 파일을 찾아도 되는 디렉터리(관리도구 컨테이너 안의 절대 경로).
 *              관리도구 컨테이너에 마운트한 호스트 디렉터리를 적는다. 그 밖의 경로는 패턴이 가리켜도 읽지 않는다
 */
@ConfigurationProperties("nginx-manager.import")
public record ImportProperties(List<Path> roots) {

    public ImportProperties {
        roots = roots == null ? List.of() : roots.stream().filter(p -> !p.toString().isBlank())
                .map(p -> p.toAbsolutePath().normalize()).toList();
    }
}
