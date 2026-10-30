package net.nginxmanager.cert;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 되돌릴 버전이 정리된 옛 인증서 디렉터리를 가리키면 같은 인증서의 지금 파일로 바꾼다(012).
 * 발급된 인증서는 한 번만 읽는다.
 */
@Component
public class CertificatePathRepair {

    private final TlsCertificateRepository certificateRepository;
    private final CertificateFiles certificateFiles;

    public CertificatePathRepair(TlsCertificateRepository certificateRepository, CertificateFiles certificateFiles) {
        this.certificateRepository = certificateRepository;
        this.certificateFiles = certificateFiles;
    }

    /** @param files 경로와 내용(지울 파일은 null). 순서를 지켜 돌려준다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, String> repair(Map<String, String> files) {
        Map<String, CertificatePaths> current = new HashMap<>();
        for (TlsCertificate cert : certificateRepository.findAllByOrderByNameAsc()) {
            CertificatePaths paths = cert.paths();
            // 같은 디렉터리 이름을 쓰는 인증서가 여럿이면 파일이 실제로 있는 것
            if (paths != null && Files.exists(Path.of(paths.certPath()))) {
                current.putIfAbsent(CertificateFiles.directoryName(cert.getName()), paths);
            }
        }
        Map<String, String> repaired = new LinkedHashMap<>();
        files.forEach((path, content) -> repaired.put(path,
                content == null || current.isEmpty() ? content : certificateFiles.repointMissing(content, current::get)));
        return repaired;
    }
}
