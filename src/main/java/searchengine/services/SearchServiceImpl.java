package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.statistics.*;
import searchengine.model.*;
import searchengine.repositories.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaService lemmaService;

    private static final double HIGH_FREQUENCY_THRESHOLD = 0.8;
    private static final int SNIPPET_LENGTH = 200;

    @Override
    @Transactional(readOnly = true)
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        try {
            log.info("Search request: query='{}', site='{}', offset={}, limit={}",
                    query, siteUrl, offset, limit);

            if (query == null || query.isBlank()) {
                return errorResponse("Задан пустой поисковый запрос");
            }

            Map<String, Integer> queryLemmas = safeCollectLemmas(query);
            if (queryLemmas.isEmpty()) {
                return errorResponse("Не найдено значимых слов в запросе");
            }

            Optional<Site> siteOpt = resolveSite(siteUrl);
            List<Lemma> filteredLemmas = filterLemmas(queryLemmas.keySet(), siteOpt);

            if (filteredLemmas.isEmpty()) {
                return errorResponse("Не найдено подходящих лемм для поиска");
            }

            List<Page> relevantPages = findRelevantPages(filteredLemmas, siteOpt);
            if (relevantPages.isEmpty()) {
                return errorResponse("Не найдено страниц, содержащих все слова запроса");
            }

            Map<Page, Float> pageRelevance = calculateRelevance(relevantPages, filteredLemmas);
            List<SearchResult> results = prepareResults(pageRelevance, queryLemmas.keySet(), offset, limit);

            return successResponse(results);
        } catch (Exception e) {
            log.error("Search error", e);
            return errorResponse("Ошибка при выполнении поиска");
        }
    }

    private Map<String, Integer> safeCollectLemmas(String query) {
        try {
            return lemmaService.collectLemmas(query);
        } catch (Exception e) {
            log.error("Lemma collection error", e);
            return Collections.emptyMap();
        }
    }

    private Optional<Site> resolveSite(String siteUrl) {
        if (siteUrl == null || siteUrl.isBlank()) {
            return Optional.empty();
        }

        return siteRepository.findByUrl(siteUrl)
                .filter(site -> site.getStatus() == SiteStatus.INDEXED);
    }

        private List<Lemma> filterLemmas(Set<String> lemmaStrings, Optional<Site> siteOpt) {
        List<Lemma> lemmas = siteOpt.isPresent()
                ? lemmaRepository.findBySiteAndLemmaIn(siteOpt.get(), lemmaStrings)
                : lemmaRepository.findByLemmaIn(lemmaStrings);

        if (lemmas.isEmpty()) {
            return Collections.emptyList();
        }

        long totalPages;
        if (siteOpt.isPresent()) {
            totalPages = pageRepository.countBySite(siteOpt.get());
        } else {
            totalPages = pageRepository.count();
        }

        long threshold = (long) (totalPages * HIGH_FREQUENCY_THRESHOLD);

        return lemmas.stream()
                .filter(l -> l.getFrequency() <= threshold)
                .sorted(Comparator.comparingInt(Lemma::getFrequency))
                .collect(Collectors.toList());
    }

    private List<Page> findRelevantPages(List<Lemma> lemmas, Optional<Site> siteOpt) {
        if (lemmas.isEmpty()) {
            return Collections.emptyList();
        }

        Lemma firstLemma = lemmas.get(0);
        List<Page> pages = indexRepository.findPagesByLemma(firstLemma);

        if (siteOpt.isPresent()) {
            Site site = siteOpt.get();
            pages = pages.stream()
                    .filter(p -> p.getSite().getId() == site.getId())
                    .collect(Collectors.toList());
        }

        for (int i = 1; i < lemmas.size() && !pages.isEmpty(); i++) {
            Lemma lemma = lemmas.get(i);
            Set<Page> lemmaPages = new HashSet<>(indexRepository.findPagesByLemma(lemma));
            pages = pages.stream()
                    .filter(lemmaPages::contains)
                    .collect(Collectors.toList());
        }

        return pages;
    }

    private Map<Page, Float> calculateRelevance(List<Page> pages, List<Lemma> lemmas) {
        Map<Page, Float> relevanceMap = new HashMap<>();
        float maxRelevance = 0f;

        for (Page page : pages) {
            float relevance = 0f;
            for (Lemma lemma : lemmas) {
                relevance += indexRepository.findRankByPageAndLemma(page, lemma)
                        .orElse(0f);
            }
            relevanceMap.put(page, relevance);
            if (relevance > maxRelevance) {
                maxRelevance = relevance;
            }
        }

        // Нормализация только если есть страницы с релевантностью > 0
        if (maxRelevance > 0f) {
            for (Page page : pages) {
                float rel = relevanceMap.get(page) / maxRelevance;
                relevanceMap.put(page, rel);
            }
        }

        return relevanceMap;
    }

    private List<SearchResult> prepareResults(Map<Page, Float> pageRelevance,
                                              Set<String> queryLemmas,
                                              int offset, int limit) {
        return pageRelevance.entrySet().stream()
                .sorted(Map.Entry.<Page, Float>comparingByValue().reversed())
                .skip(offset)
                .limit(limit)
                .map(entry -> createSearchResult(entry.getKey(), entry.getValue(), queryLemmas))
                .collect(Collectors.toList());
    }

    private SearchResult createSearchResult(Page page, float relevance, Set<String> queryLemmas) {
        try {
            Document doc = Jsoup.parse(page.getContent());
            return new SearchResult(
                    page.getSite().getUrl(),
                    page.getSite().getName(),
                    page.getPath(),
                    doc.title(),
                    createSnippet(doc.text(), queryLemmas),
                    relevance
            );
        } catch (Exception e) {
            log.error("Error parsing page content", e);
            return new SearchResult(
                    page.getSite().getUrl(),
                    page.getSite().getName(),
                    page.getPath(),
                    "Untitled",
                    "Snippet not available",
                    relevance
            );
        }
    }

    private String createSnippet(String text, Set<String> queryLemmas) {
        String lowerText = text.toLowerCase();
        List<String> fragments = new ArrayList<>();

        for (String lemma : queryLemmas) {
            String lowerLemma = lemma.toLowerCase();
            int idx = lowerText.indexOf(lowerLemma);

            if (idx >= 0) {
                int start = Math.max(0, idx - 30);
                int end = Math.min(text.length(), idx + lemma.length() + 30);

                String fragment = text.substring(start, end);
                fragment = highlightLemmas(fragment, queryLemmas);
                fragments.add(fragment);
            }
        }

        if (fragments.isEmpty()) {
            return text.substring(0, Math.min(SNIPPET_LENGTH, text.length())) + "...";
        }

        return "..." + String.join(" ... ", fragments) + "...";
    }

    private String highlightLemmas(String text, Set<String> queryLemmas) {
        String result = text;
        for (String lemma : queryLemmas) {
            result = result.replaceAll(
                    "(?i)(" + Pattern.quote(lemma) + ")",
                    "<b>$1</b>"
            );
        }
        return result;
    }

    private SearchResponse errorResponse(String error) {
        return new SearchResponse(false, 0, null, error);
    }

    private SearchResponse successResponse(List<SearchResult> results) {
        return new SearchResponse(true, results.size(), results, null);
    }
}