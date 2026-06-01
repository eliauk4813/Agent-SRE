package org.example.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.entity.DocumentStatus;

import java.time.LocalDateTime;

@Setter
@Getter
public class DocumentInfoRes {

    private String docId;
    private String fileName;
    private String filePath;
    private String contentHash;
    private Integer version;
    private DocumentStatus status;
    private Integer chunkCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
