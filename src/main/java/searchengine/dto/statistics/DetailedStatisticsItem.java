package searchengine.dto.statistics;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@AllArgsConstructor
public class DetailedStatisticsItem {
    private String url;
    private String name;
    private String status;
    private LocalDateTime statusTime;
    private String error;
    private int pages;
    private int lemmas;

    public String getFormattedStatusTime() {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                .format(statusTime);
    }
}