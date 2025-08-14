package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import searchengine.config.SiteConfig;
import searchengine.config.SitesList;
import searchengine.model.*;
import searchengine.repositories.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaService lemmaService;
    private final SitesList sitesList;
    private final PlatformTransactionManager transactionManager;

    private ExecutorService executorService;
    private volatile boolean indexingRunning = false;
    private final Map<String, Future<?>> siteTasks = new ConcurrentHashMap<>();
    private final Map<String, Object> siteLocks = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean startIndexing() {
        if (indexingRunning) {
            log.warn("Indexing already running");
            return false;
        }

        indexingRunning = true;
        executorService = Executors.newFixedThreadPool(sitesList.getSites().size());

        sitesList.getSites().forEach(siteConfig -> {
            siteLocks.putIfAbsent(siteConfig.getUrl(), new Object());
            Future<?> future = executorService.submit(() -> indexSite(siteConfig));
            siteTasks.put(siteConfig.getUrl(), future);
        });

        log.info("Indexing started for {} sites", sitesList.getSites().size());
        return true;
    }

    @Override
    public synchronized boolean stopIndexing() {
        if (!indexingRunning) {
            log.warn("No active indexing to stop");
            return false;
        }

        indexingRunning = false;
        executorService.shutdownNow();

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.execute(status -> {
            siteRepository.findAllByStatus(SiteStatus.INDEXING).forEach(site -> {
                site.setStatus(SiteStatus.FAILED);
                site.setLastError("Индексация остановлена пользователем");
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
            });
            return null;
        });

        log.info("Indexing stopped by user");
        return true;
    }

    @Override
    public boolean indexPage(String url) {
        Optional<SiteConfig> siteConfigOpt = sitesList.getSites().stream()
                .filter(s -> url.startsWith(s.getUrl()))
                .findFirst();

        if (siteConfigOpt.isEmpty()) {
            log.error("Page not in config: {}", url);
            return false;
        }

        SiteConfig config = siteConfigOpt.get();
        Site site = siteRepository.findByUrl(config.getUrl())
                .orElseGet(() -> createNewSite(config));

        return executeInTransactionWithRetry(() -> {
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent(sitesList.getUserAgent())
                        .referrer(sitesList.getReferrer())
                        .timeout(10_000)
                        .get();

                String path = url.substring(site.getUrl().length());
                Optional<Page> existingPage = pageRepository.findBySiteAndPath(site, path);

                existingPage.ifPresent(this::deletePageData);

                Page page = savePage(site, path, doc);
                processPageContent(site, page, doc.html());

                return true;
            } catch (IOException e) {
                log.error("Error indexing page: {}", url, e);
                return false;
            }
        }, 3, 1000);
    }

    @Override
    public boolean isIndexingRunning() {
        return indexingRunning;
    }

    protected void indexSite(SiteConfig config) {
        Site site = executeInTransaction(() -> {
            Site s = siteRepository.findByUrl(config.getUrl())
                    .orElseGet(() -> createNewSite(config));
            updateSiteStatus(s, SiteStatus.INDEXING, null);
            clearSiteData(s);
            return s;
        });

        try {
            Queue<String> urlQueue = new ConcurrentLinkedQueue<>();
            Set<String> visitedUrls = ConcurrentHashMap.newKeySet();

            urlQueue.add("/");
            visitedUrls.add("/");

            while (!urlQueue.isEmpty() && indexingRunning) {
                String path = urlQueue.poll();

                boolean success = executeInTransactionWithRetry(() -> {
                    try {
                        indexPage(site, path);
                        return true;
                    } catch (Exception e) {
                        log.error("Error processing page: {}", path, e);
                        return false;
                    }
                }, 3, 1000);

                if (!success) continue;

                try {
                    List<String> newLinks = extractLinksFromPage(site.getUrl() + path);
                    for (String link : newLinks) {
                        if (!visitedUrls.contains(link)) {
                            visitedUrls.add(link);
                            urlQueue.add(link);
                        }
                    }

                    Thread.sleep(500); // Задержка для избежания перегрузки
                } catch (Exception e) {
                    log.error("Error extracting links: {}", path, e);
                }
            }

            if (indexingRunning) {
                executeInTransaction(() -> {
                    updateSiteStatus(site, SiteStatus.INDEXED, null);
                    return null;
                });
                log.info("Site indexed successfully: {}", site.getUrl());
            }
        } catch (Exception e) {
            executeInTransaction(() -> {
                updateSiteStatus(site, SiteStatus.FAILED, "Ошибка индексации: " + e.getMessage());
                return null;
            });
            log.error("Site indexing failed: {}", site.getUrl(), e);
        }
    }

    private List<String> extractLinksFromPage(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent(sitesList.getUserAgent())
                .referrer(sitesList.getReferrer())
                .timeout(10_000)
                .get();

        return doc.select("a[href]").stream()
                .map(link -> link.attr("href"))
                .filter(href -> href.startsWith("/"))
                .distinct()
                .collect(Collectors.toList());
    }

    private Site createNewSite(SiteConfig config) {
        Site site = new Site();
        site.setUrl(config.getUrl());
        site.setName(config.getName());
        site.setStatus(SiteStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        return siteRepository.save(site);
    }

    private void updateSiteStatus(Site site, SiteStatus status, String error) {
        site.setStatus(status);
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    @Transactional
    protected void clearSiteData(Site site) {
        indexRepository.deleteBySite(site);
        lemmaRepository.deleteBySite(site);
        pageRepository.deleteBySite(site);

        siteRepository.flush();
    }

    private void deletePageData(Page page) {
        indexRepository.deleteByPage(page);
        lemmaRepository.decrementFrequencyForPage(page);
        pageRepository.delete(page);
    }

    private Page savePage(Site site, String path, Document doc) {
        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setCode(doc.connection().response().statusCode());
        page.setContent(doc.html());
        return pageRepository.save(page);
    }

    protected void processPageContent(Site site, Page page, String html) {
        String text = Jsoup.parse(html).body().text();
        Map<String, Integer> lemmas = lemmaService.collectLemmas(text);

        List<Lemma> lemmaEntities = new ArrayList<>();
        List<Index> indexEntities = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            String lemmaText = entry.getKey();
            int rank = entry.getValue();

            Lemma lemma = lemmaRepository.findBySiteAndLemma(site, lemmaText)
                    .orElseGet(() -> {
                        Lemma newLemma = new Lemma();
                        newLemma.setSite(site);
                        newLemma.setLemma(lemmaText);
                        newLemma.setFrequency(0);
                        return newLemma;
                    });

            lemma.setFrequency(lemma.getFrequency() + 1);
            lemmaEntities.add(lemma);

            Index index = new Index();
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(rank);
            indexEntities.add(index);
        }

        lemmaRepository.saveAll(lemmaEntities);
        indexRepository.saveAll(indexEntities);
    }

    private void indexPage(Site site, String path) throws IOException {
        Document doc = Jsoup.connect(site.getUrl() + path)
                .userAgent(sitesList.getUserAgent())
                .referrer(sitesList.getReferrer())
                .timeout(10_000)
                .get();

        Optional<Page> existingPage = pageRepository.findBySiteAndPath(site, path);
        existingPage.ifPresent(this::deletePageData);

        Page page = savePage(site, path, doc);
        processPageContent(site, page, doc.html());
    }

    private <T> T executeInTransaction(TransactionalOperation<T> operation) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> operation.execute());
    }

    private <T> T executeInTransactionWithRetry(TransactionalOperation<T> operation, int maxAttempts, long delay) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                return executeInTransaction(operation);
            } catch (PessimisticLockingFailureException | ObjectOptimisticLockingFailureException ex) {
                attempt++;
                log.warn("Lock conflict detected, attempt {}/{}", attempt, maxAttempts);
                if (attempt >= maxAttempts) {
                    throw ex;
                }
                try {
                    Thread.sleep(delay * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        return null; // never reached
    }

    @FunctionalInterface
    private interface TransactionalOperation<T> {
        T execute();
    }
}