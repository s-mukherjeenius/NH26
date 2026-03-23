package com.ocrengine.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataDto {
    private int pageCount;
    private int wordCount;
    private int characterCount;
    private double ocrConfidencePercent;
    private long processingTimeMs;
    private String fileSize;
}
