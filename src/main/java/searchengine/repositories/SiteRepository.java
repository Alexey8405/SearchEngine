package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.Site;
import searchengine.model.SiteStatus;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Integer> {
    Optional<Site> findByUrl(String url);
    List<Site> findAllByStatus(SiteStatus status);
}