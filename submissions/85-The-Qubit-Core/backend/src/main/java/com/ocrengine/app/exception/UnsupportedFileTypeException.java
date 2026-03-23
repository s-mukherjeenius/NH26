package com.ocrengine.app.exception;

public class UnsupportedFileTypeException extends RuntimeException {
    public UnsupportedFileTypeException(String fileType) {
        super("Unsupported file type: " + fileType + ". Supported types: PDF, PNG, JPG, JPEG, TIFF, BMP");
    }
}
