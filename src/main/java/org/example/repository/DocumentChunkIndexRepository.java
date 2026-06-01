package org.example.repository;

import org.example.entity.DocumentChunkIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentChunkIndexRepository extends JpaRepository<DocumentChunkIndex, Long> {

    List<DocumentChunkIndex> findByDocIdOrderByIdAsc(String docId);

    Optional<DocumentChunkIndex> findByChunkId(String chunkId);

    void deleteByDocId(String docId);

    void deleteByChunkIdIn(List<String> chunkIds);
}
