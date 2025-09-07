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
            // Подготовка результатов для ответа
            List<SearchResult> results = prepareResults(pageRelevance, queryLemmas.keySet(), offset, limit);

            return successResponse(results);
        } catch (Exception e) {
            log.error("Search error", e);
            return errorResponse("Ошибка при выполнении поиска");
        }
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
                    .collect(Collectors.toList());

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
                    .collect(Collectors.toList());

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
        Set<String> foundLemmas = new HashSet<>(); // Множество для отслеживания, какие леммы мы нашли

        // Ищем все вхождения лемм в тексте
        for (String lemma : queryLemmas) {
            String lowerLemma = lemma.toLowerCase();
            int idx = lowerText.indexOf(lowerLemma); // Ищем ПЕРВОЕ вхождение леммы в тексте (-1, если не найдено)
            // Для каждого вхождения добавляем фрагмент текста вокруг
            while (idx >= 0) {
                int start = Math.max(0, idx - 30); // Начинаем фрагмент за 30 символов ДО найденной леммы (0, чтобы не выйти за начало текста, если лемма в начале)
                int end = Math.min(text.length(), idx + lowerLemma.length() + 30); // Заканчиваем фрагмент через 30 символов ПОСЛЕ леммы (Math.min(text.length(), ...) — чтобы не выйти за конец текста)
                fragments.add(text.substring(start, end)); // Вырезаем фрагмент из ОРИГИНАЛЬНОГО текста (с сохранением регистра) и добавляем фрагмент в список
                foundLemmas.add(lemma); // Отмечаем, что эту лемму мы нашли
                idx = lowerText.indexOf(lowerLemma, end); // Ищем СЛЕДУЮЩЕЕ вхождение леммы, начиная с позиции end
            }
        }

        // Если не найдены все леммы, используем начало текста длиной SNIPPET_LENGTH или меньше, если текст короткий, добавляем многоточие в конце
        if (foundLemmas.size() < queryLemmas.size()) {
            return text.substring(0, Math.min(SNIPPET_LENGTH, text.length())) + "...";
        }

        String snippet = "..." + String.join(" ... ", fragments) + "...";
        return highlightLemmas(snippet, queryLemmas);
    }

    // Подсветка лемм в тексте (обертывание в теги <b>)
    private String highlightLemmas(String text, Set<String> queryLemmas) {
        String result = text; // Берем оригинальный текст
        for (String lemma : queryLemmas) { // Перебираем КАЖДУЮ лемму из поискового запроса
            result = result.replaceAll( // Для каждой леммы делаем замену в тексте
                    "(?i)(" + Pattern.quote(lemma) + ")", // (?i) — флаг "case insensitive" (игнорировать регистр) + экранируем спецсимволы
                    "<b>$1</b>" // $1 - ссылка на ПЕРВУЮ найденную лемму
            );
        }
        return result;
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