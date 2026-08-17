package com.cc.opsagent.knowledge.application;

public record KnowledgeQuery(String query, int topK) {

    public static final int MAX_TOP_K = 20;

    public KnowledgeQuery {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        query = query.trim();
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
    }
}
