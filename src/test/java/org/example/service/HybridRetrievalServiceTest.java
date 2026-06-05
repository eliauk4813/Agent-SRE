package org.example.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    private VectorSearchService vectorSearchService;
    private Bm25SearchService bm25SearchService;
    private RerankService rerankService;
    private HybridRetrievalService hybridRetrievalService;

    @BeforeEach
    void setUp() {
        vectorSearchService = mock(VectorSearchService.class);
        bm25SearchService = mock(Bm25SearchService.class);
        rerankService = mock(RerankService.class);
        hybridRetrievalService = new HybridRetrievalService(vectorSearchService, bm25SearchService, rerankService);

        ReflectionTestUtils.setField(hybridRetrievalService, "vectorTopK", 10);
        ReflectionTestUtils.setField(hybridRetrievalService, "bm25TopK", 10);
        ReflectionTestUtils.setField(hybridRetrievalService, "finalTopN", 4);
        ReflectionTestUtils.setField(hybridRetrievalService, "rrfK", 60);
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankEnabled", true);
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankCandidateTopK", 12);
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankFinalTopN", 4);
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankFailOpen", true);
    }

    @Test
    void shouldMergeDuplicateChunkAndUseRrfWhenRerankDisabled() {
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankEnabled", false);

        when(vectorSearchService.searchSimilarDocuments(eq("cpu 告警"), anyInt()))
                .thenReturn(List.of(vectorResult("chunk-1", 0.91f), vectorResult("chunk-2", 0.50f)));
        when(bm25SearchService.search(eq("cpu 告警"), anyInt()))
                .thenReturn(List.of(bm25Hit("chunk-1", 10.0f), bm25Hit("chunk-3", 8.0f)));

        List<HybridRetrievalService.HybridSearchResult> results = hybridRetrievalService.search("cpu 告警");

        assertEquals(3, results.size());
        assertEquals("chunk-1", results.get(0).getChunkId());
        assertEquals("rrf", results.get(0).getRankingStage());
        verify(rerankService, never()).rerank(eq("cpu 告警"), anyList(), anyInt());
    }

    @Test
    void shouldUseRerankOrderWhenRerankEnabled() {
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankFinalTopN", 2);

        when(vectorSearchService.searchSimilarDocuments(eq("登录失败"), anyInt()))
                .thenReturn(List.of(vectorResult("chunk-1", 0.95f), vectorResult("chunk-2", 0.90f)));
        when(bm25SearchService.search(eq("登录失败"), anyInt()))
                .thenReturn(List.of(bm25Hit("chunk-1", 11.0f), bm25Hit("chunk-2", 9.0f)));

        HybridRetrievalService.HybridSearchResult second = hybridResult("chunk-2", 0.99, 1, "rerank");
        HybridRetrievalService.HybridSearchResult first = hybridResult("chunk-1", 0.80, 2, "rerank");

        when(rerankService.rerank(eq("登录失败"), anyList(), eq(2)))
                .thenReturn(List.of(second, first));

        List<HybridRetrievalService.HybridSearchResult> results = hybridRetrievalService.search("登录失败");

        assertEquals(2, results.size());
        assertEquals("chunk-2", results.get(0).getChunkId());
        assertEquals("rerank", results.get(0).getRankingStage());
        assertEquals(0.99, results.get(0).getFinalScore());
    }

    @Test
    void shouldFallbackToRrfWhenRerankFailsAndFailOpenEnabled() {
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankFinalTopN", 2);

        when(vectorSearchService.searchSimilarDocuments(eq("接口超时"), anyInt()))
                .thenReturn(List.of(vectorResult("chunk-1", 0.95f), vectorResult("chunk-2", 0.90f)));
        when(bm25SearchService.search(eq("接口超时"), anyInt()))
                .thenReturn(List.of(bm25Hit("chunk-1", 11.0f), bm25Hit("chunk-2", 9.0f)));
        when(rerankService.rerank(eq("接口超时"), anyList(), eq(2)))
                .thenThrow(new RuntimeException("timeout"));

        List<HybridRetrievalService.HybridSearchResult> results = hybridRetrievalService.search("接口超时");

        assertEquals(2, results.size());
        assertEquals("chunk-1", results.get(0).getChunkId());
        assertEquals("rrf", results.get(0).getRankingStage());
    }

    @Test
    void shouldThrowWhenRerankFailsAndFailOpenDisabled() {
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankFinalTopN", 2);
        ReflectionTestUtils.setField(hybridRetrievalService, "rerankFailOpen", false);

        when(vectorSearchService.searchSimilarDocuments(eq("磁盘告警"), anyInt()))
                .thenReturn(List.of(vectorResult("chunk-1", 0.95f), vectorResult("chunk-2", 0.90f)));
        when(bm25SearchService.search(eq("磁盘告警"), anyInt()))
                .thenReturn(List.of(bm25Hit("chunk-1", 11.0f), bm25Hit("chunk-2", 9.0f)));
        when(rerankService.rerank(eq("磁盘告警"), anyList(), eq(2)))
                .thenThrow(new RuntimeException("api error"));

        assertThrows(RuntimeException.class, () -> hybridRetrievalService.search("磁盘告警"));
    }

    private VectorSearchService.SearchResult vectorResult(String chunkId, float score) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(chunkId);
        result.setScore(score);
        result.setContent("vector content " + chunkId);
        result.setMetadata("{\"chunk_id\":\"" + chunkId + "\",\"title\":\"title-" + chunkId + "\"}");
        return result;
    }

    private Bm25SearchService.SearchHit bm25Hit(String chunkId, float score) {
        Bm25SearchService.SearchHit hit = new Bm25SearchService.SearchHit();
        hit.setChunkId(chunkId);
        hit.setDocId("doc-" + chunkId);
        hit.setSource("/tmp/" + chunkId + ".md");
        hit.setFileName(chunkId + ".md");
        hit.setChunkIndex(0);
        hit.setTitle("title-" + chunkId);
        hit.setContent("bm25 content " + chunkId);
        hit.setScore(score);
        return hit;
    }

    private HybridRetrievalService.HybridSearchResult hybridResult(String chunkId,
                                                                   double score,
                                                                   int rank,
                                                                   String stage) {
        HybridRetrievalService.HybridSearchResult result = new HybridRetrievalService.HybridSearchResult();
        result.setChunkId(chunkId);
        result.setId(chunkId);
        result.setContent("content " + chunkId);
        result.setRerankScore(score);
        result.setRerankRank(rank);
        result.setFinalScore(score);
        result.setRankingStage(stage);
        return result;
    }
}
