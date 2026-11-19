package com.example.cvreader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ResumeExtractionService {

    private static final Map<String, String> IMAGE_TYPES = Map.of(
            ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".png", "image/png",
            ".gif", "image/gif", ".webp", "image/webp");

    private static final Set<String> TEXT_EXTS = Set.of(".docx", ".txt");

    private static final Map<String, String> FILE_TYPES = Map.of(
            ".pdf", "application/pdf", ".doc", "application/msword");

    private static final List<String> FIELDS = List.of(
            "First Name", "Middle Name", "Last Name", "Gender", "Marital Status",
            "Date of Birth", "Nationality", "Passport No", "Visa Status", "Visa Expiry",
            "Notice Period", "Contact No", "Address", "Email", "Current Salary",
            "UAE Driving License", "Other Driving License", "Highest Qualification",
            "Other Qualification", "Certifications", "Years of Experience",
            "Computer Skills", "Technical Skills", "Sector", "Keywords",
            "Experiences", "Language", "Mother Tongue");

    private static final Set<String> ARRAY_FIELDS = Set.of(
            "Certifications", "Computer Skills", "Other Qualification",
            "Technical Skills", "Keywords", "Language");

    private static final List<String> EXPERIENCE_FIELDS = List.of(
            "Company Name", "Date of join", "Position",
            "Responsibilities", "Reason for leaving", "Salary");

    private static final Pattern EMPTY_VALUES = Pattern.compile("^(null|n/a|na|not found|none|unknown)$",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final String prompt;

    public ResumeExtractionService(ObjectMapper mapper,
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model}") String model) throws IOException {
        this.mapper = mapper;
        this.restClient = RestClient.builder().baseUrl("https://api.openai.com").build();
        this.apiKey = apiKey;
        this.model = model;
        this.prompt = new ClassPathResource("prompts/extraction-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public Map<String, Object> extractResumeData(byte[] bytes, String originalName) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set. Add it to your .env file.");
        }

        String ext = getExtension(originalName);
        List<Map<String, Object>> content = new ArrayList<>();

        if (IMAGE_TYPES.containsKey(ext)) {
            content.add(Map.of("type", "image_url",
                    "image_url", Map.of("url", dataUrl(IMAGE_TYPES.get(ext), bytes))));

        } else if (TEXT_EXTS.contains(ext)) {
            String text = extractLocalText(bytes, ext);
            content.add(Map.of("type", "text", "text",
                    "Extract candidate information from this resume:\n" + text));

        } else {
            String mime = FILE_TYPES.getOrDefault(ext, "application/octet-stream");
            String filename = (originalName == null || originalName.isBlank()) ? "resume" + ext : originalName;
            content.add(Map.of("type", "file",
                    "file", Map.of("filename", filename, "file_data", dataUrl(mime, bytes))));
        }

        List<Map<String, Object>> messages = List.of(
                Map.of(
                        "role", "system",
                        "content", prompt),
                Map.of(
                        "role", "user",
                        "content", content));

        Map<String, Object> body = Map.of(
                "model", model,
                "max_completion_tokens", 4000,
                "messages", messages);

        JsonNode response = restClient.post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String raw = response == null ? ""
                : response.path("choices").path(0).path("message").path("content").asString("");
        if (raw.isBlank()) {
            throw new ResumeProcessingException(
                    "The AI model did not return a text response.");
        }

        JsonNode parsed;
        try {
            parsed = mapper.readTree(stripToJson(raw));
        } catch (Exception e) {
            throw new ResumeProcessingException(
                    "AI response was not valid JSON: " + e.getMessage(), e);
        }
        return normalize(parsed);
    }

    // ---------- helpers ----------

    private static String dataUrl(String mime, byte[] bytes) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    public static String getExtension(String filename) {
        if (filename == null)
            return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }

    private String extractLocalText(byte[] bytes, String ext) throws IOException {
        if (ext.equals(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                    XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                String text = extractor.getText().trim();
                if (text.isEmpty())
                    throw new ResumeProcessingException(
                            "Could not extract any text from the .docx file.");
                return text;
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty())
            throw new ResumeProcessingException(
                    "The .txt file appears to be empty.");
        return text;
    }

    private String stripToJson(String rawText) {
        String cleaned = rawText.replaceAll("(?i)```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new ResumeProcessingException("AI response did not contain a JSON object. Raw start: "
                    + rawText.substring(0, Math.min(200, rawText.length())));
        }
        return cleaned.substring(start, end + 1);
    }

    private String cleanText(String val) {
        if (val == null)
            return "";
        String text = val.trim();
        return (text.isEmpty() || EMPTY_VALUES.matcher(text).matches()) ? "" : text;
    }

    private String toFlatString(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull())
            return "";
        if (node.isArray() || node.isObject()) {
            List<String> parts = new ArrayList<>();
            node.forEach(child -> {
                String s = toFlatString(child);
                if (!s.isEmpty())
                    parts.add(s);
            });
            return String.join(", ", parts);
        }
        return cleanText(node.asString());
    }

    private List<String> toStringArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()
                || (node.isString() && node.asString().isEmpty())) {
            return result;
        }
        if (node.isArray()) {
            node.forEach(child -> {
                String s = toFlatString(child);
                if (!s.isEmpty())
                    result.add(s);
            });
        } else {
            String s = toFlatString(node);
            if (!s.isEmpty())
                result.add(s);
        }
        return result;
    }

    private List<Map<String, String>> normalizeExperiences(JsonNode node) {
        List<Map<String, String>> result = new ArrayList<>();
        if (node == null || !node.isArray())
            return result;
        for (JsonNode item : node) {
            if (!item.isObject())
                continue;
            Map<String, String> exp = new LinkedHashMap<>();
            for (String field : EXPERIENCE_FIELDS) {
                exp.put(field, toFlatString(item.get(field)));
            }
            result.add(exp);
        }
        return result;
    }

    private Map<String, Object> normalize(JsonNode raw) {
        Map<String, Object> result = new LinkedHashMap<>(); // keeps field order
        for (String field : FIELDS) {
            JsonNode value = raw.get(field);
            if (field.equals("Experiences")) {
                result.put(field, normalizeExperiences(value));
            } else if (ARRAY_FIELDS.contains(field)) {
                result.put(field, toStringArray(value));
            } else {
                result.put(field, toFlatString(value));
            }
        }
        return result;
    }
}