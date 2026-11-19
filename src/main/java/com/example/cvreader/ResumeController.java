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

        public ResumeController(ResumeExtractionService service) {
                this.service = service;
        }

        @PostMapping("/parse-resume")
        public ResponseEntity<?> parseResume(
                        @RequestParam(value = "resume", required = false) MultipartFile file) {

                if (file == null || file.isEmpty()) {
                        return error(HttpStatus.BAD_REQUEST, "FILE_MISSING",
                                        "Attach a file under the \"resume\" field.", null);
                }

                String ext = ResumeExtractionService.getExtension(file.getOriginalFilename());
                if (!ALLOWED.contains(ext)) {
                        return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                                        "Unsupported file type: " + ext, null);
                }

                try {
                        return ResponseEntity.ok(
                                        service.extractResumeData(file.getBytes(), file.getOriginalFilename()));

                } catch (ResumeProcessingException e) {
                        e.printStackTrace();
                        return error(HttpStatus.UNPROCESSABLE_CONTENT, "RESUME_PROCESSING_ERROR",
                                        e.getMessage(), rootCause(e));

                } catch (HttpClientErrorException e) {
                        String body = e.getResponseBodyAsString();
                        System.err.println("OpenAI status: " + e.getStatusCode());
                        System.err.println("OpenAI error: " + body);
                        return error(HttpStatus.valueOf(e.getStatusCode().value()), "OPENAI_CLIENT_ERROR",
                                        "OpenAI rejected the request (" + e.getStatusCode().value() + ").", body);

                } catch (HttpServerErrorException e) {
                        String body = e.getResponseBodyAsString();
                        System.err.println("OpenAI server error: " + body);
                        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_SERVER_ERROR",
                                        "OpenAI had a server error (" + e.getStatusCode().value() + ").", body);

                } catch (ResourceAccessException e) {
                        e.printStackTrace();
                        return error(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_CONNECTION_ERROR",
                                        "Could not connect to OpenAI.", rootCause(e));

                } catch (Exception e) {

                        e.printStackTrace();
                        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                        e.getClass().getName() + ": " + rootCause(e));
                }

        }

        private static String rootCause(Throwable e) {
                Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                return root.getClass().getSimpleName() + ": " + root.getMessage();
        }

        private static ResponseEntity<Map<String, Object>> error(
                        HttpStatus status, String code, String message, String details) {
                Map<String, Object> body = new LinkedHashMap<>(); // allows null-free optional fields
                body.put("success", false);
                body.put("error", code);
                body.put("message", message);
                if (details != null && !details.isBlank()) {
                        body.put("details", details);
                }
                return ResponseEntity.status(status).body(body);
        }
}