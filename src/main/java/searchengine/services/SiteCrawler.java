package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import searchengine.config.SitesList;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SiteCrawler extends RecursiveAction {
    private final Site site;
    private final String path;
    private final SitesList sitesList;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteRepository siteRepository;
    private final LemmaService lemmaService;
    private final Set<String> visitedUrls;
    private final AtomicBoolean indexingRunning;

    // Конструктор для одной страницы
    public SiteCrawler(Site site, String path, SitesList sitesList,
                       PageRepository pageRepository, LemmaRepository lemmaRepository,
                       IndexRepository indexRepository, SiteRepository siteRepository,
                       LemmaService lemmaService, Set<String> visitedUrls,
                       AtomicBoolean indexingRunning) {
        this.site = site;
        this.path = path;
        this.sitesList = sitesList;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.siteRepository = siteRepository;
        this.lemmaService = lemmaService;
        this.visitedUrls = visitedUrls;
        this.indexingRunning = indexingRunning;
    }

    @Override
    protected void compute() {
        if (!indexingRunning.get()) {
            return;
        }

        try {
            // Индексируем текущую страницу
            indexCurrentPage();

            // Извлекаем ссылки со страницы
            List<String> newLinks = extractLinksFromPage();
            List<SiteCrawler> subtasks = new ArrayList<>();

            for (String link : newLinks) {
                if (shouldProcessLink(link)) {
                    // Создаем новую задачу для каждой ссылки
                    SiteCrawler subtask = new SiteCrawler(
                            site, link, sitesList, pageRepository, lemmaRepository,
                            indexRepository, siteRepository, lemmaService,
                            visitedUrls, indexingRunning
                    );
                    subtasks.add(subtask);
                }
            }

            // Запускаем все дочерние задачи параллельно
            if (!subtasks.isEmpty()) {
                invokeAll(subtasks);
            }

            Thread.sleep(500);

        } catch (Exception e) {
            log.error("Error processing page: {}{}", site.getUrl(), path, e);
        }
    }

    private void indexCurrentPage() {
        try {
            String fullUrl = site.getUrl() + path;

            // Скачиваем страницу
            Document doc = Jsoup.connect(fullUrl)
                    .userAgent(sitesList.getUserAgent())
                    .referrer(sitesList.getReferrer())
                    .timeout(10_000)
                    .get();

            // Проверяем существующую страницу
            Optional<Page> existingPage = pageRepository.findBySiteAndPath(site, path);
            existingPage.ifPresent(this::deletePageData);

            // Сохраняем страницу
            Page page = savePage(path, doc);

            processPageContent(page, doc.html());

            // Обновляем время статуса сайта
            updateSiteStatusTime();

            log.debug("Successfully indexed page: {}{}", site.getUrl(), path);

        } catch (IOException e) {
            log.error("Failed to index page: {}{}", site.getUrl(), path, e);
        } catch (Exception e) {
            log.error("Unexpected error indexing page: {}{}", site.getUrl(), path, e);
        }
    }

    private List<String> extractLinksFromPage() {
        try {
            String fullUrl = site.getUrl() + path;
            Document doc = Jsoup.connect(fullUrl)
                    .userAgent(sitesList.getUserAgent())
                    .referrer(sitesList.getReferrer())
                    .timeout(10_000)
                    .get();

            return doc.select("a[href]").stream()
                    .map(link -> link.attr("href"))
                    .filter(href -> href.startsWith("/")) // Только относительные ссылки
                    .distinct()
                    .toList();

        } catch (Exception e) {
            log.error("Error extracting links from: {}{}", site.getUrl(), path, e);
            return List.of();
        }
    }

    private boolean shouldProcessLink(String link) {
        // Проверяем, не посещали ли уже эту ссылку
        if (visitedUrls.contains(link)) {
            return false;
        }

        // Атомарно добавляем в посещенные
        synchronized (visitedUrls) {
            if (visitedUrls.contains(link)) {
                return false;
            }
            visitedUrls.add(link);
            return true;
        }
    }

    private Page savePage(String path, Document doc) {
        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setCode(doc.connection().response().statusCode());
        page.setContent(doc.html());
        return pageRepository.save(page);
    }

    private void processPageContent(Page page, String html) {
        String text = Jsoup.parse(html).body().text();
        java.util.Map<String, Integer> lemmas = lemmaService.collectLemmas(text);

        java.util.List<searchengine.model.Lemma> lemmaEntities = new ArrayList<>();
        java.util.List<searchengine.model.Index> indexEntities = new ArrayList<>();

        for (java.util.Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            String lemmaText = entry.getKey();
            int rank = entry.getValue();

            searchengine.model.Lemma lemma = lemmaRepository.findBySiteAndLemma(site, lemmaText)
                    .orElseGet(() -> {
                        searchengine.model.Lemma newLemma = new searchengine.model.Lemma();
                        newLemma.setSite(site);
                        newLemma.setLemma(lemmaText);
                        newLemma.setFrequency(0);
                        return newLemma;
                    });

            lemma.setFrequency(lemma.getFrequency() + 1);
            lemmaEntities.add(lemma);

            searchengine.model.Index index = new searchengine.model.Index();
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(rank);
            indexEntities.add(index);
        }

        lemmaRepository.saveAll(lemmaEntities);
        indexRepository.saveAll(indexEntities);
    }

    private void deletePageData(Page page) {
        indexRepository.deleteByPage(page);
        lemmaRepository.decrementFrequencyForPage(page);
        pageRepository.delete(page);
    }

    private void updateSiteStatusTime() {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }
}