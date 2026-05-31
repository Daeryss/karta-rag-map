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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes sweep-level consolidated artefacts at the root of {@code experiments/<sweep-id>/}.
 * Per-config artefacts (queries.csv / ingest.csv / run.json) are already written by
 * {@link ResultsWriter} into per-config sub-directories — this writer adds the two
 * cross-config CSVs and a top-level {@code sweep.json}.
 *
 * <ul>
 *   <li>{@code sweep-configs.csv} — one row per config. Columns: experiment_id,
 *       chunk_label, chunks, embed_ms, ms_per_chunk, top1_accuracy, R@1, R@3, R@10,
 *       MRR, adv_precision, mean_rank_gap, query_ms_mean.</li>
 *   <li>{@code sweep-queries.csv} — one row per (config × query). Pivot-friendly for
 *       plotting "cosine vs config per query".</li>
 *   <li>{@code sweep.json} — environment snapshot + the list of experiment ids.</li>
 * </ul>
 */
public final class SweepWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SweepWriter() {}

    public static void write(Path sweepDir, String sweepId,
                             List<RunResult> results, EnvironmentSnapshot env) throws IOException {
        Files.createDirectories(sweepDir);
        writeConfigsCsv(sweepDir.resolve("sweep-configs.csv"), results);
        writeQueriesCsv(sweepDir.resolve("sweep-queries.csv"), results);
        writeSweepJson(sweepDir.resolve("sweep.json"), sweepId, results, env);
    }

    private static void writeConfigsCsv(Path path, List<RunResult> results) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(",",
                    "experiment_id", "chunk_label", "whole_file",
                    "chunk_chars", "overlap_chars",
                    "files_indexed", "files_skipped", "chunks", "embed_ms", "ms_per_chunk",
                    "top1_accuracy", "recall_at_1", "recall_at_3", "recall_at_10", "mrr",
                    "disambig_queries", "adv_precision", "mean_rank_gap", "query_ms_mean"));
            w.newLine();
            for (RunResult r : results) {
                int chunks = r.ingest().totalChunks();
                long embedMs = r.ingest().totalEmbedMs();
                int msPerChunk = chunks > 0 ? (int) (embedMs / chunks) : 0;
                w.write(String.join(",",
                        r.config().experimentId(),
                        r.config().chunkLabel(),
                        Boolean.toString(r.config().wholeFile()),
                        Integer.toString(r.config().chunkChars()),
                        Integer.toString(r.config().overlapChars()),
                        Integer.toString(r.ingest().filesIndexed()),
                        Integer.toString(r.ingest().filesSkipped()),
                        Integer.toString(chunks),
                        Long.toString(embedMs),
                        Integer.toString(msPerChunk),
                        fmt(r.aggregate().topOneAccuracy()),
                        fmt(r.aggregate().meanRecallAt1()),
                        fmt(r.aggregate().meanRecallAt3()),
                        fmt(r.aggregate().meanRecallAt10()),
                        fmt(r.aggregate().mrr()),
                        Integer.toString(r.aggregate().disambiguationQueries()),
                        fmt(r.aggregate().adversarialPrecision()),
                        fmt(r.aggregate().meanRankGap()),
                        Long.toString(r.aggregate().meanQueryLatencyMs())));
                w.newLine();
            }
        }
    }

    private static void writeQueriesCsv(Path path, List<RunResult> results) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(",",
                    "experiment_id", "chunk_label",
                    "query_id", "query_type", "has_adversarial",
                    "top1_file", "top1_cosine", "top1_hit",
                    "recall_at_1", "recall_at_3", "recall_at_10", "reciprocal_rank",
                    "adv_precision_pass", "adv_rank_gap"));
            w.newLine();
            for (RunResult r : results) {
                for (RunResult.QueryRow row : r.perQuery()) {
                    w.write(String.join(",",
                            r.config().experimentId(),
                            r.config().chunkLabel(),
                            row.queryId(),
                            row.queryType(),
                            Boolean.toString(row.hasAdversarial()),
                            csv(row.top1File()),
                            String.format(Locale.ROOT, "%.4f", row.top1Cosine()),
                            Boolean.toString(row.topOneHit()),
                            fmt(row.recallAt1()),
                            fmt(row.recallAt3()),
                            fmt(row.recallAt10()),
                            fmt(row.reciprocalRank()),
                            row.adversarialPrecisionPass() == null ? "" : Boolean.toString(row.adversarialPrecisionPass()),
                            row.adversarialRankGap() == null ? "" : sentinel(row.adversarialRankGap())));
                    w.newLine();
                }
            }
        }
    }

    private static void writeSweepJson(Path path, String sweepId,
                                       List<RunResult> results, EnvironmentSnapshot env) throws IOException {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("sweep_id", sweepId);
        top.put("environment", env);
        top.put("members", results.stream().map(r -> Map.of(
                "experiment_id", r.config().experimentId(),
                "chunk_label",   r.config().chunkLabel(),
                "topK",          r.config().topK()
        )).toList());
        JSON.writeValue(path.toFile(), top);
    }

    // ---- CSV helpers (mirrored from ResultsWriter to keep the writers independent) ----

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
}
