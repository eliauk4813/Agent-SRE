package org.example.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.entity.DocumentStatus;

@Setter
@Getter
public class DocumentIndexingRes {

    private String docId;
    private String fileName;
    private String filePath;
    private DocumentStatus status;
    private boolean skipped;
    private int chunkCount;
    private int version;
    private String message;
}
