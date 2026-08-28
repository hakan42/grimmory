package org.booklore.model.dto.response.perrypediaapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PerrypediaQueryResponse {
    private Query query;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Query {
        private List<Redirect> redirects;
        private List<Page> pages;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Redirect {
        private String from;
        private String to;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {
        private String title;
        private boolean missing;
        private List<Revision> revisions;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Revision {
        private Slots slots;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Slots {
        private Main main;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Main {
        private String content;
    }
}
