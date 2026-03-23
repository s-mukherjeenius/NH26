package com.ocrengine.app.service;

import com.ocrengine.app.config.OcrConfig;
import com.ocrengine.app.dto.EntityDto;
import com.ocrengine.app.dto.MetadataDto;
import com.ocrengine.app.dto.OcrResponseDto;
import com.ocrengine.app.exception.OcrProcessingException;
import com.ocrengine.app.model.ExtractedEntity;
import com.ocrengine.app.util.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final TesseractOcrService         tesseractOcrService;
    private final EntityExtractionService     entityExtractionService;
    private final SummarizationService        summarizationService;
    private final OcrConfig                   ocrConfig;

    // Entity type → { icon, color, label, flagged }
    private static final Map<String, String[]> ENTITY_META = new LinkedHashMap<>();
    static {
        ENTITY_META.put("DUE_DATE",             new String[]{"calendar",     "#f59e0b", "Due Date",              "false"});
        ENTITY_META.put("SIGNING_DATE",          new String[]{"calendar-check","#6366f1","Signing Date",          "false"});
        ENTITY_META.put("DATE",                  new String[]{"clock",        "#8b5cf6", "Date",                  "false"});
        ENTITY_META.put("DATE_WRITTEN_FORM",     new String[]{"clock",        "#a78bfa", "Date (Written Form)",   "false"});
        ENTITY_META.put("TOTAL_AMOUNT",          new String[]{"dollar-sign",  "#10b981", "Financial Amount",      "false"});
        ENTITY_META.put("AMOUNT_IN_WORDS",       new String[]{"dollar-sign",  "#34d399", "Amount in Words",       "false"});
        ENTITY_META.put("DEDUCTION",             new String[]{"minus-circle", "#ef4444", "Deduction / TDS",       "false"});
        ENTITY_META.put("SIGNATORY",             new String[]{"user-check",   "#6366f1", "Signatory",             "false"});
        ENTITY_META.put("SIGNATORY_INCOMPLETE",  new String[]{"user-x",       "#f97316", "Signatory (Incomplete)","true" });
        ENTITY_META.put("ORGANIZATION",          new String[]{"building-2",   "#3b82f6", "Organization",          "false"});
        ENTITY_META.put("REFERENCE_NUMBER",      new String[]{"hash",         "#ec4899", "Reference No.",         "false"});
    }

    public DocumentProcessingService(TesseractOcrService tesseractOcrService,
                                     EntityExtractionService entityExtractionService,
                                     SummarizationService summarizationService,
                                     OcrConfig ocrConfig) {
        this.tesseractOcrService     = tesseractOcrService;
        this.entityExtractionService = entityExtractionService;
        this.summarizationService    = summarizationService;
        this.ocrConfig               = ocrConfig;
    }

    public OcrResponseDto processDocument(MultipartFile file) {
        long globalStart = System.currentTimeMillis();
        String documentId = FileValidator.generateDocumentId();
        String originalFileName = file.getOriginalFilename();

        log.info("Processing [{}] id={} size={}", originalFileName, documentId, file.getSize());

        // Validate — handles Edge Cases #23 (zero-byte), #22 (too large), #24 (wrong ext)
        FileValidator.validate(file);

        // Detect real MIME type from magic bytes (Edge Case #24)
        String detectedMime = FileValidator.detectMimeFromBytes(file);
        String effectiveMime = detectedMime != null ? detectedMime
                : (file.getContentType() != null ? file.getContentType() : "application/octet-stream");

        Path tempPath = null;
        try {
            String ext = getExtensionForMime(effectiveMime);
            tempPath = Files.createTempFile("ocr_" + documentId + "_", ext);
            file.transferTo(tempPath);
        } catch (Exception e) {
            throw new OcrProcessingException("Failed to save uploaded file: " + e.getMessage(), e);
        }

        File tempFile = tempPath.toFile();
        tempFile.deleteOnExit();

        try {
            TesseractOcrService.OcrResult ocrResult;

            if (FileValidator.isPdf(effectiveMime)) {
                ocrResult = tesseractOcrService.extractTextFromPdf(tempFile);
            } else {
                ocrResult = tesseractOcrService.extractTextFromImage(tempFile);
            }

            String rawText = ocrResult.getText();
            if (rawText == null || rawText.isBlank()) {
                rawText = "[No readable text could be extracted from this document. " +
                          "The image may be too low resolution, blank, or contain only graphical elements.]";
            }

            // Entity extraction (all Category 3 edge cases handled inside)
            List<ExtractedEntity> entities = entityExtractionService.extractEntities(rawText);

            // Summarization (all Category 6 edge cases handled inside)
            SummarizationService.SummarizationResult summResult =
                    summarizationService.summarize(rawText);

            // Metadata
            int wordCount = (int) Arrays.stream(rawText.split("\\s+"))
                    .filter(w -> !w.isBlank()).count();
            long totalTime = System.currentTimeMillis() - globalStart;

            MetadataDto metadata = MetadataDto.builder()
                    .pageCount(ocrResult.getPageCount())
                    .wordCount(wordCount)
                    .characterCount(rawText.length())
                    .ocrConfidencePercent(ocrResult.getConfidence())
                    .processingTimeMs(totalTime)
                    .fileSize(FileValidator.humanReadableFileSize(file.getSize()))
                    .build();

            // Collect all warnings
            List<String> allWarnings = new ArrayList<>(
                    ocrResult.getWarnings() != null ? ocrResult.getWarnings() : List.of());
            if (ocrResult.isNativePdf()) {
                allWarnings.add(0, "NATIVE_PDF: This is a digital PDF — text was extracted directly without OCR for maximum accuracy.");
            }

            List<EntityDto> entityDtos = entities.stream()
                    .map(this::toEntityDto)
                    .collect(Collectors.toList());

            log.info("Done [{}] in {}ms | {} words | {} entities | {} warnings | {} contradictions",
                    documentId, totalTime, wordCount, entities.size(),
                    allWarnings.size(), summResult.getContradictions().size());

            return OcrResponseDto.builder()
                    .success(true)
                    .documentId(documentId)
                    .fileName(originalFileName)
                    .fileType(effectiveMime)
                    .rawText(rawText)
                    .executiveSummary(summResult.getSummary())
                    .entities(entityDtos)
                    .metadata(metadata)
                    .processingTimeMs(totalTime)
                    .nativePdf(ocrResult.isNativePdf())
                    .warnings(allWarnings)
                    .contradictions(summResult.getContradictions())
                    .hasContradictions(!summResult.getContradictions().isEmpty())
                    .isEmpty(summResult.isEmpty())
                    .build();

        } finally {
            try { Files.deleteIfExists(tempPath); } catch (Exception ignored) {}
        }
    }

    private EntityDto toEntityDto(ExtractedEntity e) {
        String[] meta = ENTITY_META.getOrDefault(e.getType(),
                new String[]{"info", "#64748b", e.getType(), "false"});
        return EntityDto.builder()
                .type(e.getType())
                .value(e.getValue())
                .context(e.getContext())
                .confidence(e.getConfidence())
                .icon(meta[0])
                .color(meta[1])
                .label(meta[2])
                .flagged(Boolean.parseBoolean(meta[3]))
                .build();
    }

    private String getExtensionForMime(String mime) {
        return switch (mime) {
            case "application/pdf" -> ".pdf";
            case "image/png"       -> ".png";
            case "image/jpeg"      -> ".jpg";
            case "image/tiff"      -> ".tiff";
            case "image/bmp"       -> ".bmp";
            default                -> ".tmp";
        };
    }
}
