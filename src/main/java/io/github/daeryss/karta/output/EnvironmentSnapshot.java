package io.github.daeryss.karta.output;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Reproducibility snapshot per research-methodology.md §6. Captured at the moment a run
 * starts; written into {@code run.json} verbatim. Goal: any reader with comparable
 * hardware can re-run the experiment and compare numbers.
 */
public record EnvironmentSnapshot(
        @JsonProperty("timestamp_iso")    String timestampIso,
        @JsonProperty("methodology")      String methodologyVersion,
        @JsonProperty("karta_commit")     String kartaCommit,
        @JsonProperty("os")               String os,
        @JsonProperty("os_arch")          String osArch,
        @JsonProperty("jvm")              String jvm,
        @JsonProperty("hardware_label")   String hardwareLabel,   // human-set, e.g. "Apple M5 Pro, 48 GB RAM"
        @JsonProperty("ollama_version")   String ollamaVersion,
        @JsonProperty("embedding_model_digest") String embeddingModelDigest
) {

    /**
     * Best-effort capture. None of the lookups throw — missing data is recorded as
     * {@code "unknown"} so a run never fails to write its results because of an
     * environment probe glitch.
     */
    public static EnvironmentSnapshot capture(
            String methodologyVersion, String hardwareLabel,
            String ollamaUrl, String embeddingModel, Path repoRoot) {
        return new EnvironmentSnapshot(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)),
                methodologyVersion,
                gitHead(repoRoot),
                System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""),
                System.getProperty("os.arch", "unknown"),
                System.getProperty("java.vendor", "") + " " + System.getProperty("java.version", ""),
                hardwareLabel == null ? "unknown" : hardwareLabel,
                ollamaVersion(ollamaUrl),
                ollamaModelDigest(ollamaUrl, embeddingModel)
        );
    }

    private static String gitHead(Path repoRoot) {
        try {
            Path head = repoRoot.resolve(".git").resolve("HEAD");
            if (!Files.exists(head)) return "unknown";
            String first = Files.readString(head, StandardCharsets.UTF_8).trim();
            if (first.startsWith("ref:")) {
                Path refPath = repoRoot.resolve(".git").resolve(first.substring(4).trim());
                if (Files.exists(refPath)) return Files.readString(refPath, StandardCharsets.UTF_8).trim();
                // packed-refs fallback
                Path packed = repoRoot.resolve(".git").resolve("packed-refs");
                if (Files.exists(packed)) {
                    String ref = first.substring(4).trim();
                    try (var lines = Files.lines(packed, StandardCharsets.UTF_8)) {
                        return lines.filter(l -> l.endsWith(" " + ref))
                                .map(l -> l.substring(0, l.indexOf(' ')))
                                .findFirst().orElse("unknown");
                    }
                }
                return "unknown";
            }
            return first; // detached HEAD
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String ollamaVersion(String baseUrl) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/version"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            // crude extraction without pulling json — body is {"version":"x.y.z"}
            String body = r.body();
            int i = body.indexOf("\"version\"");
            if (i < 0) return "unknown";
            int start = body.indexOf('"', body.indexOf(':', i)) + 1;
            int end = body.indexOf('"', start);
            return start > 0 && end > start ? body.substring(start, end) : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String ollamaModelDigest(String baseUrl, String modelName) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<InputStream> r = c.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
                String body = br.readLine();
                StringBuilder sb = new StringBuilder();
                if (body != null) sb.append(body);
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String all = sb.toString();
                // Locate the chunk for our model and pull its digest field.
                int modelIdx = all.indexOf("\"" + modelName);
                if (modelIdx < 0) modelIdx = all.indexOf(modelName);
                if (modelIdx < 0) return "unknown";
                int digestIdx = all.indexOf("\"digest\"", modelIdx);
                if (digestIdx < 0) return "unknown";
                int colon = all.indexOf(':', digestIdx);
                int start = all.indexOf('"', colon) + 1;
                int end = all.indexOf('"', start);
                return start > 0 && end > start ? all.substring(start, end) : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }
}
