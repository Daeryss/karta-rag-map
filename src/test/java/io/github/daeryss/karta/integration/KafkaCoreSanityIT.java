package io.github.daeryss.karta.integration;

import io.github.daeryss.karta.benchmark.BenchmarkConfig;
import io.github.daeryss.karta.benchmark.BenchmarkRunner;
import io.github.daeryss.karta.benchmark.RunResult;
import io.github.daeryss.karta.corpus.CorpusLoader;
import io.github.daeryss.karta.corpus.CorpusSpec;
import io.github.daeryss.karta.output.EnvironmentSnapshot;
import io.github.daeryss.karta.output.ResultsWriter;
import io.github.daeryss.karta.query.GoldQueryLoader;
import io.github.daeryss.karta.query.GoldQuerySet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity run — single Karta-default config (3000/300) on the full broker corpus
 * (~697 files, ~6 MB) with the 4 sanity queries. Goal: verify the harness scales
 * from the 10-file pilot, not to publish numbers. The gold set here is explicitly
 * labelled {@code sanity-0.1} and is replaced by a proper ≥20-query set later.
 */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "RUN_OLLAMA_TESTS", matches = "true",
                disabledReason = "Requires local Ollama with nomic-embed-text"),
        @EnabledIfEnvironmentVariable(named = "KAFKA_4_0_0_PATH", matches = ".+",
                disabledReason = "Requires KAFKA_4_0_0_PATH pointing at a Kafka 4.0.0 clone")
})
class KafkaCoreSanityIT {

    private static final Path REPO_ROOT   = Paths.get("").toAbsolutePath();
    private static final Path CORPUS_DIR  = REPO_ROOT.resolve("corpora/kafka-4.0.0");
    private static final Path EXPERIMENTS = REPO_ROOT.resolve("experiments");

    @Test
    void sanityFullCorpus3000_300() throws IOException {
        String embedModel = System.getProperty("karta.embeddingModel", "nomic-embed-text");
        String ollamaUrl  = System.getProperty("karta.ollamaUrl",      "http://localhost:11434");
        String hardware   = System.getProperty("karta.hardware",       "Apple M5 Pro, 48 GB RAM");
        String expId      = System.getProperty("karta.experimentId",   "E-sanity-core-3000-300");

        CorpusSpec spec = CorpusLoader.parse(CORPUS_DIR.resolve("corpus.yaml"));
        Map<String, String> corpus = CorpusLoader.resolve(spec, null);
        GoldQuerySet goldSet = GoldQueryLoader.load(CORPUS_DIR.resolve("gold-queries-sanity.yaml"));

        assertEquals(spec.id(), goldSet.corpusId(),
                "gold-queries corpus_id must match corpus.yaml id");
        assertTrue(corpus.size() > 100, "expected full corpus to be hundreds of files");

        long ingestBytes = corpus.values().stream()
                .mapToLong(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        System.out.printf(Locale.ROOT, "%nresolved corpus: %d files, %.1f MB%n",
                corpus.size(), ingestBytes / 1_048_576.0);

        BenchmarkConfig config = new BenchmarkConfig(expId, 3000, 300, false, embedModel, ollamaUrl, 10);
        EnvironmentSnapshot env = EnvironmentSnapshot.capture(
                "v0.3", hardware, ollamaUrl, embedModel, REPO_ROOT);

        long t0 = System.nanoTime();
        RunResult result = new BenchmarkRunner(config).run(corpus, goldSet);
        long wallMs = (System.nanoTime() - t0) / 1_000_000;

        Path outDir = ResultsWriter.write(EXPERIMENTS, result, env);
        printSummary(result, outDir, wallMs);

        assertEquals(goldSet.queries().size(), result.perQuery().size());
    }

    private void printSummary(RunResult r, Path outDir, long wallMs) {
        System.out.printf(Locale.ROOT,
                "%n=== %s — %s — wall=%d ms ===%n",
                r.config().experimentId(), r.config().chunkLabel(), wallMs);
        System.out.printf(Locale.ROOT,
                "ingest: %d/%d indexed (%d skipped), %d chunks, %d ms embed (%d ms/chunk)%n",
                r.ingest().filesIndexed(), r.ingest().filesAttempted(),
                r.ingest().filesSkipped(), r.ingest().totalChunks(), r.ingest().totalEmbedMs(),
                r.ingest().totalChunks() > 0 ? r.ingest().totalEmbedMs() / r.ingest().totalChunks() : 0);
        System.out.printf(Locale.ROOT,
                "aggregate: top1=%.2f  R@1=%.2f  R@3=%.2f  R@10=%.2f  MRR=%.3f%n",
                r.aggregate().topOneAccuracy(),
                r.aggregate().meanRecallAt1(),
                r.aggregate().meanRecallAt3(),
                r.aggregate().meanRecallAt10(),
                r.aggregate().mrr());
        if (r.aggregate().disambiguationQueries() > 0) {
            System.out.printf(Locale.ROOT,
                    "disambig (%d): adv_precision=%.2f  mean_rank_gap=%.2f%n",
                    r.aggregate().disambiguationQueries(),
                    r.aggregate().adversarialPrecision(),
                    r.aggregate().meanRankGap());
        }
        System.out.println("per-query:");
        for (RunResult.QueryRow row : r.perQuery()) {
            System.out.printf(Locale.ROOT,
                    "  %-4s  top1=%-60s  cos=%.4f  hit=%-5s%s%n",
                    row.queryId(),
                    truncate(row.top1File(), 60),
                    row.top1Cosine(),
                    row.topOneHit(),
                    row.hasAdversarial()
                            ? "  adv_pass=" + row.adversarialPrecisionPass()
                              + "  gap=" + (row.adversarialRankGap() == null ? "n/a" : row.adversarialRankGap())
                            : "");
        }
        System.out.println("artefacts: " + outDir);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : "..." + s.substring(s.length() - (n - 3));
    }
}
