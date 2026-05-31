package io.github.daeryss.karta.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.daeryss.karta.benchmark.RunResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link RunResult} into an {@code experiments/<exp-id>/} directory:
 *
 * <ul>
 *   <li>{@code queries.csv}      — one row per gold query with all per-query metrics</li>
 *   <li>{@code ingest.csv}       — one row per file with bytes, chunks, embed_ms, status</li>
 *   <li>{@code run.json}         — environment snapshot + aggregated metrics</li>
 * </ul>
 *
 * <p>CSV uses Locale.ROOT (period decimal separator) for portability across reviewers
 * in EU locales. No external CSV library — escaping is minimal and explicit.</p>
 */
public final class ResultsWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ResultsWriter() {}

    public static Path write(Path experimentsRoot, RunResult result, EnvironmentSnapshot env) throws IOException {
        Path dir = experimentsRoot.resolve(result.config().experimentId());
        Files.createDirectories(dir);

        writeQueriesCsv(dir.resolve("queries.csv"), result);
        writeIngestCsv(dir.resolve("ingest.csv"), result);
        writeRunJson(dir.resolve("run.json"), result, env);

        return dir;
    }

    private static void writeQueriesCsv(Path path, RunResult r) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(",",
                    "query_id", "query", "type", "has_adversarial",
                    "expected", "top1_file", "top1_cosine", "query_ms",
                    "top1_hit", "recall_at_1", "recall_at_3", "recall_at_10", "reciprocal_rank",
                    "adv_precision_pass", "adv_rank_gap", "adv_gap_by_type"));
            w.newLine();
            for (RunResult.QueryRow row : r.perQuery()) {
                w.write(String.join(",",
                        csv(row.queryId()),
                        csv(row.queryText()),
                        csv(row.queryType()),
                        Boolean.toString(row.hasAdversarial()),
                        csv(String.join("|", row.expectedFiles())),
                        csv(row.top1File()),
                        String.format(Locale.ROOT, "%.4f", row.top1Cosine()),
                        Long.toString(row.queryLatencyMs()),
                        Boolean.toString(row.topOneHit()),
                        fmt(row.recallAt1()),
                        fmt(row.recallAt3()),
                        fmt(row.recallAt10()),
                        fmt(row.reciprocalRank()),
                        row.adversarialPrecisionPass() == null ? "" : Boolean.toString(row.adversarialPrecisionPass()),
                        row.adversarialRankGap() == null ? "" : sentinel(row.adversarialRankGap()),
                        csv(gapByTypeToString(row.rankGapByType()))));
                w.newLine();
            }
        }
    }

    private static void writeIngestCsv(Path path, RunResult r) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(",", "rel_path", "bytes", "chunks", "embed_ms", "status"));
            w.newLine();
            for (RunResult.IngestFileStats fs : r.ingest().perFile()) {
                w.write(String.join(",",
                        csv(fs.relPath()),
                        Integer.toString(fs.bytes()),
                        Integer.toString(fs.chunksProduced()),
                        Long.toString(fs.embedMs()),
                        csv(fs.status())));
                w.newLine();
            }
        }
    }

    private static void writeRunJson(Path path, RunResult r, EnvironmentSnapshot env) throws IOException {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("environment", env);
        top.put("config", r.config());
        Map<String, Object> ingestSummary = new LinkedHashMap<>();
        ingestSummary.put("files_attempted", r.ingest().filesAttempted());
        ingestSummary.put("files_indexed",   r.ingest().filesIndexed());
        ingestSummary.put("files_skipped",   r.ingest().filesSkipped());
        ingestSummary.put("total_bytes",     r.ingest().totalBytes());
        ingestSummary.put("total_chunks",    r.ingest().totalChunks());
        ingestSummary.put("total_embed_ms",  r.ingest().totalEmbedMs());
        ingestSummary.put("vector_dim",      r.ingest().vectorDim());
        top.put("ingest", ingestSummary);
        top.put("aggregate", r.aggregate());
        JSON.writeValue(path.toFile(), top);
    }

    // ---- CSV helpers ----

    private static String csv(String raw) {
        if (raw == null) return "";
        boolean needsQuote = raw.contains(",") || raw.contains("\"") || raw.contains("\n") || raw.contains("\r");
        if (!needsQuote) return raw;
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "";
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String sentinel(int v) {
        if (v == Integer.MAX_VALUE) return "no_adv_retrieved";
        if (v == Integer.MIN_VALUE) return "expected_missing";
        return Integer.toString(v);
    }

    private static String gapByTypeToString(Map<?, Integer> gapByType) {
        if (gapByType == null || gapByType.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<?, Integer> e : gapByType.entrySet()) {
            if (!first) sb.append('|');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
