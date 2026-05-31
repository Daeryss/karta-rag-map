package io.github.daeryss.karta.benchmark;

import dev.langchain4j.community.rag.content.retriever.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import io.github.daeryss.karta.query.AdversarialType;
import io.github.daeryss.karta.query.GoldQuery;
import io.github.daeryss.karta.query.GoldQuerySet;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.daeryss.karta.benchmark.Metrics.adversarialPrecisionPasses;
import static io.github.daeryss.karta.benchmark.Metrics.rankGap;
import static io.github.daeryss.karta.benchmark.Metrics.rankGapByType;
import static io.github.daeryss.karta.benchmark.Metrics.reciprocalRank;
import static io.github.daeryss.karta.benchmark.Metrics.recallAtK;
import static io.github.daeryss.karta.benchmark.Metrics.topOneHit;

/**
 * Parameterised harness. Takes a {@link BenchmarkConfig}, a resolved corpus
 * (relative-path → text), and a {@link GoldQuerySet}; produces a {@link RunResult}.
 *
 * <p>This class is intentionally state-less between runs: caller creates one
 * runner per config and discards. The vector store is per-run (in-memory Lucene
 * via {@code ByteBuffersDirectory}) so configs do not contaminate each other.</p>
 *
 * <p>Path identity convention: {@code expected} and {@code adversarial} entries in
 * the gold set MUST use the same path format as the corpus map keys (i.e. the
 * relative path that {@link io.github.daeryss.karta.corpus.CorpusLoader} produces).
 * Metric computation matches on those strings exactly.</p>
 */
public final class BenchmarkRunner {

    private final BenchmarkConfig config;

    public BenchmarkRunner(BenchmarkConfig config) {
        this.config = config;
    }

    public RunResult run(Map<String, String> corpus, GoldQuerySet goldSet) {
        EmbeddingModel embedder = OllamaEmbeddingModel.builder()
                .baseUrl(config.ollamaUrl())
                .modelName(config.embeddingModel())
                .timeout(Duration.ofSeconds(300))
                .build();

        LuceneEmbeddingStore store = LuceneEmbeddingStore.builder()
                .directory(new ByteBuffersDirectory())
                .build();

        RunResult.IngestStats ingest = ingest(corpus, embedder, store);
        List<RunResult.QueryRow> perQuery = runQueries(goldSet, embedder, store);
        RunResult.AggregateMetrics agg = aggregate(perQuery);

        return new RunResult(config, ingest, perQuery, agg);
    }

    // ---- ingestion ----

