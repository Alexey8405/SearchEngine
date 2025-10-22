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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final PlatformTransactionManager transactionManager; // Для управления транзакциями

//    private ExecutorService executorService; // Для управления пулом потоков, не final, устанавливается во время работы
    private ForkJoinPool forkJoinPool;
    private final AtomicBoolean indexingRunning = new AtomicBoolean(false);; // Флаг (запущена ли индексация?) + атомик для потокобезопасности
    private final Map<String, Future<?>> siteTasks = new ConcurrentHashMap<>(); // Мапа для отслеживания задач по сайтам (Ключ: URL сайта, Значение: Future<?> - объект представляющий задачу)
    private final Map<String, Object> siteLocks = new ConcurrentHashMap<>(); // Мапа для блокировок по сайтам (Ключ: URL сайта, Значение: Object - объект для синхронизации)

//    // synchronized - только один поток может выполнять этот метод одновременно
//    @Override
//    public synchronized boolean startIndexing() {
//        // Используем compareAndSet для атомарной проверки и установки
//        if (!indexingRunning.compareAndSet(false, true)) {
//            log.warn("Indexing already running");
//            return false;
//        }
//        // Создаем пул потоков с количеством потоков = количеству сайтов
//        executorService = Executors.newFixedThreadPool(sitesList.getSites().size());
//
//        sitesList.getSites().forEach(siteConfig -> {
//            // Добавляем объект для синхронизации в карту блокировок (если ключа еще нет - putIfAbsent)
//            siteLocks.putIfAbsent(siteConfig.getUrl(), new Object());
//            // Запускаем задачу индексации сайта в отдельном потоке
//            Future<?> future = executorService.submit(() -> indexSite(siteConfig));
//            // Сохраняем Future задачи в карту задач
//            siteTasks.put(siteConfig.getUrl(), future);
//        });
//
//        log.info("Indexing started for {} sites", sitesList.getSites().size());
//        return true;
//    }
//
//    @Override
//    public synchronized boolean stopIndexing() {
//        // ИСПРАВЛЕНО: Используем getAndSet для атомарной проверки и установки
//        if (!indexingRunning.getAndSet(false)) {
//            log.warn("No active indexing to stop");
//            return false;
//        }
//
//        // Остановка всех потоков
//        if (executorService != null) {
//            executorService.shutdownNow();
//        }
//
//        // Создание TransactionTemplate для выполнения кода в транзакции
//        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
//        transactionTemplate.execute(status -> {
//            siteRepository.findAllByStatus(SiteStatus.INDEXING).forEach(site -> {
//                site.setStatus(SiteStatus.FAILED);
//                site.setLastError("Индексация остановлена пользователем");
//                site.setStatusTime(LocalDateTime.now());
//                siteRepository.save(site);
//            });
//            return null;
//        });
//
//        log.info("Indexing stopped by user");
//        return true;
//    }

    @Override
    public synchronized boolean startIndexing() {
        if (!indexingRunning.compareAndSet(false, true)) {
            log.warn("Indexing already running");
            return false;
        }

        // Создаем ForkJoinPool
        forkJoinPool = new ForkJoinPool();

        sitesList.getSites().forEach(siteConfig -> {
            siteLocks.putIfAbsent(siteConfig.getUrl(), new Object());

            // Запускаем индексацию для каждого сайта
            startSiteIndexing(siteConfig);
        });

        log.info("Indexing started for {} sites using Fork-Join", sitesList.getSites().size());
        return true;
    }

    private void startSiteIndexing(SiteConfig config) {
        try {
            // Подготавливаем сайт (старый метод indexSite без логики обхода страниц)
            Site site = executeInTransaction(() -> {
                Site s = siteRepository.findByUrl(config.getUrl())
                        .orElseGet(() -> createNewSite(config));
                updateSiteStatus(s, searchengine.model.SiteStatus.INDEXING, null);
                clearSiteData(s);
                return s;
            });

            // Создаем множество для отслеживания посещенных URL
            Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
            visitedUrls.add("/"); // Начинаем с корневой страницы

            // Создаем и запускаем задачу для корневой страницы
            SiteCrawler rootTask = new SiteCrawler(
                    site, "/", sitesList, pageRepository, lemmaRepository,
                    indexRepository, siteRepository, lemmaService,
                    visitedUrls, indexingRunning
            );

            Future<?> future = forkJoinPool.submit(rootTask);
            siteTasks.put(config.getUrl(), future);

            log.info("Started indexing site: {}", config.getUrl());

        } catch (Exception e) {
            log.error("Failed to start indexing for site: {}", config.getUrl(), e);
            // Обновляем статус сайта на FAILED
            executeInTransaction(() -> {
                Site site = siteRepository.findByUrl(config.getUrl()).orElse(null);
                if (site != null) {
                    updateSiteStatus(site, searchengine.model.SiteStatus.FAILED,
                            "Ошибка запуска индексации: " + e.getMessage());
                }
                return null;
            });
        }
    }

    // Сохраняем старый метод indexSite, но убираем из него логику обхода страниц
    protected void indexSite(SiteConfig config) {
        // Теперь этот метод только подготавливает сайт и запускает Fork-Join задачи
        startSiteIndexing(config);
    }

    @Override
    public synchronized boolean stopIndexing() {
        if (!indexingRunning.getAndSet(false)) {
            log.warn("No active indexing to stop");
            return false;
        }

        if (forkJoinPool != null) {
            forkJoinPool.shutdownNow();
            try {
                if (!forkJoinPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    forkJoinPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                forkJoinPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.execute(status -> {
            siteRepository.findAllByStatus(searchengine.model.SiteStatus.INDEXING).forEach(site -> {
                site.setStatus(searchengine.model.SiteStatus.FAILED);
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
        // Ищем сайт к которому принадлежит URL
        Optional<SiteConfig> siteConfigOpt = sitesList.getSites().stream() // stream() создает поток из списка сайтов
                .filter(s -> url.startsWith(s.getUrl())) // Фильтрация сайтов, чей URL является началом переданного URL
                .findFirst(); // Находит первый подходящий сайт

        if (siteConfigOpt.isEmpty()) {
            log.error("Page not in config: {}", url);
            return false;
        }

        SiteConfig config = siteConfigOpt.get();
        Site site = siteRepository.findByUrl(config.getUrl())
                .orElseGet(() -> createNewSite(config)); // Если сайт не найден, создаем новый

        // Выполняем индексацию страницы в транзакции с повторами
        // executeInTransactionWithRetry - метод для безопасного выполнения
        return Boolean.TRUE.equals(executeInTransactionWithRetry(() -> {
            try {
                // Скачиваем страницу с помощью Jsoup
                Document doc = Jsoup.connect(url)
                        .userAgent(sitesList.getUserAgent())
                        .referrer(sitesList.getReferrer())
                        .timeout(10_000)
                        .get();

                // Извлекаем путь страницы (часть URL после адреса сайта)
                String path = url.substring(site.getUrl().length());
                // Ищем существующую страницу в базе данных
                Optional<Page> existingPage = pageRepository.findBySiteAndPath(site, path);
                // Если страница уже существует, удаляем ее данные
                existingPage.ifPresent(this::deletePageData);

                Page page = savePage(site, path, doc);
                // Обрабатываем содержимое страницы (извлекаем слова)
                processPageContent(site, page, doc.html());
                // Возвращаем true - индексация успешна
                return true;
            } catch (IOException e) {
                log.error("Error indexing page: {}", url, e); // Ошибка - например, сайт не доступен
                // Возвращаем false - индексация не удалась
                return false;
            }
        }, 3, 1000)); // 3 попытки с задержкой 1000 мс
    }

    @Override
    public boolean isIndexingRunning() {
        return indexingRunning.get(); // Возвращает значение флага, используем get() для AtomicBoolean
    }

    // Индексация одного сайта
//    protected void indexSite(SiteConfig config) {
//        // Выполняем в транзакции: находим или создаем сайт, обновляем статус, очищаем данные, возвращаем сайт
//        Site site = executeInTransaction(() -> {
//            Site s = siteRepository.findByUrl(config.getUrl())
//                    .orElseGet(() -> createNewSite(config));
//            updateSiteStatus(s, SiteStatus.INDEXING, null);
//            clearSiteData(s);
//            return s;
//        });
//
//        try {
//            // Создание очереди URL для обхода (начинается с корневой страницы)
//            Queue<String> urlQueue = new ConcurrentLinkedQueue<>();
//            // Множество посещенных URL (чтобы не ходить по одним и тем же страницам)
//            Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
//
//            // Добавляем корневую страницу в очередь
//            urlQueue.add("/");
//            // Добавляем корневую страницу в посещенные
//            visitedUrls.add("/");
//
//            // Пока есть URL в очереди и индексация не остановлена
//            while (!urlQueue.isEmpty() && indexingRunning.get()) {
//                String path = urlQueue.poll(); // Берем следующий URL из очереди
//
//                // Пытаемся проиндексировать страницу в транзакции с повторами
//                // Индексируем страницу
//                // Если ошибка - логируем и возвращаем false
//                boolean success = Boolean.TRUE.equals(executeInTransactionWithRetry(() -> {
//                    try {
//                        indexPage(site, path); // Индексируем страницу
//                        return true;
//                    } catch (Exception e) {
//                        log.error("Error processing page: {}", path, e); // Если ошибка - логируем и возвращаем false
//                        return false;
//                    }
//                }, 3, 1000)); // 3 попытки с задержкой 1000 мс
//
//                if (!success) continue; // Если не удалось обработать страницу, переходим к следующей
//
//                try {
//                    // Извлекаем ссылки со страницы
//                    List<String> newLinks = extractLinksFromPage(site.getUrl() + path);
//                    for (String link : newLinks) {
//                        // Если еще не посещали эту ссылку
//                        if (!visitedUrls.contains(link)) {
//                            visitedUrls.add(link); // Добавляем в посещенные
//                            urlQueue.add(link); // Добавляем в очередь для обработки
//                        }
//                    }
//
//                    Thread.sleep(500); // Задержка для избежания перегрузки
//                } catch (Exception e) {
//                    log.error("Error extracting links: {}", path, e); // Если ошибка при извлечении ссылок, логируем и продолжаем
//                }
//            }
//
//            // Если индексация не была остановлена вручную
//            if (indexingRunning.get()) {
//                // Выполнение в транзакции: обновляем статус на INDEXED
//                executeInTransaction(() -> {
//                    updateSiteStatus(site, SiteStatus.INDEXED, null);
//                    return null;
//                });
//                log.info("Site indexed successfully: {}", site.getUrl());
//            }
//        } catch (Exception e) {
//            executeInTransaction(() -> {
//                updateSiteStatus(site, SiteStatus.FAILED, "Ошибка индексации: " + e.getMessage());
//                return null;
//            });
//            log.error("Site indexing failed: {}", site.getUrl(), e);
//        }
//    }

    // Метод для извлечения ссылок со страницы
    private List<String> extractLinksFromPage(String url) throws IOException {
        // Скачиваем страницу
        Document doc = Jsoup.connect(url)
                .userAgent(sitesList.getUserAgent())
                .referrer(sitesList.getReferrer())
                .timeout(10_000)
                .get();

        // Извлекаем все ссылки (теги <a> с атрибутом href)
        // doc.select("a[href]") находит все теги <a> у которых есть атрибут href
        // stream() создает поток из элементов
        // map(link -> link.attr("href")) извлекает значение атрибута href
        // filter(href -> href.startsWith("/")) оставляет только относительные ссылки (начинающиеся с /)
        // distinct() убирает дубликаты
        // collect(Collectors.toList()) собирает результат в список
        return doc.select("a[href]").stream()
                .map(link -> link.attr("href"))
                .filter(href -> href.startsWith("/"))
                .distinct()
                .collect(Collectors.toList());
    }

    // Метод для создания нового сайта
    private Site createNewSite(SiteConfig config) {
        Site site = new Site();
        site.setUrl(config.getUrl());
        site.setName(config.getName());
        site.setStatus(SiteStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        return siteRepository.save(site);
    }

    // Метод для обновления статуса сайта
    private void updateSiteStatus(Site site, SiteStatus status, String error) {
        site.setStatus(status);
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    // Метод для очистки данных сайта (выполняется в транзакции)
    @Transactional
    protected void clearSiteData(Site site) {
        indexRepository.deleteBySite(site);
        lemmaRepository.deleteBySite(site);
        pageRepository.deleteBySite(site);

        siteRepository.flush(); // Принудительно сбрасываем изменения (flush), чтобы гарантировать выполнение
    }

    // Метод для удаления данных страницы
    private void deletePageData(Page page) {
        indexRepository.deleteByPage(page);
        lemmaRepository.decrementFrequencyForPage(page);
        pageRepository.delete(page);
    }

    // Метод для сохранения страницы в базу данных
    private Page savePage(Site site, String path, Document doc) {
        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setCode(doc.connection().response().statusCode());
        page.setContent(doc.html());
        return pageRepository.save(page);
    }

    // Метод для обработки содержимого страницы
    protected void processPageContent(Site site, Page page, String html) {
        String text = Jsoup.parse(html).body().text(); // Извлекаем чистый текст из HTML (убираем все теги)
        Map<String, Integer> lemmas = lemmaService.collectLemmas(text); // Извлекаем леммы и их частоты из текста

        List<Lemma> lemmaEntities = new ArrayList<>();
        List<Index> indexEntities = new ArrayList<>();

        // Для каждой леммы и ее частоты
        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            String lemmaText = entry.getKey();
            int rank = entry.getValue();

            // Ищем лемму в базе данных или создаем новую
            Lemma lemma = lemmaRepository.findBySiteAndLemma(site, lemmaText)
                    .orElseGet(() -> {
                        Lemma newLemma = new Lemma();
                        newLemma.setSite(site);
                        newLemma.setLemma(lemmaText);
                        newLemma.setFrequency(0);
                        return newLemma;
                    });

            lemma.setFrequency(lemma.getFrequency() + 1); // Увеличиваем частоту леммы на 1
            lemmaEntities.add(lemma); // Добавляем лемму в список для сохранения

            // Создание связи между страницей и леммой
            Index index = new Index();
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(rank);
            indexEntities.add(index); // Добавляем связь в список для сохранения
        }

        lemmaRepository.saveAll(lemmaEntities); // Сохраняем все леммы в базу данных
        indexRepository.saveAll(indexEntities); // Сохраняем все связи в базу данных
    }

    // Метод для индексации одной страницы (внутренний вариант)
    private void indexPage(Site site, String path) throws IOException {
        // Скачиваем страницу
        Document doc = Jsoup.connect(site.getUrl() + path)
                .userAgent(sitesList.getUserAgent())
                .referrer(sitesList.getReferrer())
                .timeout(10_000)
                .get();

        // Ищем существующую страницу в базе данных
        Optional<Page> existingPage = pageRepository.findBySiteAndPath(site, path);
        // Если страница существует, удаляем ее данные
        existingPage.ifPresent(this::deletePageData);

        Page page = savePage(site, path, doc); // Сохраняем новую страницу в базу данных
        processPageContent(site, page, doc.html()); // Обрабатываем содержимое страницы
    }

    // Метод для выполнения операции в транзакции
    private <T> T executeInTransaction(TransactionalOperation<T> operation) {
        // Создаем TransactionTemplate с transactionManager
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        // Устанавливаем уровень изоляции: READ_COMMITTED (чтение подтвержденных данных)
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // Устанавливаем поведение распространения: REQUIRES_NEW (всегда новая транзакция)
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Выполняем операцию в транзакции
        return transactionTemplate.execute(status -> operation.execute());
    }

    // Метод для выполнения операции в транзакции с повторами при ошибках блокировок
    private <T> T executeInTransactionWithRetry(TransactionalOperation<T> operation, int maxAttempts, long delay) {
        int attempt = 0;
        // Выполнение операции maxAttempts раз
        while (attempt < maxAttempts) {
            try {
                // Выполнение операции в транзакции
                return executeInTransaction(operation);
            } catch (PessimisticLockingFailureException | ObjectOptimisticLockingFailureException ex) {
                // Если произошла ошибка блокировки
                attempt++;
                log.warn("Lock conflict detected, attempt {}/{}", attempt, maxAttempts);
                // Если превышение максимального количества попыток, то выбрасывается исключение
                if (attempt >= maxAttempts) {
                    throw ex;
                }
                try {
                    // Задержка перед следующей попыткой (увеличиваем с каждой попыткой)
                    Thread.sleep(delay * attempt);
                } catch (InterruptedException ie) {
                    // Если поток был прерван, то восстанавливается флаг прерывания и выбрасывается RuntimeException
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        return null; // Этот return никогда не выполнится, но компилятор требует его
    }

    // Функциональный интерфейс для операций в транзакциях с одним методом (для лямбд)
    @FunctionalInterface
    private interface TransactionalOperation<T> {
        T execute();
    }
}