package org.booklore.model.dto.response.perrypediaapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PerrypediaCategoryMembersResponse {
    private Query query;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Query {
        private List<CategoryMember> categorymembers;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryMember {
        private String title;
    }
}
