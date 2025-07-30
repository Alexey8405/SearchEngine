package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.SiteConfig;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexingService indexingService;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics totalStats = new TotalStatistics(
                (int) siteRepository.count(),
                pageRepository.count(),
                lemmaRepository.count(),
                indexingService.isIndexingRunning()
        );

        List<DetailedStatisticsItem> detailedStats = siteRepository.findAll().stream()
                .map(site -> new DetailedStatisticsItem(
                        site.getUrl(),
                        site.getName(),
                        site.getStatus().toString(),
                        site.getStatusTime().atZone(ZoneId.systemDefault()).toEpochSecond(),
                        site.getLastError(),
                        pageRepository.countBySite(site),
                        lemmaRepository.countBySite(site)
                ))
                .collect(Collectors.toList());

        return new StatisticsResponse(
                true,
                new StatisticsData(totalStats, detailedStats)
        );
    }
}



//@Service
//@RequiredArgsConstructor
//public class StatisticsServiceImpl implements StatisticsService {
//
//    private final Random random = new Random();
//    private final SitesList sites;
//
//    @Override
//    public StatisticsResponse getStatistics() {
//        String[] statuses = { "INDEXED", "FAILED", "INDEXING" };
//        String[] errors = {
//                "Ошибка индексации: главная страница сайта не доступна",
//                "Ошибка индексации: сайт не доступен",
//                ""
//        };
//
//        TotalStatistics total = new TotalStatistics();
//        total.setSites(sites.getSites().size());
//        total.setIndexing(true);
//
//        List<DetailedStatisticsItem> detailed = new ArrayList<>();
//        List<SiteConfig> sitesList = sites.getSites();
//        for(int i = 0; i < sitesList.size(); i++) {
//            SiteConfig site = sitesList.get(i);
//            DetailedStatisticsItem item = new DetailedStatisticsItem();
//            item.setName(site.getName());
//            item.setUrl(site.getUrl());
//            int pages = random.nextInt(1_000);
//            int lemmas = pages * random.nextInt(1_000);
//            item.setPages(pages);
//            item.setLemmas(lemmas);
//            item.setStatus(statuses[i % 3]);
//            item.setError(errors[i % 3]);
//            item.setStatusTime(System.currentTimeMillis() -
//                    (random.nextInt(10_000)));
//            total.setPages(total.getPages() + pages);
//            total.setLemmas(total.getLemmas() + lemmas);
//            detailed.add(item);
//        }
//
//        StatisticsResponse response = new StatisticsResponse();
//        StatisticsData data = new StatisticsData();
//        data.setTotal(total);
//        data.setDetailed(detailed);
//        response.setStatistics(data);
//        response.setResult(true);
//        return response;
//    }
//}
