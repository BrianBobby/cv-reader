
package com.example.cvreader;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatusCode;

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
                        return ResponseEntity.badRequest()
                                        .body(Map.of("error",
                                                        "Attach a file under the \"resume\" field."));
                }

                String ext = ResumeExtractionService.getExtension(
                                file.getOriginalFilename());

                if (!ALLOWED.contains(ext)) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of("error",
                                                        "Unsupported file type: " + ext));
                }

                try {
                        return ResponseEntity.ok(
                                        service.extractResumeData(
                                                        file.getBytes(),
                                                        file.getOriginalFilename()));

                } catch (ResumeProcessingException e) {

                        return ResponseEntity.status(HttpStatusCode.valueOf(422))
                                        .body(Map.of("error", e.getMessage()));

                } catch (HttpClientErrorException e) {

                        System.err.println("OpenAI status: " + e.getStatusCode());
                        System.err.println("OpenAI error: " + e.getResponseBodyAsString());

                        return ResponseEntity.status(e.getStatusCode())
                                        .body(Map.of("error", "OpenAI API request failed."));

                } catch (HttpServerErrorException e) {

                        return ResponseEntity.status(503)
                                        .body(Map.of("error",
                                                        "OpenAI service is temporarily unavailable."));

                } catch (ResourceAccessException e) {

                        return ResponseEntity.status(503)
                                        .body(Map.of("error",
                                                        "Could not connect to OpenAI."));

                } catch (Exception e) {

                        e.printStackTrace();

                        return ResponseEntity.internalServerError()
                                        .body(Map.of("error",
                                                        "An unexpected server error occurred."));
                }
        }
}
