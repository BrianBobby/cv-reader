package com.example.cvreader;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "FILE_TOO_LARGE",
                "message", "File too large (max 20MB)."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> multipart(MultipartException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "INVALID_UPLOAD",
                "message", String.valueOf(e.getMessage())));
    }
}