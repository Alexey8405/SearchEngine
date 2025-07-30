package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.SearchResponse;
import searchengine.dto.statistics.SearchResult;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaService lemmaService;

    @Override
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        Map<String, Integer> queryLemmas = lemmaService.collectLemmas(query);
        if (queryLemmas.isEmpty()) {
            return new SearchResponse(false, 0, null, "Задан пустой поисковый запрос");
        }

        Optional<Site> siteOpt = siteUrl != null
                ? siteRepository.findByUrl(siteUrl)
                : Optional.empty();

        List<Lemma> filteredLemmas = filterLemmas(queryLemmas.keySet(), siteOpt);
        if (filteredLemmas.isEmpty()) {
            return new SearchResponse(false, 0, null, "Не найдено подходящих страниц");
        }

        List<SearchResult> results = findPages(filteredLemmas, siteOpt);
        List<SearchResult> paginatedResults = applyPagination(results, offset, limit);

        return new SearchResponse(true, results.size(), paginatedResults, null);
    }

    private List<Lemma> filterLemmas(Set<String> lemmaStrings, Optional<Site> siteOpt) {
        List<Lemma> lemmas = siteOpt.isPresent()
                ? lemmaRepository.findBySiteAndLemmaIn(siteOpt.get(), lemmaStrings)
                : lemmaRepository.findByLemmaIn(lemmaStrings);

        int totalPages = siteOpt.map(s -> (int) pageRepository.countBySite(s))
                .orElse((int) pageRepository.count());
        int threshold = (int) (totalPages * 0.8);

        return lemmas.stream()
                .filter(l -> l.getFrequency() <= threshold)
                .sorted(Comparator.comparingInt(Lemma::getFrequency))
                .collect(Collectors.toList());
    }

    private List<SearchResult> findPages(List<Lemma> lemmas, Optional<Site> siteOpt) {
        List<Page> pages = findPagesContainingAllLemmas(lemmas, siteOpt);
        if (pages.isEmpty()) return Collections.emptyList();

        Map<Page, Float> pageRelevance = calculateAbsoluteRelevance(pages, lemmas);
        float maxRelevance = pageRelevance.values().stream().max(Float::compare).orElse(1f);

        return pages.stream()
                .sorted((p1, p2) -> Float.compare(
                        pageRelevance.get(p2) / maxRelevance,
                        pageRelevance.get(p1) / maxRelevance
                ))
                .map(page -> convertToSearchResult(page, pageRelevance.get(page) / maxRelevance))
                .collect(Collectors.toList());
    }

    private List<Page> findPagesContainingAllLemmas(List<Lemma> lemmas, Optional<Site> siteOpt) {
        if (lemmas.isEmpty()) return Collections.emptyList();

        List<Page> pages = indexRepository.findPagesByLemma(lemmas.get(0));

        if (siteOpt.isPresent()) {
            pages = pages.stream()
                    .filter(p -> p.getSite().equals(siteOpt.get()))
                    .collect(Collectors.toList());
        }

        for (int i = 1; i < lemmas.size() && !pages.isEmpty(); i++) {
            Lemma lemma = lemmas.get(i);
            Set<Page> pagesWithLemma = new HashSet<>(indexRepository.findPagesByLemma(lemma));
            pages = pages.stream()
                    .filter(pagesWithLemma::contains)
                    .collect(Collectors.toList());
        }

        return pages;
    }

    private Map<Page, Float> calculateAbsoluteRelevance(List<Page> pages, List<Lemma> lemmas) {
        Map<Page, Float> relevanceMap = new HashMap<>();

        for (Page page : pages) {
            float relevance = 0;
            for (Lemma lemma : lemmas) {
                relevance += indexRepository.findRankByPageAndLemma(page, lemma)
                        .orElse(0f);
            }
            relevanceMap.put(page, relevance);
        }

        return relevanceMap;
    }

    private SearchResult convertToSearchResult(Page page, float relevance) {
        String title = Jsoup.parse(page.getContent()).title();
        Set<String> queryLemmas = Set.of(); // Здесь должны быть леммы запроса

        return new SearchResult(
                page.getSite().getUrl(),
                page.getSite().getName(),
                page.getPath(),
                title,
                createSnippet(page, queryLemmas),
                relevance
        );
    }

    private List<SearchResult> applyPagination(List<SearchResult> results, int offset, int limit) {
        return results.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String createSnippet(Page page, Set<String> queryLemmas) {
        String text = Jsoup.parse(page.getContent()).text();
        return text.substring(0, Math.min(200, text.length())) + "...";
    }
}