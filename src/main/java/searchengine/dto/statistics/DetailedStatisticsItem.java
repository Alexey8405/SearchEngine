package searchengine.dto.statistics;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Data
@AllArgsConstructor
public class DetailedStatisticsItem {
    private String url;
    private String name;
    private String status;
    private long statusTime;
    private String error;
    private int pages;
    private int lemmas;

    private String getFormattedStatusTime() {
        Instant instant = Instant.ofEpochSecond(statusTime);
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .format(instant.atZone(ZoneId.systemDefault()));
    }
}
