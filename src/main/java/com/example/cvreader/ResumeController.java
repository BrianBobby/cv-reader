package com.example.cvreader;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ResumeController {

        private static final Set<String> ALLOWED = Set.of(
                        ".pdf", ".doc", ".docx", ".jpg", ".jpeg",
                        ".png", ".gif", ".webp", ".txt");

        private final ResumeExtractionService service;
        private final LookupMatchService lookupService;

        public ResumeController(ResumeExtractionService service, LookupMatchService lookupService) {
                this.service = service;
                this.lookupService = lookupService;
        }

        @PostMapping("/parse-resume")
        public ResponseEntity<?> parseResume(
                        @RequestParam(value = "resume", required = false) MultipartFile file) {

                if (file == null || file.isEmpty()) {
                        return error(HttpStatus.BAD_REQUEST, "FILE_MISSING",
                                        "No file was provided. Attach the file using the \"resume\" field.",
                                        null);
                }

                String ext = ResumeExtractionService.getExtension(file.getOriginalFilename());
                if (!ALLOWED.contains(ext)) {
                        return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                                        "Unsupported file type: " + ext, null);
                }

                try {
                        return ResponseEntity.ok(
                                        service.extractResumeData(file.getBytes(), file.getOriginalFilename()));
                } catch (Exception e) {
                        return handleException(e);
                }
        }

        @PostMapping("/match-code")
        public ResponseEntity<?> matchCode(
                        @RequestParam(value = "cvJson", required = false) MultipartFile cvJson,
                        @RequestParam(value = "lookup", required = false) MultipartFile lookup,
                        @RequestParam(value = "title", required = false) String title) {

                if (cvJson == null || cvJson.isEmpty()) {
                        return error(HttpStatus.BAD_REQUEST, "FILE_MISSING",
                                        "Attach the resume JSON file using the \"cvJson\" field.", null);
                }
                if (lookup == null || lookup.isEmpty()) {
                        return error(HttpStatus.BAD_REQUEST, "FILE_MISSING",
                                        "Attach the lookup CSV (CODE,DESCRIPTION, no header) using the \"lookup\" field.",
                                        null);
                }
                if (title == null || title.isBlank()) {
                        return error(HttpStatus.BAD_REQUEST, "TITLE_MISSING",
                                        "The \"title\" field is required.", null);
                }

                try {
                        return ResponseEntity.ok(lookupService.match(
                                        cvJson.getBytes(), title.trim(), lookup.getBytes()));
                } catch (Exception e) {
                        return handleException(e);
                }
        }

        // ---------- shared error handling ----------

        private ResponseEntity<?> handleException(Exception e) {
                if (e instanceof IllegalArgumentException) {
                        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage(), null);

                } else if (e instanceof ResumeProcessingException) {
                        e.printStackTrace();
                        return error(HttpStatus.UNPROCESSABLE_CONTENT, "RESUME_PROCESSING_ERROR",
                                        e.getMessage(), rootCause(e));

                } else if (e instanceof HttpClientErrorException ce) {
                        String body = ce.getResponseBodyAsString();
                        System.err.println("OpenAI status: " + ce.getStatusCode());
                        System.err.println("OpenAI error: " + body);
                        return error(HttpStatus.valueOf(ce.getStatusCode().value()), "OPENAI_CLIENT_ERROR",
                                        "OpenAI rejected the request (" + ce.getStatusCode().value() + ").", body);

                } else if (e instanceof HttpServerErrorException se) {
                        String body = se.getResponseBodyAsString();
                        System.err.println("OpenAI server error: " + body);
                        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_SERVER_ERROR",
                                        "OpenAI had a server error (" + se.getStatusCode().value() + ").", body);

                } else if (e instanceof ResourceAccessException) {
                        e.printStackTrace();
                        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_CONNECTION_ERROR",
                                        "Could not connect to OpenAI.", rootCause(e));
                }

                e.printStackTrace();
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                e.getClass().getName() + ": " + rootCause(e));
        }

        private static String rootCause(Throwable e) {
                Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                return root.getClass().getSimpleName() + ": " + root.getMessage();
        }

        private static ResponseEntity<Map<String, Object>> error(
                        HttpStatus status, String code, String message, String details) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("error", code);
                body.put("message", message);
                if (details != null && !details.isBlank()) {
                        body.put("details", details);
                }
                return ResponseEntity.status(status).body(body);
        }
}