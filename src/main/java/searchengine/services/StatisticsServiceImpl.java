package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.statistics.*;
import searchengine.model.Site;
import searchengine.repositories.*;

import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexingService indexingService;

    @Override
    @Transactional(readOnly = true)
    public StatisticsResponse getStatistics() {
        try {
            TotalStatistics total = new TotalStatistics(
                    (int) siteRepository.count(),
                    pageRepository.count(),
                    lemmaRepository.count(),
                    indexingService.isIndexingRunning()
            );

            List<DetailedStatisticsItem> detailed = siteRepository.findAll().stream()
                    .map(this::convertToDetailedItem)
                    .collect(Collectors.toList());

            return new StatisticsResponse(true, new StatisticsData(total, detailed));
        } catch (Exception e) {
            log.error("Error getting statistics", e);
            return new StatisticsResponse(false, null);
        }
    }

    private DetailedStatisticsItem convertToDetailedItem(Site site) {
        return new DetailedStatisticsItem(
                site.getUrl(),
                site.getName(),
                site.getStatus().name(),
                site.getStatusTime().atZone(ZoneId.systemDefault()).toEpochSecond(),
                site.getLastError(),
                (int) pageRepository.countBySite(site),
                lemmaRepository.countBySite(site)
        );
    }
}
