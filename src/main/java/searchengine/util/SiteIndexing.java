package searchengine.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.services.LemmaService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SiteIndexing extends RecursiveAction {

    private final Site site;
    private final String currentPath;
    private final AtomicBoolean isIndexingRunning;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final LemmaService lemmaService;
    private final IndexRepository indexRepository;

    public SiteIndexing(Site site, String currentPath, AtomicBoolean isIndexingRunning, PageRepository pageRepository, LemmaRepository lemmaRepository, LemmaService lemmaService, IndexRepository indexRepository) {
        this.site = site;
        this.currentPath = currentPath;
        this.isIndexingRunning = isIndexingRunning;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.lemmaService = lemmaService;
        this.indexRepository = indexRepository;
    }


    @Override
    protected void compute() {
        if (!isIndexingRunning.get()) {
            throw new RuntimeException("Индексация остановлена пользователем");
        }

        try {
            Thread.sleep(500); // Задержка между запросами

            Document doc = Jsoup.connect(site.getUrl() + currentPath)
                    .userAgent("")
                    .referrer("")
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

            newPaths.forEach(path -> new SiteIndexing(site, path, isIndexingRunning,
                    pageRepository, lemmaRepository, lemmaService, indexRepository));

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
}
