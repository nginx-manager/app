package net.nginxmanager.cert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TlsCertificateRepository extends JpaRepository<TlsCertificate, Long> {

    Optional<TlsCertificate> findByName(String name);

    List<TlsCertificate> findAllByOrderByNameAsc();

    List<TlsCertificate> findBySourceOrderByNameAsc(CertificateSource source);
}
