package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import java.util.List;
import java.util.Optional;

public interface IndexRepository extends JpaRepository<Index, Integer> {
    // Для SearchServiceImpl
    // Для поиска по лемме и сайту
    @Query("SELECT i.page FROM Index i WHERE i.lemma = :lemma AND i.page.site = :site")
    List<Page> findPagesByLemmaAndSite(@Param("lemma") Lemma lemma, @Param("site") Site site);

    // Для поиска по лемме (без указания сайта)
    @Query("SELECT i.page FROM Index i WHERE i.lemma = :lemma")
    List<Page> findPagesByLemma(@Param("lemma") Lemma lemma);

    @Query("SELECT i.rank FROM Index i WHERE i.page = :page AND i.lemma = :lemma")
    Optional<Float> findRankByPageAndLemma(
            @Param("page") Page page,
            @Param("lemma") Lemma lemma
    );

    // Для IndexingServiceImpl
    @Modifying
    @Query("DELETE FROM Index i WHERE i.page = :page")
    void deleteByPage(@Param("page") Page page);

    @Modifying
    @Query("DELETE FROM Index i WHERE i.page IN (SELECT p FROM Page p WHERE p.site = :site)")
    void deleteBySite(@Param("site") Site site);
}
