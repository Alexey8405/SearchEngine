package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LemmaRepository extends JpaRepository<Lemma, Integer> {

    // Для SearchServiceImpl
    // Поиск лемм по сайту и набору слов
    @Query("SELECT l FROM Lemma l WHERE l.site = :site AND l.lemma IN :lemmas")
    List<Lemma> findBySiteAndLemmaIn(@Param("site") Site site, @Param("lemmas") Set<String> lemmas);

    // Поиск лемм по набору слов без указания сайта
    @Query("SELECT l FROM Lemma l WHERE l.lemma IN :lemmas")
    List<Lemma> findByLemmaIn(@Param("lemmas") Set<String> lemmas);

    // Для IndexingServiceImpl
    // Уменьшение частоты для всех лемм страницы на единицу (при удалении одной страницы)
    @Modifying
    @Transactional
    @Query("UPDATE Lemma l SET l.frequency = l.frequency - 1 WHERE l IN " +
            "(SELECT i.lemma FROM Index i WHERE i.page = :page)")
    void decrementFrequencyForPage(@Param("page") Page page);

    // Удаление всех лемм сайта при полной переиндексации сайта
    @Modifying
    @Transactional
    @Query("DELETE FROM Lemma l WHERE l.site = :site")
    void deleteBySite(@Param("site") Site site);

    // Общее
    // Поиск леммы по сайту и слову при индексации одной страницы для проверки есть ли уже такое слово в базе,
    // чтобы не создавать дубликат
    @Query("SELECT l FROM Lemma l WHERE l.site = :site AND l.lemma = :lemma")
    Optional<Lemma> findBySiteAndLemma(@Param("site") Site site, @Param("lemma") String lemma);

    // Подсчет количества лемм на сайте
    @Query("SELECT COUNT(l) FROM Lemma l WHERE l.site = :site")
    int countBySite(@Param("site") Site site);

    // Поиск лемм по сайту и набору слов с сортировкой по частоте (для оптимизации поиска)
    @Query("SELECT l FROM Lemma l WHERE l.site = :site AND l.lemma IN :lemmas ORDER BY l.frequency ASC")
    List<Lemma> findBySiteAndLemmaInOrderByFrequencyAsc(@Param("site") Site site, @Param("lemmas") Set<String> lemmas);

    // Подсчет всех лемм (наследуемый метод JpaRepository)
    long count();
}
