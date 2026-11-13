package net.nginxmanager.site;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DirectiveRequestRepository extends JpaRepository<DirectiveRequest, Long> {

    Optional<DirectiveRequest> findBySiteId(Long siteId);

    List<DirectiveRequest> findAllByOrderByIdAsc();
}
