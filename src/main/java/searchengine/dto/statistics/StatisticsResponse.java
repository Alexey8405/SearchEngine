package searchengine.dto.statistics;

import lombok.Data;

@Data
public class StatisticsResponse {
    private boolean result;
    private StatisticsData statistics;

    public StatisticsResponse(boolean result, StatisticsData statistics) {
        this.result = result;
        this.statistics = statistics;
    }
}
