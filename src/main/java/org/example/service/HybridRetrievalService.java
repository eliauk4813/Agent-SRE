package org.example.service;

import lombok.Getter;
import lombok.Setter;
import org.example.service.VectorSearchService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 混合召回服务。
 * 执行向量检索与 ES 关键词检索，先通过 RRF 融合，再按配置执行 rerank。
 */
@Service
public class HybridRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final VectorSearchService vectorSearchService;
    private final Bm25SearchService bm25SearchService;
    private final RerankService rerankService;

    @Value("${rag.vector-top-k:10}")
    private int vectorTopK;

    @Value("${rag.bm25-top-k:10}")
    private int bm25TopK;

    @Value("${rag.final-top-n:5}")
    private int finalTopN;

    @Value("${rag.rrf-k:60}")
    private int rrfK;

    @Value("${rag.rerank-enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank-candidate-top-k:12}")
    private int rerankCandidateTopK;

    @Value("${rag.rerank-final-top-n:4}")
    private int rerankFinalTopN;

    @Value("${rag.rerank-fail-open:true}")
    private boolean rerankFailOpen;

    public HybridRetrievalService(VectorSearchService vectorSearchService,
                                  Bm25SearchService bm25SearchService,
                                  RerankService rerankService) {
        this.vectorSearchService = vectorSearchService;
        this.bm25SearchService = bm25SearchService;
        this.rerankService = rerankService;
    }

    public List<HybridSearchResult> search(String query) {
        logger.info("开始混合召回与排序, query={}", query);

        List<SearchResult> vectorResults = vectorSearchService.searchSimilarDocuments(query, vectorTopK);
        List<Bm25SearchService.SearchHit> bm25Results = bm25SearchService.search(query, bm25TopK);

        Map<String, HybridSearchResult> merged = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorResults.size(); rank++) {
            SearchResult vectorResult = vectorResults.get(rank);
            String chunkId = buildChunkIdFromVectorResult(vectorResult);
            HybridSearchResult result = merged.computeIfAbsent(chunkId, key -> fromVectorResult(vectorResult));
            result.setVectorScore(vectorResult.getScore());
            result.setVectorRank(rank + 1);
            result.setRrfScore(result.getRrfScore() + reciprocalRank(rank + 1));
        }

        for (int rank = 0; rank < bm25Results.size(); rank++) {
            Bm25SearchService.SearchHit bm25Hit = bm25Results.get(rank);
            HybridSearchResult result = merged.computeIfAbsent(bm25Hit.getChunkId(), key -> fromEsHit(bm25Hit));
            result.setBm25Score(bm25Hit.getScore());
            result.setBm25Rank(rank + 1);
            result.setRrfScore(result.getRrfScore() + reciprocalRank(rank + 1));
            if (isBlank(result.getContent())) {
                result.setContent(bm25Hit.getContent());
            }
            if (isBlank(result.getTitle())) {
                result.setTitle(bm25Hit.getTitle());
            }
            if (isBlank(result.getMetadata())) {
                result.setMetadata(buildMetadataFromEs(bm25Hit));
            }
            if (isBlank(result.getId())) {
                result.setId(bm25Hit.getChunkId());
            }
        }

        List<HybridSearchResult> rrfResults = new ArrayList<>(merged.values());
        for (HybridSearchResult result : rrfResults) {
            result.setRerankScore(0D);
            result.setRerankRank(null);
            result.setFinalScore(result.getRrfScore());
            result.setRankingStage("rrf");
        }

        rrfResults = rrfResults.stream()
                .sorted(Comparator.comparingDouble(HybridSearchResult::getFinalScore).reversed())
                .collect(Collectors.toList());

        if (!rerankEnabled || rrfResults.isEmpty()) {
            return limitResults(rrfResults, finalTopN);
        }

        int candidateCount = Math.min(rerankCandidateTopK, rrfResults.size());
        int rerankTopN = Math.min(Math.max(rerankFinalTopN, 1), candidateCount);
        List<HybridSearchResult> rerankCandidates = new ArrayList<>(rrfResults.subList(0, candidateCount));

        long startTime = System.currentTimeMillis();
        try {
            List<HybridSearchResult> reranked = rerankService.rerank(query, rerankCandidates, rerankTopN);
            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("Rerank 完成, query={}, candidates={}, elapsedMs={}, fallback=false",
                    query, candidateCount, elapsed);
            return limitResults(reranked, rerankTopN);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.warn("Rerank 失败, query={}, candidates={}, elapsedMs={}, fallback={}",
                    query, candidateCount, elapsed, rerankFailOpen, e);
            if (!rerankFailOpen) {
                throw new RuntimeException("Rerank 执行失败: " + e.getMessage(), e);
            }
            return limitResults(rrfResults, rerankTopN);
        }
    }

    private List<HybridSearchResult> limitResults(List<HybridSearchResult> results, int topN) {
        return results.stream()
                .limit(Math.max(topN, 0))
                .collect(Collectors.toList());
    }

    private double reciprocalRank(int rank) {
        return 1.0 / (rrfK + rank);
    }

    private HybridSearchResult fromVectorResult(SearchResult result) {
        HybridSearchResult hybrid = new HybridSearchResult();
        hybrid.setId(result.getId());
        hybrid.setChunkId(buildChunkIdFromVectorResult(result));
        hybrid.setContent(result.getContent());
        hybrid.setMetadata(result.getMetadata());
        hybrid.setTitle(extractMetadataValue(result.getMetadata(), "title"));
        return hybrid;
    }

    private HybridSearchResult fromEsHit(Bm25SearchService.SearchHit hit) {
        HybridSearchResult hybrid = new HybridSearchResult();
        hybrid.setId(hit.getChunkId());
        hybrid.setChunkId(hit.getChunkId());
        hybrid.setTitle(hit.getTitle());
        hybrid.setContent(hit.getContent());
        hybrid.setMetadata(buildMetadataFromEs(hit));
        return hybrid;
    }

    private String buildChunkIdFromVectorResult(SearchResult result) {
        String chunkId = extractMetadataValue(result.getMetadata(), "chunk_id");
        if (!isBlank(chunkId)) {
            return chunkId;
        }
        String source = extractMetadataValue(result.getMetadata(), "_source");
        String chunkIndex = extractMetadataValue(result.getMetadata(), "chunkIndex");
        if (!isBlank(source) && !isBlank(chunkIndex)) {
            return source + "#" + chunkIndex;
        }
        return result.getId();
    }

    private String buildMetadataFromEs(Bm25SearchService.SearchHit hit) {
        return String.format("{\"doc_id\":\"%s\",\"chunk_id\":\"%s\",\"_source\":\"%s\",\"_file_name\":\"%s\",\"chunkIndex\":%d,\"title\":\"%s\"}",
                escapeJson(hit.getDocId()), escapeJson(hit.getChunkId()), escapeJson(hit.getSource()), escapeJson(hit.getFileName()), hit.getChunkIndex(), escapeJson(hit.getTitle()));
    }

    private String extractMetadataValue(String metadata, String key) {
        if (isBlank(metadata) || isBlank(key)) {
            return "";
        }
        String pattern = "\"" + key + "\":";
        int start = metadata.indexOf(pattern);
        if (start < 0) {
            return "";
        }
        int valueStart = start + pattern.length();
        while (valueStart < metadata.length() && Character.isWhitespace(metadata.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= metadata.length()) {
            return "";
        }
        if (metadata.charAt(valueStart) == '"') {
            int valueEnd = metadata.indexOf('"', valueStart + 1);
            if (valueEnd > valueStart) {
                return metadata.substring(valueStart + 1, valueEnd);
            }
        }
        int commaIndex = metadata.indexOf(',', valueStart);
        int braceIndex = metadata.indexOf('}', valueStart);
        int end = minPositive(commaIndex, braceIndex, metadata.length());
        return metadata.substring(valueStart, end).replaceAll("[\"}]", "").trim();
    }

    private int minPositive(int... values) {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            if (value >= 0 && value < min) {
                min = value;
            }
        }
        return min == Integer.MAX_VALUE ? values[values.length - 1] : min;
    }

    private String escapeJson(String value) {
        return defaultString(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String defaultString(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Setter
    @Getter
    public static class HybridSearchResult {
        private String id;
        private String chunkId;
        private String title;
        private String content;
        private String metadata;
        private double vectorScore;
        private Integer vectorRank;
        private double bm25Score;
        private Integer bm25Rank;
        private double rrfScore;
        private double rerankScore;
        private Integer rerankRank;
        private double finalScore;
        private String rankingStage;
    }
}
