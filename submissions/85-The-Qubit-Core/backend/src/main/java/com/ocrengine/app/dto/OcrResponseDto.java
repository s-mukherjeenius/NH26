package com.ocrengine.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrResponseDto {
    private boolean success;
    private String documentId;
    private String fileName;
    private String fileType;
    private String rawText;
    private String executiveSummary;
    private List<EntityDto> entities;
    private MetadataDto metadata;
    private String errorMessage;
    private long processingTimeMs;

    // Edge Case flags
    private boolean nativePdf;           // #19 — native PDF skipped OCR
    private List<String> warnings;       // #2, #5, #6 — quality warnings
    private List<String> contradictions; // #33 — contradictory clauses
    private boolean hasContradictions;
    private boolean isEmpty;             // #32 — no content found
}
