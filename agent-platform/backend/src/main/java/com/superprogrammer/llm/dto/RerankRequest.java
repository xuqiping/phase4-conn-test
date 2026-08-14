package com.superprogrammer.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankRequest {
    public static final String DEFAULT_INSTRUCT =
            "Given a web search query, retrieve relevant passages that answer the query.";

    private String model;
    private String query;
    private List<String> documents;
    private Integer topN;
    private String instruct;
}
