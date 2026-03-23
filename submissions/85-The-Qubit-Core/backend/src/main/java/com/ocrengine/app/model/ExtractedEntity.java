package com.ocrengine.app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedEntity {
    private String type;       // DATE, AMOUNT, ORGANIZATION, PERSON, SIGNATORY
    private String value;
    private String context;
    private double confidence;
}
