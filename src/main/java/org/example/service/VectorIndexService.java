package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.example.dto.DocumentIndexingRes;
import org.example.entity.DocumentChunkIndex;
import org.example.entity.KnowledgeDocument;
import org.example.repository.DocumentChunkIndexRepository;
import org.example.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final DocumentChunkService chunkService;
    private final Bm25SearchService bm25SearchService;
    private final DocumentMetadataService documentMetadataService;
    private final DocumentChunkIndexRepository documentChunkIndexRepository;

    @Value("${file.upload.path}")
    private String uploadPath;

    public VectorIndexService(MilvusServiceClient milvusClient,
                              VectorEmbeddingService embeddingService,
                              DocumentChunkService chunkService,
                              Bm25SearchService bm25SearchService,
                              DocumentMetadataService documentMetadataService,
                              DocumentChunkIndexRepository documentChunkIndexRepository) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.chunkService = chunkService;
        this.bm25SearchService = bm25SearchService;
        this.documentMetadataService = documentMetadataService;
        this.documentChunkIndexRepository = documentChunkIndexRepository;
    }

    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());
        try {
            String targetPath = directoryPath != null && !directoryPath.trim().isEmpty() ? directoryPath : uploadPath;
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }
            result.setDirectoryPath(directory.getAbsolutePath());
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".txt") || name.endsWith(".md"));
            if (files == null || files.length == 0) {
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }
            result.setTotalFiles(files.length);
            for (File file : files) {
                try {
                    upsertDocument(file.getAbsolutePath());
                    result.incrementSuccessCount();
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("文件索引失败: {}", file.getName(), e);
                }
            }
            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    @Transactional
    public DocumentIndexingRes upsertDocument(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        String content = Files.readString(path);
        String normalizedPath = normalizePath(path);
        String contentHash = HashUtils.sha256(HashUtils.normalizeForHash(content));
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();

        KnowledgeDocument document = documentMetadataService.findByFilePath(normalizedPath)
                .orElseGet(() -> documentMetadataService.createNewDocument(fileName, normalizedPath, contentHash));
        document.setFileName(fileName);
        document.setFilePath(normalizedPath);

        if (contentHash.equals(document.getContentHash()) && Optional.ofNullable(document.getChunkCount()).orElse(0) > 0) {
            logger.info("文档内容未变化，跳过重建: docId={}, filePath={}", document.getDocId(), normalizedPath);
            return buildResult(document, true, "文档内容未变化，跳过重建");
        }

        documentMetadataService.markIndexing(document, contentHash);
        try {
            List<DocumentChunk> newChunks = chunkService.chunkDocument(content, normalizedPath);
            Map<String, ChunkCandidate> newChunkMap = buildChunkCandidates(document, newChunks);
            Map<String, DocumentChunkIndex> oldChunkMap = documentChunkIndexRepository.findByDocIdOrderByIdAsc(document.getDocId())
                    .stream().collect(Collectors.toMap(DocumentChunkIndex::getChunkId, item -> item, (a, b) -> a, LinkedHashMap::new));

            List<String> toDelete = oldChunkMap.keySet().stream().filter(id -> !newChunkMap.containsKey(id)).toList();
            for (String chunkId : toDelete) {
                deleteChunkFromMilvus(chunkId);
            }
            bm25SearchService.deleteByChunkIds(toDelete);
            if (!toDelete.isEmpty()) {
                documentChunkIndexRepository.deleteByChunkIdIn(toDelete);
            }

            for (ChunkCandidate candidate : newChunkMap.values()) {
                if (oldChunkMap.containsKey(candidate.chunkId())) {
                    continue;
                }
                List<Float> vector = embeddingService.generateEmbedding(candidate.chunk().getContent());
                insertToMilvus(candidate.chunkId(), candidate.chunk().getContent(), vector,
                        buildMetadata(document, candidate.chunk(), newChunks.size(), candidate.chunkId(), candidate.chunkHash()));
                bm25SearchService.upsertChunk(document.getDocId(), candidate.chunkId(), normalizedPath, fileName, candidate.chunk());
                saveChunkIndex(document.getDocId(), candidate);
            }

            boolean incrementVersion = Optional.ofNullable(document.getChunkCount()).orElse(0) > 0;
            document = documentMetadataService.markIndexed(document, contentHash, newChunks.size(), incrementVersion);
            return buildResult(document, false, "文档索引完成");
        } catch (Exception e) {
            documentMetadataService.markFailed(document);
            throw e;
        }
    }

    public DocumentIndexingRes reindexByDocId(String docId) throws Exception {
        KnowledgeDocument document = documentMetadataService.findByDocId(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
        return upsertDocument(document.getFilePath());
    }

    @Transactional
    public void deleteDocument(String docId) {
        KnowledgeDocument document = documentMetadataService.findByDocId(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
        deleteExistingDataByDocId(docId);
        bm25SearchService.deleteByDocId(docId);
        documentChunkIndexRepository.deleteByDocId(docId);
        documentMetadataService.deleteByDocId(docId);
        try {
            Path path = Paths.get(document.getFilePath()).normalize();
            if (Files.exists(path)) {
                Files.delete(path);
            }
        } catch (Exception e) {
            logger.warn("删除本地文件失败: {}", document.getFilePath(), e);
        }
    }

    public void indexSingleFile(String filePath) throws Exception {
        upsertDocument(filePath);
    }

    private Map<String, ChunkCandidate> buildChunkCandidates(KnowledgeDocument document, List<DocumentChunk> chunks) {
        Map<String, ChunkCandidate> result = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            String normalizedChunk = HashUtils.normalizeForHash(chunk.getContent());
            String chunkHash = HashUtils.sha256(normalizedChunk);
            String chunkId = HashUtils.sha256(document.getDocId() + ":" + chunkHash);
            result.put(chunkId, new ChunkCandidate(chunkId, chunkHash, chunk));
        }
        return result;
    }

    private void saveChunkIndex(String docId, ChunkCandidate candidate) {
        DocumentChunkIndex chunkIndex = new DocumentChunkIndex();
        chunkIndex.setChunkId(candidate.chunkId());
        chunkIndex.setDocId(docId);
        chunkIndex.setChunkHash(candidate.chunkHash());
        chunkIndex.setChunkIndex(candidate.chunk().getChunkIndex());
        chunkIndex.setTitle(candidate.chunk().getTitle());
        chunkIndex.setStartIndex(candidate.chunk().getStartIndex());
        chunkIndex.setEndIndex(candidate.chunk().getEndIndex());
        chunkIndex.setContentLength(candidate.chunk().getContent() == null ? 0 : candidate.chunk().getContent().length());
        documentChunkIndexRepository.save(chunkIndex);
    }

    private void deleteExistingDataByDocId(String docId) {
        try {
            String expr = String.format("metadata[\"doc_id\"] == \"%s\"", docId);
            deleteByExpr(expr, "docId=" + docId);
        } catch (Exception e) {
            logger.warn("按 docId 删除旧数据失败: {}", e.getMessage());
        }
    }

    private void deleteChunkFromMilvus(String chunkId) {
        try {
            String expr = String.format("id == \"%s\"", chunkId);
            deleteByExpr(expr, "chunkId=" + chunkId);
        } catch (Exception e) {
            logger.warn("删除 chunk 失败: {}", chunkId, e);
        }
    }

    private void deleteByExpr(String expr, String logIdentifier) {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .build()
        );
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            logger.warn("加载 collection 失败: {}", loadResponse.getMessage());
            return;
        }
        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withExpr(expr)
                .build();
        R<MutationResult> response = milvusClient.delete(deleteParam);
        if (response.getStatus() != 0) {
            logger.warn("删除数据时出现警告 [{}]: {}", logIdentifier, response.getMessage());
            return;
        }
        logger.info("Milvus 删除完成 [{}], 删除记录数: {}", logIdentifier, response.getData().getDeleteCnt());
    }

    private Map<String, Object> buildMetadata(KnowledgeDocument document,
                                              DocumentChunk chunk,
                                              int totalChunks,
                                              String chunkId,
                                              String chunkHash) {
        Map<String, Object> metadata = new HashMap<>();
        String fileName = document.getFileName() == null ? "" : document.getFileName();
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex);
        }
        metadata.put("doc_id", document.getDocId());
        metadata.put("chunk_id", chunkId);
        metadata.put("chunk_hash", chunkHash);
        metadata.put("_source", document.getFilePath());
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileName);
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        metadata.put("version", document.getVersion());
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        return metadata;
    }

    private void insertToMilvus(String chunkId, String content, List<Float> vector, Map<String, Object> metadata) throws Exception {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .build()
        );
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
        }
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(chunkId)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        JsonObject metadataJson = new Gson().toJsonTree(metadata).getAsJsonObject();
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFields(fields)
                .build();
        R<MutationResult> insertResponse = milvusClient.insert(insertParam);
        if (insertResponse.getStatus() != 0) {
            throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
        }
    }

    private String normalizePath(Path path) {
        return path.toString().replace(File.separator, "/");
    }

    private DocumentIndexingRes buildResult(KnowledgeDocument document, boolean skipped, String message) {
        DocumentIndexingRes res = new DocumentIndexingRes();
        res.setDocId(document.getDocId());
        res.setFileName(document.getFileName());
        res.setFilePath(document.getFilePath());
        res.setStatus(document.getStatus());
        res.setSkipped(skipped);
        res.setChunkCount(Optional.ofNullable(document.getChunkCount()).orElse(0));
        res.setVersion(Optional.ofNullable(document.getVersion()).orElse(1));
        res.setMessage(message);
        return res;
    }

    private record ChunkCandidate(String chunkId, String chunkHash, DocumentChunk chunk) {
    }

    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private final Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
