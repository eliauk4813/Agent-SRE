package org.example.repository;

import org.example.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByDocId(String docId);

    Optional<KnowledgeDocument> findByFilePath(String filePath);

    void deleteByDocId(String docId);
}
