package com.ocrengine.app.util;

import com.ocrengine.app.exception.OcrProcessingException;
import com.ocrengine.app.exception.UnsupportedFileTypeException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class FileValidator {

    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "application/pdf", "image/png", "image/jpeg",
            "image/jpg", "image/tiff", "image/bmp"
    );

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50MB

    // Magic bytes for true MIME detection (Edge Case #24 — wrong extension)
    private static final byte[] MAGIC_PDF  = {0x25, 0x50, 0x44, 0x46}; // %PDF
    private static final byte[] MAGIC_PNG  = {(byte)0x89, 0x50, 0x4E, 0x47}; // .PNG
    private static final byte[] MAGIC_JPG  = {(byte)0xFF, (byte)0xD8, (byte)0xFF}; // JPEG
    private static final byte[] MAGIC_TIFF_LE = {0x49, 0x49, 0x2A, 0x00}; // TIFF LE
    private static final byte[] MAGIC_TIFF_BE = {0x4D, 0x4D, 0x00, 0x2A}; // TIFF BE
    private static final byte[] MAGIC_BMP  = {0x42, 0x4D}; // BM

    public static void validate(MultipartFile file) {
        // Edge Case #23 — Zero-byte file
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new OcrProcessingException(
                "The uploaded file is empty (0 bytes). Please upload a valid document.");
        }

        // Edge Case #22 — File too large
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new OcrProcessingException(
                String.format("File size %.1f MB exceeds the 50 MB limit. " +
                    "For very large documents, please split the PDF into smaller parts.",
                    file.getSize() / (1024.0 * 1024)));
        }

        // Edge Case #24 — Detect real MIME type from magic bytes, not just extension
        String detectedType = detectMimeFromBytes(file);
        String declaredType = file.getContentType();

        if (detectedType == null) {
            throw new UnsupportedFileTypeException(declaredType != null ? declaredType : "unknown");
        }

        if (!ALLOWED_CONTENT_TYPES.contains(detectedType)) {
            throw new UnsupportedFileTypeException(detectedType);
        }

        // Warn but allow if declared type differs from detected (renamed file)
        if (declaredType != null && !declaredType.equals(detectedType)) {
            // Log mismatch but continue with detected type — handled upstream
        }
    }

    /**
     * Reads first 8 bytes to determine real file type regardless of extension.
     * Edge Case #24: A .jpg renamed to .pdf will be detected as JPEG.
     */
    public static String detectMimeFromBytes(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[8];
            int read = is.read(header);
            if (read < 2) return null;

            if (startsWith(header, MAGIC_PDF))     return "application/pdf";
            if (startsWith(header, MAGIC_PNG))     return "image/png";
            if (startsWith(header, MAGIC_JPG))     return "image/jpeg";
            if (startsWith(header, MAGIC_TIFF_LE)) return "image/tiff";
            if (startsWith(header, MAGIC_TIFF_BE)) return "image/tiff";
            if (startsWith(header, MAGIC_BMP))     return "image/bmp";

            return null; // Unknown / unsupported
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) return false;
        }
        return true;
    }

    public static String generateDocumentId() {
        return UUID.randomUUID().toString();
    }

    public static String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
    }

    public static boolean isPdf(String contentType) {
        return "application/pdf".equalsIgnoreCase(contentType);
    }

    public static boolean isImage(String contentType) {
        return contentType != null && contentType.startsWith("image/");
    }

    public static String humanReadableFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
