package io.github.daeryss.karta.benchmark;

import io.github.daeryss.karta.query.AdversarialType;

import java.util.List;
import java.util.Map;

/**
 * The result of one {@link BenchmarkRunner} invocation. Captures per-query rows plus
 * aggregated metrics; the writer turns this into CSV + JSON for the
 * {@code experiments/<exp-id>/} directory.
 */
public record RunResult(
        BenchmarkConfig config,
        IngestStats ingest,
        List<QueryRow> perQuery,
        AggregateMetrics aggregate
) {

    /** Per-file ingest outcome. */
    public record IngestFileStats(
            String relPath,
            int bytes,
            int chunksProduced,
            long embedMs,
            String status // "ok" | "over_ctx" | "error:<msg>"
    ) {}

    /** Totals across all files for one config. */
    public record IngestStats(
            int filesAttempted,
            int filesIndexed,
            int filesSkipped,
            long totalBytes,
            int totalChunks,
            long totalEmbedMs,
            int vectorDim,
            List<IngestFileStats> perFile
    ) {}

    /** One row in the per-query results table. */
    public record QueryRow(
            String queryId,
            String queryText,
            String queryType,
            boolean hasAdversarial,
            List<String> expectedFiles,
            // Top-1 result (most common headline)
            String top1File,
            double top1Cosine,
            long queryLatencyMs,
            // Layer-1 metrics
            boolean topOneHit,
            double recallAt1,
            double recallAt3,
            double recallAt10,
            double reciprocalRank,
            // Disambiguation metrics — only meaningful when hasAdversarial; otherwise sentinels
            Boolean adversarialPrecisionPass,   // null when !hasAdversarial
            Integer adversarialRankGap,         // null when !hasAdversarial; MAX_VALUE = no adversarial in ranking; MIN_VALUE = expected missing
            Map<AdversarialType, Integer> rankGapByType  // empty when !hasAdversarial
    ) {}

    /** Aggregated metrics across the whole gold set. */
    public record AggregateMetrics(
            int queries,
            int disambiguationQueries,
            double meanRecallAt1,
            double meanRecallAt3,
            double meanRecallAt10,
            double mrr,
            double topOneAccuracy,
            // Disambiguation
            double adversarialPrecision,                // fraction of disambig queries where expected ranks above all adversarial
            double meanRankGap,                         // mean signed rank-gap across disambig queries with at least one adversarial in the ranking
            Map<AdversarialType, Double> meanRankGapByType,
            // Operational
            long meanQueryLatencyMs
    ) {}
}
