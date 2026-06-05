package org.example.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通过 DashScope HTTP API 执行 rerank。
 */
@Service
public class DashScopeRerankService implements RerankService {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeRerankService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${spring.ai.dashscope.api-key:${dashscope.api.key:}}")
    private String apiKey;

    @Value("${rag.rerank-model:qwen3-rerank}")
    private String rerankModel;

    @Value("${rag.rerank-instruct:}")
    private String rerankInstruct;

    @Value("${rag.rerank-timeout-ms:5000}")
    private long rerankTimeoutMs;

    @Value("${rag.rerank-api-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String rerankApiUrl;

    public DashScopeRerankService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
    }

    @Override
    public List<HybridRetrievalService.HybridSearchResult> rerank(String query,
                                                                  List<HybridRetrievalService.HybridSearchResult> candidates,
                                                                  int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (isBlank(apiKey)) {
            throw new IllegalStateException("DashScope API Key 未配置，无法执行 rerank");
        }

        int expectedCount = Math.min(Math.max(topN, 1), candidates.size());
        try {
            String requestBody = objectMapper.writeValueAsString(buildRequestBody(query, candidates, expectedCount));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rerankApiUrl))
                    .timeout(Duration.ofMillis(rerankTimeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            logger.debug("调用 DashScope rerank, model={}, candidates={}", rerankModel, candidates.size());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DashScope rerank 调用失败, status=" + response.statusCode() + ", body=" + response.body());
            }

            RerankResponse rerankResponse = objectMapper.readValue(response.body(), RerankResponse.class);
            return applyRerankResponse(candidates, rerankResponse, expectedCount);
        } catch (IOException e) {
            throw new RuntimeException("解析 DashScope rerank 响应失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DashScope rerank 调用被中断", e);
        }
    }

    private Map<String, Object> buildRequestBody(String query,
                                                 List<HybridRetrievalService.HybridSearchResult> candidates,
                                                 int topN) {
        List<String> documents = candidates.stream()
                .map(this::buildDocumentText)
                .toList();

        if ("qwen3-rerank".equalsIgnoreCase(rerankModel)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", rerankModel);
            payload.put("query", defaultString(query));
            payload.put("documents", documents);
            payload.put("top_n", topN);
            if (!isBlank(rerankInstruct)) {
                payload.put("instruct", rerankInstruct);
            }
            return payload;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", rerankModel);
        payload.put("input", Map.of(
                "query", defaultString(query),
                "documents", documents
        ));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("top_n", topN);
        if (!isBlank(rerankInstruct)) {
            parameters.put("instruct", rerankInstruct);
        }
        payload.put("parameters", parameters);
        return payload;
    }

    private String buildDocumentText(HybridRetrievalService.HybridSearchResult candidate) {
        String title = defaultString(candidate.getTitle());
        String content = defaultString(candidate.getContent());
        if (title.isEmpty()) {
            return content;
        }
        if (content.isEmpty()) {
            return title;
        }
        return title + "\n" + content;
    }

    private List<HybridRetrievalService.HybridSearchResult> applyRerankResponse(
            List<HybridRetrievalService.HybridSearchResult> candidates,
            RerankResponse rerankResponse,
            int expectedCount) {
        if (rerankResponse == null || rerankResponse.output == null || rerankResponse.output.results == null
                || rerankResponse.output.results.isEmpty()) {
            throw new IllegalStateException("DashScope rerank 返回空结果");
        }

        List<RerankResultItem> items = rerankResponse.output.results;
        if (items.size() < expectedCount) {
            throw new IllegalStateException("DashScope rerank 返回结果不完整, expected=" + expectedCount + ", actual=" + items.size());
        }

        List<HybridRetrievalService.HybridSearchResult> reranked = new ArrayList<>(expectedCount);
        Set<Integer> seenIndices = new HashSet<>();
        for (int rank = 0; rank < expectedCount; rank++) {
            RerankResultItem item = items.get(rank);
            if (item == null || item.index == null || item.relevanceScore == null) {
                throw new IllegalStateException("DashScope rerank 返回了非法结果项");
            }
            if (item.index < 0 || item.index >= candidates.size()) {
                throw new IllegalStateException("DashScope rerank 返回了越界候选索引: " + item.index);
            }
            if (!seenIndices.add(item.index)) {
                throw new IllegalStateException("DashScope rerank 返回了重复候选索引: " + item.index);
            }

            HybridRetrievalService.HybridSearchResult candidate = candidates.get(item.index);
            candidate.setRerankScore(item.relevanceScore);
            candidate.setRerankRank(rank + 1);
            candidate.setFinalScore(item.relevanceScore);
            candidate.setRankingStage("rerank");
            reranked.add(candidate);
        }
        return reranked;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RerankResponse {
        @JsonProperty("output")
        private RerankOutput output;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RerankOutput {
        @JsonProperty("results")
        private List<RerankResultItem> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RerankResultItem {
        @JsonProperty("index")
        private Integer index;

        @JsonProperty("relevance_score")
        private Double relevanceScore;
    }
}
