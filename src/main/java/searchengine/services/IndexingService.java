package searchengine.services;

public interface IndexingService {
    boolean startIndexing(); // Запуск полной индексации
    boolean stopIndexing();  // Остановка индексации
    boolean indexPage(String url); // Индексация одной страницы
    boolean isIndexingRunning(); // Проверка, идёт ли индексация
}
