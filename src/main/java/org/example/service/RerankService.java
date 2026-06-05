package org.example.service;

import java.util.List;

/**
 * 候选文档重排序服务。
 */
public interface RerankService {

    List<HybridRetrievalService.HybridSearchResult> rerank(String query,
                                                           List<HybridRetrievalService.HybridSearchResult> candidates,
                                                           int topN);
}
