package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.HybridRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用混合 RAG（向量召回 + ES 关键词召回 + 融合排序）从内部知识库检索相关文档。
 */
@Component
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    private final HybridRetrievalService hybridRetrievalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InternalDocsTools(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含混合召回后的相关文档内容、分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It uses Hybrid RAG (vector retrieval + ES keyword retrieval + fusion ranking) to find the most relevant documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for")
            String query) {
        return queryInternalDocsWithRewrite(query, query, false, "tool-direct-query");
    }

    public String queryInternalDocsWithRewrite(String originalQuery,
                                               String retrievalQuery,
                                               boolean rewritten,
                                               String rewriteReason) {
        String original = safeText(originalQuery);
        String retrieval = safeText(retrievalQuery);

        if (retrieval.isEmpty()) {
            retrieval = original;
        }

        try {
            logger.info("queryInternalDocs 检索开始, originalQuery={}, retrievalQuery={}, rewritten={}",
                    original, retrieval, rewritten);

            List<HybridRetrievalService.HybridSearchResult> searchResults = hybridRetrievalService.search(retrieval);

            if (searchResults.isEmpty()) {
                Map<String, Object> noResults = new LinkedHashMap<>();
                noResults.put("status", "no_results");
                noResults.put("message", "No relevant documents found in the knowledge base.");
                noResults.put("originalQuery", original);
                noResults.put("retrievalQuery", retrieval);
                noResults.put("rewritten", rewritten);
                noResults.put("rewriteReason", rewriteReason);
                return objectMapper.writeValueAsString(noResults);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "success");
            payload.put("originalQuery", original);
            payload.put("retrievalQuery", retrieval);
            payload.put("rewritten", rewritten);
            payload.put("rewriteReason", rewriteReason);
            payload.put("results", searchResults);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", e.getMessage());
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
