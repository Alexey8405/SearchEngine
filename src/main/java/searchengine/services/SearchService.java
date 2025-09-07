package searchengine.services;

import searchengine.dto.statistics.SearchResponse;

public interface SearchService {
    // Метод для выполнения поиска
    // query - поисковый запрос (что ищем)
    // siteUrl - URL сайта для поиска (если null - ищем по всем сайтам)
    // offset - сдвиг для постраничного вывода (сколько результатов пропустить)
    // limit - ограничение количества результатов (сколько вернуть)
    // Возвращает SearchResponse - ответ с результатами поиска
    SearchResponse search(String query, String siteUrl, int offset, int limit);
}
