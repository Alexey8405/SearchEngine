package searchengine.dto.statistics;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TotalStatistics {
    private int sites;
    private long pages;
    private long lemmas;
    private boolean indexing;
}
