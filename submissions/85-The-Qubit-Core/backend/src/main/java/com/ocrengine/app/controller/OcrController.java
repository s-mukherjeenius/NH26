package com.ocrengine.app.controller;

import com.ocrengine.app.dto.OcrResponseDto;
import com.ocrengine.app.service.DocumentProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);
    private final DocumentProcessingService documentProcessingService;

    public OcrController(DocumentProcessingService documentProcessingService) {
        this.documentProcessingService = documentProcessingService;
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "OCR Enterprise Engine",
            "version", "1.0.0"
        ));
    }

    /**
     * Main OCR document processing endpoint.
     * Accepts PDF, PNG, JPG, JPEG, TIFF, BMP files up to 20MB.
     */
    @PostMapping(value = "/ocr/process",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OcrResponseDto> processDocument(
            @RequestPart("file") MultipartFile file) {

        log.info("Received document upload: name={} size={} type={}",
                file.getOriginalFilename(), file.getSize(), file.getContentType());

        OcrResponseDto response = documentProcessingService.processDocument(file);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns supported file types and constraints
     */
    @GetMapping("/ocr/info")
    public ResponseEntity<Map<String, Object>> getOcrInfo() {
        return ResponseEntity.ok(Map.of(
            "supportedFormats", new String[]{"PDF", "PNG", "JPG", "JPEG", "TIFF", "BMP"},
            "maxFileSizeMb", 20,
            "engine", "Tesseract OCR 5.x (LSTM)",
            "features", new String[]{
                "Text Extraction", "Entity Recognition",
                "Executive Summarization", "Multi-page PDF support"
            }
        ));
    }
}
