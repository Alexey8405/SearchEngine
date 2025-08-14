package searchengine.services;

public interface IndexingService {
    boolean startIndexing();
    boolean stopIndexing();
    boolean indexPage(String url);
    boolean isIndexingRunning();
}
