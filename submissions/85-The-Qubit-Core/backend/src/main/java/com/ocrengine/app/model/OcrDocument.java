package com.ocrengine.app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrDocument {
    private String documentId;
    private String originalFileName;
    private String fileType;
    private long fileSizeBytes;
    private LocalDateTime processedAt;
    private String rawExtractedText;
    private String executiveSummary;
    private List<ExtractedEntity> entities;
    private DocumentMetadata metadata;
    private ProcessingStatus status;
    private String errorMessage;

    public enum ProcessingStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
