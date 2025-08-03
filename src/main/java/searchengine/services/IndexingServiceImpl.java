package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SiteConfig;
import searchengine.config.SitesList;
import searchengine.model.*;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.util.SiteIndexing;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.aspectj.weaver.tools.cache.SimpleCacheFactory.path;

@Service
@RequiredArgsConstructor
@Transactional
public class IndexingServiceImpl implements IndexingService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaService lemmaService;
    private final SitesList sitesList;

    private ForkJoinPool forkJoinPool;
    private AtomicBoolean isIndexingRunning = new AtomicBoolean(false);

    @Override
    @Transactional
    public boolean startIndexing() {
        if (isIndexingRunning()) {
            return false;
        }
        for (SiteConfig siteConfig:sitesList.getSites()) {
            Site site = createOrUpdateSite(siteConfig, SiteStatus.INDEXING);
            clearExistingData(site);
            ForkJoinPool forkJoinPool = new ForkJoinPool();
            forkJoinPool.invoke(new SiteIndexing(site, path, isIndexingRunning,
                    pageRepository, lemmaRepository, lemmaService, indexRepository));
        }
        return true;
    }

//    @Override
//    @Transactional
//    public boolean startIndexing() {
//        if (isIndexingRunning()) {
//            return false;
//        }
//
//        isIndexingStopped = false;
//        forkJoinPool = new ForkJoinPool();
//
//        sitesList.getSites().forEach(siteConfig -> {
//            forkJoinPool.execute(() -> {
//                Site site = createOrUpdateSite(siteConfig, SiteStatus.INDEXING);
//                try {
//                    clearExistingData(site);
//                    crawlSite(site, "/");
//                    updateSiteStatus(site, SiteStatus.INDEXED, null);
//                } catch (Exception e) {
//                    updateSiteStatus(site, SiteStatus.FAILED, e.getMessage());
//                }
//            });
//        });
//
//        return true;
//    }

    @Override
    @Transactional
    public boolean stopIndexing() {
        if (!isIndexingRunning()) {
            return false;
        }

        isIndexingRunning.set(false);
        forkJoinPool.shutdownNow();

        siteRepository.findAllByStatus(SiteStatus.INDEXING).forEach(site -> {
            updateSiteStatus(site, SiteStatus.FAILED, "Индексация остановлена пользователем");
        });

        return true;
    }

    @Override
    @Transactional
    public boolean indexPage(String url) {
        Optional<SiteConfig> siteConfigOpt = findSiteConfigByUrl(url);
        if (siteConfigOpt.isEmpty()) {
            return false;
        }

        SiteConfig config = siteConfigOpt.get();
        Site site = siteRepository.findByUrl(config.getUrl())
                .orElseGet(() -> createNewSite(config));

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(sitesList.getUserAgent())
                    .referrer(sitesList.getReferrer())
                    .timeout(10_000)
                    .get();

            String path = url.substring(site.getUrl().length());
            pageRepository.findBySiteAndPath(site, path).ifPresent(page -> {
                lemmaRepository.decrementFrequencyForPage(page);
                indexRepository.deleteByPage(page);
                pageRepository.delete(page);
            });

            Page page = savePage(site, path, doc);
            processPageContent(site, page, doc.html());
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Ошибка индексации страницы: " + url, e);
        }
    }

    @Override
    public boolean isIndexingRunning() {
        return isIndexingRunning.get();
//        forkJoinPool != null && !forkJoinPool.isTerminated();
    }

    private Site createOrUpdateSite(SiteConfig config, SiteStatus status) {
        Site site = siteRepository.findByUrl(config.getUrl())
                .orElse(new Site());
        site.setUrl(config.getUrl());
        site.setName(config.getName());
        site.setStatus(status);
        site.setStatusTime(LocalDateTime.now());
        return siteRepository.save(site);
    }

    private void clearExistingData(Site site) {
        indexRepository.deleteBySite(site);
        lemmaRepository.deleteBySite(site);
        pageRepository.deleteBySite(site);
    }

    private void deletePageData(Page page) {
        lemmaRepository.decrementFrequencyForPage(page);
        indexRepository.deleteByPage(page);
        pageRepository.delete(page);
    }

    private void updateSiteStatus(Site site, SiteStatus status, String error) {
        site.setStatus(status);
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    private void crawlSite(Site site, String currentPath) {
        if (!isIndexingRunning.get()) {
            throw new RuntimeException("Индексация остановлена пользователем");
        }

        try {
            Thread.sleep(500); // Задержка между запросами

            Document doc = Jsoup.connect(site.getUrl() + currentPath)
                    .userAgent(sitesList.getUserAgent())
                    .referrer(sitesList.getReferrer())
                    .timeout(10_000)
                    .get();

            Page page = savePage(site, currentPath, doc);
            processPageContent(site, page, doc.html());

            Elements links = doc.select("a[href]");
            List<String> newPaths = links.stream()
                    .map(link -> link.attr("href"))
                    .filter(href -> href.startsWith("/"))
                    .distinct()
                    .filter(path -> !pageRepository.existsBySiteAndPath(site, path))
                    .collect(Collectors.toList());

            newPaths.forEach(path -> crawlSite(site, path));

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при загрузке страницы: " + currentPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Индексация прервана");
        }
    }

    private Page savePage(Site site, String path, Document doc) {
        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setCode(doc.connection().response().statusCode());
        page.setContent(doc.html());
        return pageRepository.save(page);
    }

    private void processPageContent(Site site, Page page, String html) {
        String text = Jsoup.parse(html).body().text();
        Map<String, Integer> lemmas = lemmaService.collectLemmas(text);

        lemmas.forEach((lemmaText, rank) -> {
            Lemma lemma = lemmaRepository.findBySiteAndLemma(site, lemmaText)
                    .orElseGet(() -> {
                        Lemma newLemma = new Lemma();
                        newLemma.setSite(site);
                        newLemma.setLemma(lemmaText);
                        newLemma.setFrequency(0);
                        return newLemma;
                    });

            lemma.setFrequency(lemma.getFrequency() + 1);
            lemmaRepository.save(lemma);

            Index index = new Index();
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(rank);
            indexRepository.save(index);
        });
    }

    private Optional<SiteConfig> findSiteConfigByUrl(String url) {
        return sitesList.getSites().stream()
                .filter(s -> url.startsWith(s.getUrl()))
                .findFirst();
    }

    private Site createNewSite(SiteConfig config) {
        Site site = new Site();
        site.setUrl(config.getUrl());
        site.setName(config.getName());
        site.setStatus(SiteStatus.INDEXED);
        site.setStatusTime(LocalDateTime.now());
        return siteRepository.save(site);
    }
}


