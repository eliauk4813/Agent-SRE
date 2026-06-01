package org.example.service;

import org.example.entity.DocumentStatus;
import org.example.entity.KnowledgeDocument;
import org.example.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DocumentMetadataService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    public DocumentMetadataService(KnowledgeDocumentRepository knowledgeDocumentRepository) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
    }

    public Optional<KnowledgeDocument> findByDocId(String docId) {
        return knowledgeDocumentRepository.findByDocId(docId);
    }

    public Optional<KnowledgeDocument> findByFilePath(String filePath) {
        return knowledgeDocumentRepository.findByFilePath(filePath);
    }

    public List<KnowledgeDocument> listAll() {
        return knowledgeDocumentRepository.findAll();
    }

    public KnowledgeDocument save(KnowledgeDocument document) {
        return knowledgeDocumentRepository.save(document);
    }

    public KnowledgeDocument createNewDocument(String fileName, String filePath, String contentHash) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setFileName(fileName);
        document.setFilePath(filePath);
        document.setContentHash(contentHash);
        document.setStatus(DocumentStatus.UPLOADED);
        document.setChunkCount(0);
        document.setVersion(1);
        return knowledgeDocumentRepository.save(document);
    }

    public KnowledgeDocument markIndexing(KnowledgeDocument document, String contentHash) {
        document.setStatus(DocumentStatus.INDEXING);
        document.setContentHash(contentHash);
        return knowledgeDocumentRepository.save(document);
    }

    public KnowledgeDocument markIndexed(KnowledgeDocument document, String contentHash, int chunkCount, boolean incrementVersion) {
        document.setStatus(DocumentStatus.INDEXED);
        document.setContentHash(contentHash);
        document.setChunkCount(chunkCount);
        if (incrementVersion) {
            document.setVersion(document.getVersion() == null ? 1 : document.getVersion() + 1);
        }
        return knowledgeDocumentRepository.save(document);
    }

    public KnowledgeDocument markFailed(KnowledgeDocument document) {
        document.setStatus(DocumentStatus.FAILED);
        return knowledgeDocumentRepository.save(document);
    }

    public void deleteByDocId(String docId) {
        knowledgeDocumentRepository.deleteByDocId(docId);
    }
}
