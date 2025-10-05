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

    // Порог для отсечения слишком частых слов (80% страниц)
    // Слова которые встречаются на слишком многих страницах считаются неинформативными
    private static final double HIGH_FREQUENCY_THRESHOLD = 0.8;
    // Длина сниппета в символах
    private static final int SNIPPET_LENGTH = 200;

    // @Transactional(readOnly = true) - выполняется в транзакции только для чтения
    // readOnly = true оптимизирует производительность для запросов только на чтение
    @Override
    @Transactional(readOnly = true)
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        try {
            log.info("Search request: query='{}', site='{}', offset={}, limit={}",
                    query, siteUrl, offset, limit);

            if (query == null || query.isBlank()) {
                return errorResponse("Задан пустой поисковый запрос");
            }

            // Извлекаем леммы из поискового запроса
            Map<String, Integer> queryLemmas = safeCollectLemmas(query);
            if (queryLemmas.isEmpty()) {
                return errorResponse("Не найдено значимых слов в запросе");
            }

            // Определяем сайт для поиска (если указан)
            Optional<Site> siteOpt = resolveSite(siteUrl);
            // Фильтруем леммы (убираем слишком частые и сортируем)
            List<Lemma> filteredLemmas = filterLemmas(queryLemmas.keySet(), siteOpt);

            // Проверка лемм после фильтрации
            if (filteredLemmas.isEmpty()) {
                return errorResponse("Не найдено подходящих лемм для поиска");
            }

            // Ищем страницы, которые содержат все отфильтрованные леммы
            List<Page> relevantPages = findRelevantPages(filteredLemmas, siteOpt);
            if (relevantPages.isEmpty()) {
                return errorResponse("Не найдено страниц, содержащих все слова запроса");
            }

            // Вычисление релевантности для каждой страницы
            Map<Page, Float> pageRelevance = calculateRelevance(relevantPages, filteredLemmas);

            // Получаем ВСЕ результаты для подсчета общего количества
            List<SearchResult> allResults = prepareAllResults(pageRelevance, queryLemmas.keySet());
            int totalCount = allResults.size();

            // Получаем только нужную страницу для отображения
            List<SearchResult> paginatedResults = paginateResults(allResults, offset, limit);

            return successResponse(totalCount, paginatedResults); // Передаем общее количество

        } catch (Exception e) {
            log.error("Search error", e);
            return errorResponse("Ошибка при выполнении поиска");
        }
    }

    // Подготовка всех результатов (без пагинации)
    private List<SearchResult> prepareAllResults(Map<Page, Float> pageRelevance, Set<String> queryLemmas) {
        return pageRelevance.entrySet().stream()
                .sorted(Map.Entry.<Page, Float>comparingByValue().reversed())
                .map(entry -> createSearchResult(entry.getKey(), entry.getValue(), queryLemmas))
                .collect(Collectors.toList());
    }

    // Пагинация результатов
    private List<SearchResult> paginateResults(List<SearchResult> allResults, int offset, int limit) {
        return allResults.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    // Создание успешного ответа с общим количеством
    private SearchResponse successResponse(int totalCount, List<SearchResult> results) {
        return new SearchResponse(true, totalCount, results, null);
    }

    // Безопасное извлечение лемм из запроса (с обработкой исключений)
    private Map<String, Integer> safeCollectLemmas(String query) {
        try {
            return lemmaService.collectLemmas(query); // Извлекаем леммы с помощью метода collectLemmas класса LemmaService
        } catch (Exception e) {
            log.error("Lemma collection error", e); // Логируем ошибку, но не прерываем выполнение
            return Collections.emptyMap(); // Возвращаем пустую мапу
        }
    }

    // Определение сайта для поиска
    private Optional<Site> resolveSite(String siteUrl) {
        // Если сайт не указан или пустой - ищем по всем сайтам
        if (siteUrl == null || siteUrl.isBlank()) {
            return Optional.empty();
        }

        // Ищем сайт по URL и проверяем, что он проиндексирован
        return siteRepository.findByUrl(siteUrl)
                .filter(site -> site.getStatus() == SiteStatus.INDEXED);
    }

    // Фильтрация лемм (удаление слишком частых и сортировка)
    private List<Lemma> filterLemmas(Set<String> lemmaStrings, Optional<Site> siteOpt) {
        List<Lemma> lemmas;

        // Получаем леммы в зависимости от того указан сайт или нет
        if (siteOpt.isPresent()) {
            lemmas = lemmaRepository.findBySiteAndLemmaIn(siteOpt.get(), lemmaStrings); // Ищем леммы только для указанного сайта
        } else {
            lemmas = lemmaRepository.findByLemmaIn(lemmaStrings); // Ищем леммы по всем сайтам
        }

        if (lemmas.isEmpty()) {
            return Collections.emptyList();
        }

        // Если указан конкретный сайт применяем дополнительную фильтрацию
        if (siteOpt.isPresent()) {
            long totalPages = pageRepository.countBySite(siteOpt.get()); // Считаем общее количество страниц на сайте
            long threshold = (long) (totalPages * HIGH_FREQUENCY_THRESHOLD); // Вычисляем порог для частых слов

            // Фильтруем леммы, которые встречаются слишком часто
            List<Lemma> filtered = lemmas.stream()
                    .filter(l -> l.getFrequency() <= threshold)
                    .toList();

            // Если все леммы слишком частые возвращаем самую редкую
            if (filtered.isEmpty()) {
                Lemma rarest = lemmas.stream()
                        .min(Comparator.comparingInt(Lemma::getFrequency)) // Поиск леммы с минимальной частотой
                        .orElse(lemmas.get(0)); // Если не нашли минимум - берём первую лемму из списка
                return Collections.singletonList(rarest); // Неизменяемый список с одним элементом
            }
            // Сортируем от самых редких к самым частым
            return filtered.stream()
                    .sorted(Comparator.comparingInt(Lemma::getFrequency)) // Сортировка лемм по частоте в порядке возрастания
                    .collect(Collectors.toList());
        }

        // Для поиска по всем сайтам сортируем по частоте
        return lemmas.stream()
                .sorted(Comparator.comparingInt(Lemma::getFrequency))
                .collect(Collectors.toList());
    }

    // Поиск страниц, содержащих все леммы
    private List<Page> findRelevantPages(List<Lemma> lemmas, Optional<Site> siteOpt) {
        if (lemmas.isEmpty()) {
            return Collections.emptyList();
        }
        // Для поиска с указанием сайта
        if (siteOpt.isPresent()) {
            Site site = siteOpt.get();
            // Фильтруем леммы ТОЛЬКО для указанного сайта
            List<Lemma> siteLemmas = lemmas.stream()
                    .filter(lemma -> lemma.getSite().getId() == site.getId())
                    .sorted(Comparator.comparingInt(Lemma::getFrequency))
                    .toList();

            if (siteLemmas.isEmpty()) {
                return Collections.emptyList();
            }

            // Берем самую редкую лемму и находим страницы, где она встречается
            Lemma firstLemma = siteLemmas.get(0);
            List<Page> pages = indexRepository.findPagesByLemma(firstLemma);

            // Фильтруем страницы ТОЛЬКО для указанного сайта
            pages = pages.stream()
                    .filter(p -> p.getSite().getId() == site.getId())
                    .collect(Collectors.toList());

            // Для каждой следующей леммы фильтруем страницы
            for (int i = 1; i < siteLemmas.size() && !pages.isEmpty(); i++) {
                Lemma lemma = siteLemmas.get(i);
                Set<Page> lemmaPages = new HashSet<>(indexRepository.findPagesByLemma(lemma));
                pages = pages.stream()
                        .filter(lemmaPages::contains)
                        .collect(Collectors.toList());
            }
            return pages;
        }
        // Для поиска по всем сайтам
        else {
            // Группируем леммы по сайтам
            Map<Site, List<Lemma>> lemmasBySite = lemmas.stream()
                    .collect(Collectors.groupingBy(Lemma::getSite));

            // Результирущая переменная
            List<Page> relevantPages = new ArrayList<>();

            // Для каждого сайта выполняем поиск отдельно
            for (Map.Entry<Site, List<Lemma>> entry : lemmasBySite.entrySet()) {
                Site site = entry.getKey();
                List<Lemma> siteLemmas = entry.getValue();
                siteLemmas.sort(Comparator.comparingInt(Lemma::getFrequency)); // Сортировка по частоте

                Lemma firstLemma = siteLemmas.get(0);
                List<Page> pages = indexRepository.findPagesByLemma(firstLemma);

                // Фильтруем страницы по текущему сайту (проверка, что страницы с нужного сайта)
                pages = pages.stream()
                        .filter(p -> p.getSite().getId() == site.getId())
                        .collect(Collectors.toList());

                // Начинаем со ВТОРОЙ леммы (первая уже обработана)
                // Цикл прервётся, если страниц не останется
                for (int i = 1; i < siteLemmas.size() && !pages.isEmpty(); i++) {
                    Lemma lemma = siteLemmas.get(i);
                    Set<Page> lemmaPages = new HashSet<>(indexRepository.findPagesByLemma(lemma));
                    pages = pages.stream()
                            .filter(lemmaPages::contains)
                            .collect(Collectors.toList()); // В pages остаются только страницы, которые содержат ВСЕ леммы от первой до текущей
                }
                relevantPages.addAll(pages);
            }
            return relevantPages;
        }
    }

    // Вычисление релевантности страниц
    private Map<Page, Float> calculateRelevance(List<Page> pages, List<Lemma> lemmas) {
        Map<Page, Float> relevanceMap = new HashMap<>();
        float maxRelevance = 0f;

        // Для каждой страницы считаем абсолютную релевантность
        for (Page page : pages) {
            float relevance = 0f;
            // Суммируем веса всех лемм на странице
            for (Lemma lemma : lemmas) {
                relevance += indexRepository.findRankByPageAndLemma(page, lemma)
                        .orElse(0f);
            }
            relevanceMap.put(page, relevance);
            // Устанавливаем максимальную релевантность
            if (relevance > maxRelevance) {
                maxRelevance = relevance;
            }
        }
        // Нормализуем релевантность (приводим все значения к диапазону от 0.0 до 1.0 - 1.0 самая релевантная, 0.0 - самая нерелевантная)
        if (maxRelevance > 0f) {
            for (Page page : pages) {
                float rel = relevanceMap.get(page) / maxRelevance;
                relevanceMap.put(page, rel);
            }
        }

        return relevanceMap;
    }

    // Подготовка результатов для ответа
    private List<SearchResult> prepareResults(Map<Page, Float> pageRelevance,
                                              Set<String> queryLemmas,
                                              int offset, int limit) {
        return pageRelevance.entrySet().stream() //Извлекаем данные в сет и преобразуем в поток для ряда операций
                .sorted(Map.Entry.<Page, Float>comparingByValue().reversed()) // Сортируем по релевантности (от высокой к низкой)
                .skip(offset) // Пропускаем первые offset результатов (для пагинации)
                .limit(limit)// Ограничиваем количество результатов
                .map(entry -> createSearchResult(entry.getKey(), entry.getValue(), queryLemmas)) // Преобразуем каждую запись в SearchResult
                .collect(Collectors.toList());
    }

    // Создание одного результата поиска
    private SearchResult createSearchResult(Page page, float relevance, Set<String> queryLemmas) {
        try {
            Document doc = Jsoup.parse(page.getContent()); // Парсим HTML страницы, чтобы извлечь заголовок и текст
            return new SearchResult(
                    page.getSite().getUrl(),
                    page.getSite().getName(),
                    page.getPath(),
                    doc.title(), // Заголовок страницы
                    createSnippet(doc.text(), queryLemmas), // Сниппет с подсветкой
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
    // Создание сниппета (с подсветкой)
    private String createSnippet(String text, Set<String> queryLemmas) {
        String lowerText = text.toLowerCase();
        List<String> fragments = new ArrayList<>(); // Список для хранения найденных фрагментов текста
        Set<String> foundWords = findMatchingWords(text, queryLemmas); // Ищем все слова, которые содержат леммы из запроса

        if (foundWords.isEmpty()) {
            // Если не нашли совпадений, возвращаем начало текста
            return text.substring(0, Math.min(SNIPPET_LENGTH, text.length())) + "...";
        }

        // Ищем вхождения оригинальных форм слов в тексте
        for (String wordForm : foundWords) {
            String lowerWord = wordForm.toLowerCase();
            int idx = lowerText.indexOf(lowerWord);// Ищем ПЕРВОЕ вхождение слова в тексте (-1, если не найдено)
            // Для каждого вхождения добавляем фрагмент текста вокруг
            while (idx >= 0 && fragments.size() < 3) {
                int start = Math.max(0, idx - 50); // Начинаем фрагмент за 50 символов ДО найденного слова (0, чтобы не выйти за начало текста, если слово в начале)
                int end = Math.min(text.length(), idx + wordForm.length() + 50); // Заканчиваем фрагмент через 50 символов ПОСЛЕ слова (Math.min(text.length(), ...) — чтобы не выйти за конец текста)

                // Сохраняем оригинальный фрагмент текста (с правильным регистром)
                String fragment = text.substring(start, end); // Вырезаем фрагмент из ОРИГИНАЛЬНОГО текста (с сохранением регистра)
                fragments.add(fragment); // Добавляем фрагмент в список
                idx = lowerText.indexOf(lowerWord, end);
            }
            if (fragments.size() >= 3) break;
        }

        String snippet = "..." + String.join(" ... ", fragments) + "...";
        // Выделяем слова ДО объединения в сниппет
        return highlightWordsInFragments(fragments, foundWords);
    }

    // Поиск слов в тексте, которые содержат леммы из запроса (находим только чистые слова без пунктуации)
    private Set<String> findMatchingWords(String text, Set<String> queryLemmas) {
        Set<String> foundWords = new HashSet<>();
        String[] words = text.split("\\s+");

        for (String originalWord : words) {
            // Очищаем слово от пунктуации по краям
            String cleanWord = originalWord.replaceAll("^[^a-zA-Zа-яё]+|[^a-zA-Zа-яё]+$", "").toLowerCase();
            if (cleanWord.length() < 3) continue;

            // Проверяем, содержит ли слово любую из лемм запроса
            for (String lemma : queryLemmas) {
                String cleanLemma = lemma.toLowerCase();
                if (cleanWord.contains(cleanLemma)) {
                    // Сохраняем очищенное слово для выделения
                    foundWords.add(cleanWord);
                    break;
                }
            }
        }
        return foundWords;
    }

    // Выделение слов в отдельных фрагментах до объединения (выделяем только чистые слова без пунктуации)
    private String highlightWordsInFragments(List<String> fragments, Set<String> wordsToHighlight) {
        List<String> highlightedFragments = new ArrayList<>();

        for (String fragment : fragments) {
            String highlightedFragment = fragment;

            for (String cleanWord : wordsToHighlight) {
                // Выделяем слово с учетом регистра из оригинального фрагмента
                highlightedFragment = highlightCleanWord(highlightedFragment, cleanWord);
            }

            highlightedFragments.add(highlightedFragment);
        }

        return "..." + String.join(" ... ", highlightedFragments) + "...";
    }

    // Выделение чистого слова с поиском по разным формам регистра
    private String highlightCleanWord(String text, String cleanWord) {
        try {
            // Ищем слово в разных вариантах регистра
            String lowerText = text.toLowerCase();
            String lowerCleanWord = cleanWord.toLowerCase();
            int idx = lowerText.indexOf(lowerCleanWord);

            while (idx >= 0) {
                // Проверяем, что это отдельное слово (не часть другого слова)
                boolean isWordStart = (idx == 0) || !Character.isLetterOrDigit(text.charAt(idx - 1));
                boolean isWordEnd = (idx + lowerCleanWord.length() == text.length()) ||
                        !Character.isLetterOrDigit(text.charAt(idx + lowerCleanWord.length()));

                if (isWordStart && isWordEnd) {
                    // Находим точное слово с оригинальным регистром
                    String exactWord = text.substring(idx, idx + lowerCleanWord.length());

                    // Заменяем только если это не уже выделенное слово
                    if (!exactWord.startsWith("<b>")) {
                        String before = text.substring(0, idx);
                        String after = text.substring(idx + exactWord.length());
                        text = before + "<b>" + exactWord + "</b>" + after;

                        // Обновляем lowerText после изменения
                        lowerText = text.toLowerCase();
                    }
                }

                // Ищем следующее вхождение
                idx = lowerText.indexOf(lowerCleanWord, idx + lowerCleanWord.length() + 7); // +7 для "<b></b>"
            }
        } catch (Exception e) {
            log.debug("Error highlighting clean word: {}", cleanWord, e);
        }

        return text;
    }

    // Создание ответа с ошибкой
    private SearchResponse errorResponse(String error) {
        return new SearchResponse(false, 0, null, error);
    }

    // Создание успешного ответа
    private SearchResponse successResponse(List<SearchResult> results) {
        return new SearchResponse(true, results.size(), results, null);
    }
}