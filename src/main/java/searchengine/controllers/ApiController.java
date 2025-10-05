package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.statistics.SearchResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.util.Map;


@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final IndexingService indexingService;
    private final SearchService searchService;
    private final StatisticsService statisticsService;

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> getStatistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<?> startIndexing() {
        if (indexingService.startIndexing()) {
            return ResponseEntity.ok(Map.of("result", true));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", false,
                    "error", "Индексация уже запущена"
            ));
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<?> stopIndexing() {
        if (indexingService.stopIndexing()) {
            return ResponseEntity.ok(Map.of("result", true));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", false,
                    "error", "Индексация не запущена"
            ));
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam String url) {
        if (indexingService.indexPage(url)) {
            return ResponseEntity.ok(Map.of("result", true));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", false,
                    "error", "Страница не принадлежит сайтам из конфигурации"
            ));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String site,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(
                    new SearchResponse(false, 0, null, "Задан пустой поисковый запрос")
            );
        }

        SearchResponse response = searchService.search(query, site, offset, limit);
        return ResponseEntity.ok(response);
    }
}
