package net.nginxmanager.site;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {

    boolean existsByPrimaryDomain(String primaryDomain);

    Optional<Site> findByPrimaryDomain(String primaryDomain);

    List<Site> findByCertificateId(Long certificateId);

    List<Site> findByCertificateIdIsNotNull();

    Optional<Site> findByConfigFileId(Long configFileId);
}
