package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.DocumentIndexingRes;
import org.example.dto.DocumentInfoRes;
import org.example.entity.KnowledgeDocument;
import org.example.service.DocumentMetadataService;
import org.example.service.VectorIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentMetadataService documentMetadataService;
    private final VectorIndexService vectorIndexService;

    public DocumentController(DocumentMetadataService documentMetadataService,
                              VectorIndexService vectorIndexService) {
        this.documentMetadataService = documentMetadataService;
        this.vectorIndexService = vectorIndexService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentInfoRes>>> listDocuments() {
        List<DocumentInfoRes> documents = documentMetadataService.listAll()
                .stream()
                .map(this::toRes)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(documents));
    }

    @GetMapping("/{docId}")
    public ResponseEntity<ApiResponse<DocumentInfoRes>> getDocument(@PathVariable String docId) {
        return documentMetadataService.findByDocId(docId)
                .map(document -> ResponseEntity.ok(ApiResponse.success(toRes(document))))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error(404, "文档不存在")));
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<ApiResponse<String>> deleteDocument(@PathVariable String docId) {
        try {
            vectorIndexService.deleteDocument(docId);
            return ResponseEntity.ok(ApiResponse.success("文档删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(500, "删除文档失败: " + e.getMessage()));
        }
    }

    @PostMapping("/{docId}/reindex")
    public ResponseEntity<ApiResponse<DocumentIndexingRes>> reindexDocument(@PathVariable String docId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(vectorIndexService.reindexByDocId(docId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(500, "重建索引失败: " + e.getMessage()));
        }
    }

    private DocumentInfoRes toRes(KnowledgeDocument document) {
        DocumentInfoRes res = new DocumentInfoRes();
        res.setDocId(document.getDocId());
        res.setFileName(document.getFileName());
        res.setFilePath(document.getFilePath());
        res.setContentHash(document.getContentHash());
        res.setVersion(document.getVersion());
        res.setStatus(document.getStatus());
        res.setChunkCount(document.getChunkCount());
        res.setCreatedAt(document.getCreatedAt());
        res.setUpdatedAt(document.getUpdatedAt());
        return res;
    }
}
