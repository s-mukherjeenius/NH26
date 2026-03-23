package com.ocrengine.app.exception;

public class OcrProcessingException extends RuntimeException {
    private final String documentId;

    public OcrProcessingException(String message) {
        super(message);
        this.documentId = null;
    }

    public OcrProcessingException(String message, String documentId) {
        super(message);
        this.documentId = documentId;
    }

    public OcrProcessingException(String message, Throwable cause) {
        super(message, cause);
        this.documentId = null;
    }

    public String getDocumentId() { return documentId; }
}
