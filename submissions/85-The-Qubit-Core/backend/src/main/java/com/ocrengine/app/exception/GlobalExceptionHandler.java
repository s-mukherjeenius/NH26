package com.ocrengine.app.exception;

import com.ocrengine.app.dto.ApiErrorDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OcrProcessingException.class)
    public ResponseEntity<ApiErrorDto> handleOcrProcessingException(
            OcrProcessingException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                ApiErrorDto.builder()
                        .status(422)
                        .error("OCR Processing Failed")
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now().toString())
                        .build()
        );
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<ApiErrorDto> handleUnsupportedFileType(
            UnsupportedFileTypeException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorDto.builder()
                        .status(400)
                        .error("Unsupported File Type")
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now().toString())
                        .build()
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorDto> handleMaxSizeException(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiErrorDto.builder()
                        .status(413)
                        .error("File Too Large")
                        .message("File size exceeds the maximum allowed limit of 20MB.")
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now().toString())
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorDto> handleGenericException(
            Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorDto.builder()
                        .status(500)
                        .error("Internal Server Error")
                        .message("An unexpected error occurred. Please try again.")
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now().toString())
                        .build()
        );
    }
}
