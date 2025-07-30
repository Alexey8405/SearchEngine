package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Page;
import searchengine.model.Site;

import java.util.Optional;

public interface PageRepository extends JpaRepository<Page, Integer> {
    // Для SearchServiceImpl
    @Query("SELECT COUNT(p) FROM Page p WHERE p.site = :site")
    int countBySite(@Param("site") Site site);

    long count();

    @Query("SELECT p FROM Page p WHERE p.site = :site AND p.path = :path")
    Optional<Page> findBySiteAndPath(@Param("site") Site site, @Param("path") String path);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Page p WHERE p.site = :site AND p.path = :path")
    boolean existsBySiteAndPath(@Param("site") Site site, @Param("path") String path);

    // Для IndexingServiceImpl
    @Modifying
    @Transactional
    @Query("DELETE FROM Page p WHERE p.site = :site")
    void deleteBySite(@Param("site") Site site);
}