package com.ocrengine.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityDto {
    private String type;
    private String value;
    private String context;
    private double confidence;
    private String icon;
    private String color;
    private String label;      // human-readable label
    private boolean flagged;   // #16 — incomplete/ambiguous entity
}
