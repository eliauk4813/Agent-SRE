package org.example.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.Getter;
import lombok.Setter;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class Bm25SearchService {

    private static final Logger logger = LoggerFactory.getLogger(Bm25SearchService.class);

    @Value("${es.host:localhost}")
    private String host;

    @Value("${es.port:9200}")
    private int port;

    @Value("${es.scheme:http}")
    private String scheme;

    @Value("${es.index-name:rag_chunks}")
    private String indexName;

    private RestClient restClient;
    private ElasticsearchTransport transport;
    private ElasticsearchClient esClient;

    @PostConstruct
    public void init() {
        try {
            restClient = RestClient.builder(new HttpHost(host, port, scheme)).build();
            transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            esClient = new ElasticsearchClient(transport);
            ensureIndex();
            logger.info("ES 检索服务初始化完成: {}://{}:{}, index={}", scheme, host, port, indexName);
        } catch (Exception e) {
            throw new RuntimeException("ES 初始化失败: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (transport != null) {
                transport.close();
            }
            if (restClient != null) {
                restClient.close();
            }
        } catch (IOException e) {
            logger.warn("关闭 ES 客户端失败", e);
        }
    }

    public void upsertChunk(String docId, String chunkId, String source, String fileName, DocumentChunk chunk) {
        try {
            ensureIndex();
            ChunkIndexDocument document = new ChunkIndexDocument();
            document.setDocId(defaultString(docId));
            document.setChunkId(defaultString(chunkId));
            document.setSource(defaultString(source));
            document.setFileName(defaultString(fileName));
            document.setChunkIndex(chunk.getChunkIndex());
            document.setTitle(defaultString(chunk.getTitle()));
            document.setContent(defaultString(chunk.getContent()));

            esClient.index(i -> i
                    .index(indexName)
                    .id(chunkId)
                    .document(document));
        } catch (Exception e) {
            logger.error("ES 写入 chunk 失败: docId={}, chunkId={}", docId, chunkId, e);
            throw new RuntimeException("ES 写入 chunk 失败: " + e.getMessage(), e);
        }
    }

    public void deleteByDocId(String docId) {
        try {
            if (defaultString(docId).isEmpty()) {
                return;
            }
            ensureIndex();
            Query query = Query.of(q -> q.term(t -> t.field("docId").value(docId)));
            esClient.deleteByQuery(DeleteByQueryRequest.of(d -> d.index(indexName).query(query)));
        } catch (Exception e) {
            logger.error("按 docId 删除 ES 索引失败: {}", docId, e);
            throw new RuntimeException("按 docId 删除 ES 索引失败: " + e.getMessage(), e);
        }
    }

    public void deleteByChunkIds(List<String> chunkIds) {
        try {
            if (chunkIds == null || chunkIds.isEmpty()) {
                return;
            }
            ensureIndex();
            Query query = Query.of(q -> q.terms(t -> t.field("chunkId")
                    .terms(v -> v.value(chunkIds.stream().map(id -> co.elastic.clients.elasticsearch._types.FieldValue.of(id)).toList()))));
            esClient.deleteByQuery(DeleteByQueryRequest.of(d -> d.index(indexName).query(query)));
        } catch (Exception e) {
            logger.error("按 chunkId 删除 ES 索引失败", e);
            throw new RuntimeException("按 chunkId 删除 ES 索引失败: " + e.getMessage(), e);
        }
    }

    public List<SearchHit> search(String queryText, int topK) {
        try {
            ensureIndex();
            SearchResponse<ChunkIndexDocument> response = esClient.search(s -> s
                    .index(indexName)
                    .size(topK)
                    .query(q -> q
                            .multiMatch(m -> m
                                    .query(defaultString(queryText))
                                    .fields("title^2", "content"))),
                    ChunkIndexDocument.class);

            List<SearchHit> hits = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                ChunkIndexDocument source = hit.source();
                if (source == null) {
                    return;
                }
                SearchHit result = new SearchHit();
                result.setDocId(source.getDocId());
                result.setChunkId(source.getChunkId());
                result.setSource(source.getSource());
                result.setFileName(source.getFileName());
                result.setChunkIndex(source.getChunkIndex());
                result.setTitle(source.getTitle());
                result.setContent(source.getContent());
                result.setScore(hit.score() == null ? 0F : hit.score().floatValue());
                hits.add(result);
            });
            return hits;
        } catch (Exception e) {
            logger.error("ES 检索失败, query={}", queryText, e);
            throw new RuntimeException("ES 检索失败: " + e.getMessage(), e);
        }
    }

    private void ensureIndex() throws IOException {
        boolean exists = esClient.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
        if (exists) {
            return;
        }
        esClient.indices().create(CreateIndexRequest.of(c -> c
                .index(indexName)
                .settings(IndexSettings.of(s -> s.numberOfShards("1").numberOfReplicas("0")))
                .mappings(m -> m.properties("docId", p -> p.keyword(k -> k))
                        .properties("chunkId", p -> p.keyword(k -> k))
                        .properties("source", p -> p.keyword(k -> k))
                        .properties("fileName", p -> p.keyword(k -> k))
                        .properties("chunkIndex", p -> p.integer(i -> i))
                        .properties("title", p -> p.text(t -> t))
                        .properties("content", p -> p.text(t -> t))));
    }

    private String defaultString(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    @Setter
    @Getter
    public static class SearchHit {
        private String docId;
        private String chunkId;
        private String source;
        private String fileName;
        private int chunkIndex;
        private String title;
        private String content;
        private float score;
    }

    @Setter
    @Getter
    public static class ChunkIndexDocument {
        private String docId;
        private String chunkId;
        private String source;
        private String fileName;
        private int chunkIndex;
        private String title;
        private String content;
    }
}