    private RunResult.IngestStats ingest(
            Map<String, String> corpus, EmbeddingModel embedder, LuceneEmbeddingStore store) {

        List<RunResult.IngestFileStats> perFile = new ArrayList<>(corpus.size());
        int indexed = 0, skipped = 0, totalChunks = 0;
        long totalBytes = 0, totalEmbedMs = 0;
        int vectorDim = -1;

        DocumentSplitter splitter = config.wholeFile()
                ? null
                : DocumentSplitters.recursive(config.chunkChars(), config.overlapChars());

        for (Map.Entry<String, String> e : corpus.entrySet()) {
            String relPath = e.getKey();
            String text = e.getValue();
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            totalBytes += bytes;

            String name = relPath.substring(relPath.lastIndexOf('/') + 1);
            Metadata md = Metadata.from(Map.of("path", relPath, "name", name));

            List<TextSegment> segments;
            if (config.wholeFile()) {
                segments = List.of(TextSegment.from(text, md));
            } else {
                Document doc = Document.from(text, md);
                segments = splitter.split(doc);
                // Re-attach our metadata to each segment so query results can resolve back to file.
                List<TextSegment> tagged = new ArrayList<>(segments.size());
                for (TextSegment s : segments) tagged.add(TextSegment.from(s.text(), md));
                segments = tagged;
            }

            long t0 = System.nanoTime();
            try {
                List<Embedding> embs = embedder.embedAll(segments).content();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalEmbedMs += ms;
                store.addAll(embs, segments);
                indexed++;
                totalChunks += segments.size();
                if (vectorDim < 0 && !embs.isEmpty()) vectorDim = embs.get(0).dimension();
                perFile.add(new RunResult.IngestFileStats(relPath, bytes, segments.size(), ms, "ok"));
            } catch (InvalidRequestException ex) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalEmbedMs += ms;
                skipped++;
                perFile.add(new RunResult.IngestFileStats(relPath, bytes, 0, ms, "over_ctx"));
            } catch (RuntimeException ex) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalEmbedMs += ms;
                skipped++;
                String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                perFile.add(new RunResult.IngestFileStats(relPath, bytes, 0, ms, "error:" + truncate(msg)));
            }
        }

        return new RunResult.IngestStats(
                corpus.size(), indexed, skipped, totalBytes, totalChunks, totalEmbedMs, vectorDim, perFile);
    }

    // ---- queries ----

    private List<RunResult.QueryRow> runQueries(
            GoldQuerySet goldSet, EmbeddingModel embedder, LuceneEmbeddingStore store) {

        List<RunResult.QueryRow> rows = new ArrayList<>(goldSet.queries().size());

        for (GoldQuery q : goldSet.queries()) {
            long t0 = System.nanoTime();
            Embedding qe = embedder.embed(q.query()).content();
            EmbeddingSearchResult<TextSegment> res = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(qe)
                            .maxResults(config.topK())
                            .build());
            long ms = (System.nanoTime() - t0) / 1_000_000;

            List<EmbeddingMatch<TextSegment>> matches = res.matches();
            // Collapse to a deduplicated, score-ordered list of relative paths.
            List<String> rankedFiles = dedupOrdered(matches);
            String top1File = rankedFiles.isEmpty() ? "(none)" : rankedFiles.get(0);
            double top1Cosine = matches.isEmpty() ? 0.0 : matches.get(0).score();

            Set<String> expected = new HashSet<>(q.expected());
            boolean t1 = topOneHit(rankedFiles, expected);
            double r1 = recallAtK(rankedFiles, expected, 1);
            double r3 = recallAtK(rankedFiles, expected, 3);
            double r10 = recallAtK(rankedFiles, expected, 10);
            double rr = reciprocalRank(rankedFiles, expected);

            Boolean advPrecision = null;
            Integer advGap = null;
            Map<AdversarialType, Integer> gapByType = Map.of();
            if (q.hasAdversarial()) {
                List<GoldQuery.Adversarial> adv = q.adversarialOrEmpty();
                advPrecision = adversarialPrecisionPasses(rankedFiles, expected, adv);
                advGap = rankGap(rankedFiles, expected, adv);
                gapByType = rankGapByType(rankedFiles, expected, adv);
            }

            rows.add(new RunResult.QueryRow(
                    q.id(), q.query(), q.type().name(), q.hasAdversarial(),
                    q.expected(),
                    top1File, top1Cosine, ms,
                    t1, r1, r3, r10, rr,
                    advPrecision, advGap, gapByType));
        }

        return rows;
    }

    // ---- aggregation ----

    private RunResult.AggregateMetrics aggregate(List<RunResult.QueryRow> rows) {
        List<Double> r1 = new ArrayList<>(), r3 = new ArrayList<>(), r10 = new ArrayList<>();
        List<Double> rr = new ArrayList<>();
        List<Boolean> top1 = new ArrayList<>();
        long totalQueryMs = 0;

        List<Boolean> advPrec = new ArrayList<>();
        List<Integer> advGaps = new ArrayList<>();
        Map<AdversarialType, List<Integer>> gapsByType = new EnumMap<>(AdversarialType.class);

        for (RunResult.QueryRow row : rows) {
            r1.add(row.recallAt1());
            r3.add(row.recallAt3());
            r10.add(row.recallAt10());
            rr.add(row.reciprocalRank());
            top1.add(row.topOneHit());
            totalQueryMs += row.queryLatencyMs();
            if (row.hasAdversarial()) {
                if (row.adversarialPrecisionPass() != null) advPrec.add(row.adversarialPrecisionPass());
                if (row.adversarialRankGap() != null) advGaps.add(row.adversarialRankGap());
                for (Map.Entry<AdversarialType, Integer> e : row.rankGapByType().entrySet()) {
                    gapsByType.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                }
            }
        }

        int n = rows.size();
        int nAdv = advPrec.size();
        double advPrecAgg = nAdv == 0 ? Double.NaN
                : (double) Metrics.countTrue(advPrec) / nAdv;

        double meanGap = Metrics.meanInt(advGaps, Integer.MAX_VALUE, Integer.MIN_VALUE);
        Map<AdversarialType, Double> meanGapByType = new EnumMap<>(AdversarialType.class);
        for (Map.Entry<AdversarialType, List<Integer>> e : gapsByType.entrySet()) {
            meanGapByType.put(e.getKey(),
                    Metrics.meanInt(e.getValue(), Integer.MAX_VALUE, Integer.MIN_VALUE));
        }

        return new RunResult.AggregateMetrics(
                n, nAdv,
                Metrics.mean(r1), Metrics.mean(r3), Metrics.mean(r10),
                Metrics.mean(rr),
                (double) Metrics.countTrue(top1) / Math.max(1, n),
                advPrecAgg, meanGap, meanGapByType,
                n == 0 ? 0L : totalQueryMs / n);
    }

    private static List<String> dedupOrdered(List<EmbeddingMatch<TextSegment>> matches) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> m : matches) {
            String path = m.embedded().metadata().getString("path");
            if (path == null) path = m.embedded().metadata().getString("name");
            if (path == null) continue;
            if (seen.add(path)) out.add(path);
        }
        return out;
    }

    private static String truncate(String s) {
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    /** Convenience: produce {@code Arrays.asList(...)} of {@link AdversarialType} for diagnostics. */
    public static List<AdversarialType> allAdversarialTypes() {
        return Arrays.asList(AdversarialType.values());
    }
}