//import lombok.RequiredArgsConstructor;
//import org.jsoup.Jsoup;
//import org.jsoup.nodes.Document;
//import org.jsoup.select.Elements;
//import org.springframework.stereotype.Service;
//import searchengine.config.SiteConfig;
//import searchengine.config.SitesList;
//import searchengine.model.Page;
//import searchengine.model.Site;
//import searchengine.model.SiteStatus;
//import searchengine.repositories.IndexRepository;
//import searchengine.repositories.LemmaRepository;
//import searchengine.repositories.PageRepository;
//import searchengine.repositories.SiteRepository;
//
//import java.io.IOException;
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.Optional;
//import java.util.concurrent.ForkJoinPool;
//import java.util.stream.Collectors;
//
//@Service
//@RequiredArgsConstructor
//public class IndexingServiceImpl implements IndexingService {
//    private final SiteRepository siteRepository;
//    private final PageRepository pageRepository;
//    private final SitesList sitesList;
//    private final LemmaService lemmaService;
//    private final LemmaRepository lemmaRepository;
//    private final IndexRepository indexRepository;
//
//    private ForkJoinPool forkJoinPool;
//    private volatile boolean isIndexingStopped = false;
//
//    @Override
//    public boolean startIndexing() {
//        if (isIndexingRunning()) {
//            return false;
//        }
//
//        isIndexingStopped = false;
//        forkJoinPool = new ForkJoinPool();
//
//        sitesList.getSites().forEach(siteConfig -> {
//            forkJoinPool.execute(() -> indexSite(siteConfig));
//        });
//
//        return true;
//    }
//
//    @Override
//    public boolean stopIndexing() {
//        if (!isIndexingRunning()) {
//            return false;
//        }
//
//        isIndexingStopped = true;
//        forkJoinPool.shutdownNow();
//
//        siteRepository.findAllByStatus(SiteStatus.INDEXING).forEach(site -> {
//            site.setStatus(SiteStatus.FAILED);
//            site.setLastError("Индексация остановлена пользователем");
//            site.setStatusTime(LocalDateTime.now());
//            siteRepository.save(site);
//        });
//
//        return true;
//    }
//
//
//    @Override
//    public boolean indexPage(String url) {
//        // Проверяем, принадлежит ли URL сайтам из конфигурации
//        Optional<SiteConfig> siteConfigOpt = sitesList.getSites().stream()
//                .filter(s -> url.startsWith(s.getUrl()))
//                .findFirst();
//
//        if (siteConfigOpt.isEmpty()) {
//            return false;
//        }
//
//        SiteConfig config = siteConfigOpt.get();
//        Site site = siteRepository.findByUrl(config.getUrl())
//                .orElseGet(() -> {
//                    Site newSite = new Site();
//                    newSite.setUrl(config.getUrl());
//                    newSite.setName(config.getName());
//                    newSite.setStatus(SiteStatus.INDEXED);
//                    return siteRepository.save(newSite);
//                });
//
//        try {
//            Document doc = Jsoup.connect(url)
//                    .userAgent(sitesList.getUserAgent())
//                    .get();
//
//            indexPage(site, url.substring(site.getUrl().length()), doc.html());
//            return true;
//        } catch (IOException e) {
//            throw new RuntimeException("Ошибка индексации страницы", e);
//        }
//    }
//
//    @Override
//    public boolean isIndexingRunning() {
//        return forkJoinPool != null && !forkJoinPool.isTerminated();
//    }
//
//    private void indexSite(SiteConfig siteConfig) {
//        Site site = new Site();
//        site.setUrl(siteConfig.getUrl());
//        site.setName(siteConfig.getName());
//        site.setStatus(SiteStatus.INDEXING);
//        site.setStatusTime(LocalDateTime.now());
//        site = siteRepository.save(site);
//
//        try {
//            crawlSite(site, "/");
//            site.setStatus(SiteStatus.INDEXED);
//        } catch (Exception e) {
//            site.setStatus(SiteStatus.FAILED);
//            site.setLastError(e.getMessage());
//        } finally {
//            site.setStatusTime(LocalDateTime.now());
//            siteRepository.save(site);
//        }
//    }
//
//    private void crawlSite(Site site, String currentPath) {
//        if (isIndexingStopped) {
//            throw new RuntimeException("Индексация остановлена пользователем");
//        }
//
//        try {
//            Thread.sleep(500);
//
//            Document doc = Jsoup.connect(site.getUrl() + currentPath)
//                    .userAgent(sitesList.getUserAgent())
//                    .referrer(sitesList.getReferrer())
//                    .timeout(10_000)
//                    .get();
//
//            Page page = new Page();
//            page.setSite(site);
//            page.setPath(currentPath);
//            page.setCode(doc.connection().response().statusCode());
//            page.setContent(doc.html());
//            pageRepository.save(page);
//
//            Elements links = doc.select("a[href]");
//            List<String> newPaths = links.stream()
//                    .map(link -> link.attr("href"))
//                    .filter(href -> href.startsWith("/"))
//                    .distinct()
//                    .filter(path -> !pageRepository.existsBySiteAndPath(site, path))
//                    .collect(Collectors.toList());
//
//            newPaths.forEach(path -> crawlSite(site, path));
//
//        } catch (IOException e) {
//            throw new RuntimeException("Ошибка при загрузке страницы: " + currentPath, e);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            throw new RuntimeException("Индексация прервана");
//        }
//    }
//}