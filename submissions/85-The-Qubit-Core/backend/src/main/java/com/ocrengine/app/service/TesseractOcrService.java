package com.ocrengine.app.service;

import com.ocrengine.app.config.OcrConfig;
import com.ocrengine.app.exception.OcrProcessingException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Service
public class TesseractOcrService {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrService.class);

    private final OcrConfig                 ocrConfig;
    private final ImagePreprocessingService preprocessingService;

    public TesseractOcrService(OcrConfig ocrConfig,
                               ImagePreprocessingService preprocessingService) {
        this.ocrConfig            = ocrConfig;
        this.preprocessingService = preprocessingService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public OcrResult extractTextFromImage(File imageFile) {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        File toOcr = imageFile;
        double deskewAngle = 0;
        try {
            ImagePreprocessingService.PreprocessResult prep =
                    preprocessingService.preprocess(imageFile);
            toOcr = prep.processedFile;
            deskewAngle = prep.deskewAngle;

            if (prep.lowResolution)
                warnings.add("LOW_RESOLUTION: Image resolution below recommended 800px. Image was upscaled for better OCR.");
            if (prep.isDarkImage())
                warnings.add("DARK_IMAGE: Very dark image detected. Enhanced contrast applied.");
            if (prep.isOverexposedImage())
                warnings.add("OVEREXPOSED: Washed-out image detected. Processing applied.");
            if (prep.wasSkewed())
                warnings.add(String.format(
                    "DESKEWED: Document was rotated %.1f°. Auto-corrected for better OCR.", deskewAngle));
        } catch (Exception e) {
            log.warn("Preprocessing failed, using original: {}", e.getMessage());
        }

        String lang = detectLanguage(imageFile, toOcr);

        // Try PSM 3 (auto layout) first for standalone images — works better
        // for photos with mixed content, curved text, etc.
        String text = runTesseract(toOcr, lang, 3);

        // Quality check: if result is mostly garbage, retry with PSM 6 (single block)
        if (isLowQualityOcr(text)) {
            log.info("PSM 3 produced low-quality result, retrying with PSM 6...");
            String text2 = runTesseract(toOcr, lang, 6);
            if (countAlphanumericRatio(text2) > countAlphanumericRatio(text)) {
                text = text2;
                log.info("PSM 6 produced better result, using it.");
            }
        }

        long elapsed = System.currentTimeMillis() - start;

        return OcrResult.builder()
                .text(postProcessOcr(text))
                .pageCount(1).processingTimeMs(elapsed)
                .confidence(computeConfidence(text, warnings))
                .warnings(warnings).nativePdf(false).detectedLanguage(lang)
                .build();
    }

    public OcrResult extractTextFromPdf(File pdfFile) {
        long start = System.currentTimeMillis();

        PDDocument document;
        try {
            document = Loader.loadPDF(pdfFile);
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new OcrProcessingException(
                "This document is password-protected. Please remove the password and re-upload.");
        } catch (IOException e) {
            throw new OcrProcessingException(
                "The PDF file appears to be corrupt or malformed: " + e.getMessage());
        }

        try (document) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) throw new OcrProcessingException("The PDF has no pages.");

            int nativeCount = countNativePages(document);
            log.info("PDF [{}] pages={} native={}", pdfFile.getName(), pageCount, nativeCount);

            if (nativeCount == pageCount)
                return extractNativePdfText(document, pdfFile.getName(), pageCount, start);
            if (nativeCount == 0)
                return extractScannedPdfText(document, pdfFile.getName(), pageCount, start);
            return extractMixedPdfText(document, pdfFile.getName(), pageCount, start);

        } catch (OcrProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrProcessingException("PDF processing error: " + e.getMessage(), e);
        }
    }

    // ── PDF strategies ────────────────────────────────────────────────────────

    private OcrResult extractNativePdfText(PDDocument doc, String name,
                                            int pages, long start) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setAddMoreFormatting(false);
        stripper.setWordSeparator(" ");
        stripper.setLineSeparator("\n");
        stripper.setParagraphStart("\n");
        stripper.setParagraphEnd("\n");

        String raw = stripper.getText(doc);
        String text = postProcessNative(raw != null ? raw : "");
        long elapsed = System.currentTimeMillis() - start;

        log.info("Native PDF done [{}] in {}ms", name, elapsed);
        return OcrResult.builder()
                .text(text).pageCount(pages).processingTimeMs(elapsed)
                .confidence(98.0).warnings(List.of()).nativePdf(true)
                .detectedLanguage("native-text").build();
    }

    private OcrResult extractScannedPdfText(PDDocument doc, String name,
                                             int pages, long start) throws IOException {
        List<String> pageTexts = new ArrayList<>();
        List<String> warnings  = new ArrayList<>();
        PDFRenderer renderer   = new PDFRenderer(doc);

        for (int i = 0; i < pages; i++)
            pageTexts.add(ocrPageWithPreprocessing(renderer, i, warnings));

        pageTexts = removeRepeatedHeadersFooters(pageTexts);
        String fullText = String.join("\n\n", pageTexts).trim();
        long elapsed = System.currentTimeMillis() - start;

        return OcrResult.builder()
                .text(fullText).pageCount(pages).processingTimeMs(elapsed)
                .confidence(computeConfidence(fullText, warnings))
                .warnings(warnings).nativePdf(false)
                .detectedLanguage(ocrConfig.getLanguage()).build();
    }

    private OcrResult extractMixedPdfText(PDDocument doc, String name,
                                           int pages, long start) throws IOException {
        List<String> pageTexts = new ArrayList<>();
        List<String> warnings  = new ArrayList<>();
        PDFRenderer renderer   = new PDFRenderer(doc);
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        int nativePages = 0, ocrPages = 0;
        for (int i = 0; i < pages; i++) {
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);
            String native_ = stripper.getText(doc);
            int len = native_ == null ? 0 : native_.replaceAll("\\s+", "").length();

            if (len >= 50) {
                pageTexts.add(postProcessNative(native_));
                nativePages++;
            } else {
                pageTexts.add(ocrPageWithPreprocessing(renderer, i, warnings));
                ocrPages++;
            }
        }

        pageTexts = removeRepeatedHeadersFooters(pageTexts);
        warnings.add(0, String.format(
            "MIXED_PDF: %d page(s) used direct text extraction, %d page(s) used OCR.",
            nativePages, ocrPages));

        String fullText = String.join("\n\n", pageTexts).trim();
        long elapsed = System.currentTimeMillis() - start;

        return OcrResult.builder()
                .text(fullText).pageCount(pages).processingTimeMs(elapsed)
                .confidence(computeConfidence(fullText, warnings))
                .warnings(warnings).nativePdf(false)
                .detectedLanguage("mixed").build();
    }

    // ── Per-page OCR with full preprocessing ─────────────────────────────────

    private String ocrPageWithPreprocessing(PDFRenderer renderer, int pageIndex,
                                             List<String> warnings) {
        File tempPng = null;
        try {
            // Render at 300 DPI — this is the standard for OCR
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300, ImageType.RGB);
            tempPng = Files.createTempFile("raw_p" + pageIndex + "_", ".png").toFile();
            tempPng.deleteOnExit();
            ImageIO.write(image, "PNG", tempPng);

            ImagePreprocessingService.PreprocessResult prep =
                    preprocessingService.preprocess(tempPng);

            if (prep.wasSkewed())
                warnings.add(String.format("Page %d: deskewed %.1f°",
                        pageIndex + 1, prep.deskewAngle));

            String lang = detectLanguage(tempPng, prep.processedFile);

            // For PDF pages, try PSM 6 first (single block — good for uniform pages)
            String text = runTesseract(prep.processedFile, lang, 6);

            // Quality check: if poor, retry with PSM 3 (auto layout detection)
            if (isLowQualityOcr(text)) {
                log.info("Page {} PSM 6 produced low quality, retrying with PSM 3...", pageIndex + 1);
                String text2 = runTesseract(prep.processedFile, lang, 3);
                if (countAlphanumericRatio(text2) > countAlphanumericRatio(text)) {
                    text = text2;
                    warnings.add(String.format("Page %d: Used automatic layout detection for better results.",
                                               pageIndex + 1));
                }
            }

            return postProcessOcr(text != null ? text.trim() : "");

        } catch (Exception e) {
            log.warn("Page {} OCR failed: {}", pageIndex + 1, e.getMessage());
            warnings.add("Page " + (pageIndex + 1) + " could not be processed.");
            return "";
        } finally {
            if (tempPng != null) tempPng.delete();
        }
    }

    // ── OCR quality assessment ────────────────────────────────────────────────

    /**
     * Checks if OCR text is low quality by looking at the ratio of
     * alphanumeric characters to total characters, and checking for
     * excessive garbage symbols.
     */
    private boolean isLowQualityOcr(String text) {
        if (text == null || text.trim().length() < 20) return true;

        String stripped = text.replaceAll("\\s+", "");
        if (stripped.isEmpty()) return true;

        double alphaRatio = countAlphanumericRatio(text);
        if (alphaRatio < 0.5) return true;

        // Count garbage characters
        long garbage = stripped.chars()
                .filter(c -> "~%|#^&*@<>{}[]\\=+".indexOf(c) >= 0).count();
        double garbageRatio = (double) garbage / stripped.length();
        if (garbageRatio > 0.05) return true;

        // Check for excessive single-character "words" (sign of bad segmentation)
        String[] words = text.split("\\s+");
        if (words.length > 10) {
            long singleCharWords = java.util.Arrays.stream(words)
                    .filter(w -> w.length() == 1 && !w.matches("[aAI0-9]"))
                    .count();
            if ((double) singleCharWords / words.length > 0.3) return true;
        }

        return false;
    }

    private double countAlphanumericRatio(String text) {
        if (text == null || text.isEmpty()) return 0;
        String stripped = text.replaceAll("\\s+", "");
        if (stripped.isEmpty()) return 0;
        long alnum = stripped.chars()
                .filter(c -> Character.isLetterOrDigit(c) || c == '.' || c == ',' || c == '\'' || c == '"' || c == '-')
                .count();
        return (double) alnum / stripped.length();
    }

    private double computeConfidence(String text, List<String> warnings) {
        if (text == null || text.isBlank()) return 10.0;
        double ratio = countAlphanumericRatio(text);
        double base = ratio * 100;
        // Penalize for warnings
        for (String w : warnings) {
            if (w.startsWith("LOW_RESOLUTION")) base -= 10;
            if (w.startsWith("DARK_IMAGE")) base -= 5;
        }
        return Math.max(10, Math.min(98, base));
    }

    // ── Text post-processing ──────────────────────────────────────────────────

    /**
     * Post-process text from NATIVE PDFs (PDFTextStripper output).
     */
    private String postProcessNative(String text) {
        if (text == null || text.isBlank()) return "";

        String result = text;

        // Fix bullet/list characters from various font encodings
        result = result
            .replaceAll("\uF0B7", "•")
            .replaceAll("\uF0A7", "•")
            .replaceAll("\u00D8", "•")
            .replaceAll("\u00B7", "•")
            .replaceAll("\u2022", "•")
            .replaceAll("\u25CF", "•")
            .replaceAll("\u25E6", "◦")
            .replaceAll("\u2013", "–")
            .replaceAll("\u2014", "—")
            .replaceAll("\uFB01", "fi")
            .replaceAll("\uFB02", "fl")
            .replaceAll("\uFB03", "ffi")
            .replaceAll("\uFB04", "ffl")
            .replaceAll("[\u2018\u2019]", "'")
            .replaceAll("[\u201C\u201D]", "\"");

        // Fix justified text extra spaces
        result = result.replaceAll("(?<=\\S)  +(?=\\S)", " ");

        // Fix hyphenated line-breaks
        result = result.replaceAll("-(\\r?\\n)\\s*([a-z])", "$2");

        // Collapse 3+ consecutive blank lines
        result = result.replaceAll("(\r?\n){3,}", "\n\n");

        // Remove form-feed characters
        result = result.replace("\f", "\n\n");

        // Trim trailing whitespace per line
        StringBuilder sb = new StringBuilder();
        for (String line : result.split("\n")) {
            sb.append(line.stripTrailing()).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Post-process text from OCR (Tesseract output).
     * Fixes common OCR character substitution errors and cleans up
     * formatting artifacts from scanned documents and book photos.
     */
    private String postProcessOcr(String text) {
        if (text == null || text.isBlank()) return "";

        String result = text;

        // Fix common OCR character substitutions
        // O/0 confusion in numeric contexts
        result = result.replaceAll("(?<=[\\$₹€£,\\d])[O](?=[\\d,])", "0");

        // l/1 confusion in numeric contexts
        result = result.replaceAll("(?<=[\\d])[l](?=[\\d])", "1");

        // rn → m confusion (very common in OCR)
        // Only fix when it creates a non-word — be conservative
        // result = result.replaceAll("\\brn(?=[aeiou])", "m"); // Too risky

        // Fix common symbol garbling
        result = result
            .replaceAll("[\u2010\u2011\u2012\u2013\u2014\u2015]", "-")
            .replaceAll("[\u2018\u2019\u0060\u00B4]", "'")
            .replaceAll("[\u201C\u201D\u00AB\u00BB]", "\"")
            .replaceAll("\uFB01", "fi")
            .replaceAll("\uFB02", "fl")
            .replaceAll("\uFB03", "ffi")
            .replaceAll("\uFB04", "ffl");

        // Fix hyphenated line-breaks (word- \n continuation → merged word)
        result = result.replaceAll("-(\\r?\\n)\\s*([a-z])", "$2");

        // Fix pipe characters that should be 'l' or 'I' in word context
        result = result.replaceAll("(?<=[a-zA-Z])\\|(?=[a-zA-Z])", "l");

        // Collapse excessive spaces within lines
        result = result.replaceAll("(?<=\\S)  +(?=\\S)", " ");

        // Remove form-feed and other control characters
        result = result.replaceAll("[\\f\\x0B]", "\n");

        // Collapse 3+ blank lines to double
        result = result.replaceAll("(\\r?\\n){3,}", "\n\n");

        // Remove lines that are only garbage characters (noise lines)
        StringBuilder sb = new StringBuilder();
        for (String line : result.split("\n")) {
            String trimmed = line.stripTrailing();
            // Skip lines that are entirely non-alphanumeric (except blank lines)
            if (!trimmed.isEmpty()) {
                long alpha = trimmed.chars().filter(Character::isLetterOrDigit).count();
                double ratio = (double) alpha / trimmed.replaceAll("\\s", "").length();
                // Keep line only if >30% alphanumeric or it's very short (1-2 chars, could be page number)
                if (ratio > 0.3 || trimmed.replaceAll("\\s", "").length() <= 3) {
                    sb.append(trimmed).append("\n");
                }
            } else {
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    // ── Language detection ────────────────────────────────────────────────────

    private String detectLanguage(File original, File preprocessed) {
        String tessdataPath = ocrConfig.getTessdataPath();
        List<String> available = new ArrayList<>();
        available.add("eng");

        boolean hasBen = new File(tessdataPath, "ben.traineddata").exists();
        boolean hasHin = new File(tessdataPath, "hin.traineddata").exists();
        if (hasBen) available.add("ben");
        if (hasHin) available.add("hin");

        if (available.size() == 1) return "eng";

        try {
            String quickText = runTesseract(preprocessed, "eng", 6);
            if (quickText == null) return buildLangString(available);

            long garbage = quickText.chars()
                    .filter(c -> "~%|#^&*@<>{}[]\\".indexOf(c) >= 0).count();
            double garbageRatio = quickText.isEmpty() ? 0
                    : (double) garbage / quickText.length();

            if (garbageRatio > 0.08) return buildLangString(available);
        } catch (Exception e) {
            log.warn("Language detection probe failed: {}", e.getMessage());
        }
        return "eng";
    }

    private String buildLangString(List<String> langs) {
        return String.join("+", langs);
    }

    // ── Core Tesseract runner ─────────────────────────────────────────────────

    private String runTesseract(File imageFile, String language, int pageSegMode) {
        Tesseract t = new Tesseract();
        String tessdata = ocrConfig.getTessdataPath();
        t.setDatapath(tessdata);
        t.setLanguage(language);
        t.setOcrEngineMode(1);                         // LSTM only (best accuracy)
        t.setPageSegMode(pageSegMode);                 // Caller decides PSM
        t.setVariable("tessedit_do_invert", "0");      // Don't invert (we pre-binarize)
        t.setVariable("preserve_interword_spaces", "1"); // Keep word spacing
        t.setVariable("tessedit_char_blacklist", "|~`\\"); // Reduce common garbage chars

        // DO NOT set textord_heavy_nr — it strips legitimate thin text strokes

        try {
            return t.doOCR(imageFile);
        } catch (TesseractException e) {
            log.error("Tesseract error [{}]: {}", imageFile.getName(), e.getMessage());
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("tessdata")) {
                throw new OcrProcessingException(
                    "Tesseract language data not found at: " + tessdata +
                    ". Ensure eng.traineddata exists in the tessdata folder.");
            }
            throw new OcrProcessingException("OCR engine error: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countNativePages(PDDocument doc) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setSortByPosition(true);
        int count = 0;
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            s.setStartPage(i + 1); s.setEndPage(i + 1);
            String t = s.getText(doc);
            if (t != null && t.replaceAll("\\s+", "").length() >= 50) count++;
        }
        return count;
    }

    private List<String> removeRepeatedHeadersFooters(List<String> pages) {
        if (pages.size() <= 2) return pages;
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();
        for (String p : pages) {
            String[] lines = p.split("\n");
            if (lines.length > 0 && !lines[0].trim().isBlank())
                freq.merge(lines[0].trim(), 1, Integer::sum);
            if (lines.length > 1 && !lines[lines.length-1].trim().isBlank())
                freq.merge(lines[lines.length-1].trim(), 1, Integer::sum);
        }
        int thr = (int) Math.ceil(pages.size() * 0.6);
        java.util.Set<String> remove = new java.util.HashSet<>();
        for (var e : freq.entrySet()) if (e.getValue() >= thr) remove.add(e.getKey());
        if (remove.isEmpty()) return pages;

        List<String> cleaned = new ArrayList<>();
        for (String p : pages) {
            StringBuilder sb = new StringBuilder();
            for (String line : p.split("\n"))
                if (!remove.contains(line.trim())) sb.append(line).append("\n");
            cleaned.add(sb.toString().trim());
        }
        return cleaned;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OcrResult {
        private String text;
        private int pageCount;
        private long processingTimeMs;
        private double confidence;
        private List<String> warnings;
        private boolean nativePdf;
        private String detectedLanguage;
    }
}
