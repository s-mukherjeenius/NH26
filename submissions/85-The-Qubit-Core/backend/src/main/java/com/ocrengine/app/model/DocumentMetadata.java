package com.ocrengine.app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata {
    private int pageCount;
    private int wordCount;
    private int characterCount;
    private String detectedLanguage;
    private double ocrConfidence;
    private long processingTimeMs;
}
