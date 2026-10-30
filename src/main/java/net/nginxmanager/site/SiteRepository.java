package net.nginxmanager.site;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {

    long countByEnabledTrue();

    boolean existsByPrimaryDomain(String primaryDomain);

    Optional<Site> findByPrimaryDomain(String primaryDomain);

    List<Site> findByImportPath(String importPath);

    List<Site> findByCertificateId(Long certificateId);

    List<Site> findByCertificateIdIsNotNull();

    Optional<Site> findByConfigFileId(Long configFileId);

    List<Site> findByOwnerId(Long ownerId);

    long countByOwnerId(Long ownerId);

    long countByOwnerIdAndEnabledTrue(Long ownerId);
}
