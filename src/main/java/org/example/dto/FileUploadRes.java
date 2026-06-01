package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FileUploadRes {

    private String docId;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private boolean skipped;
    private Integer version;
    private Integer chunkCount;
    private String status;

}
