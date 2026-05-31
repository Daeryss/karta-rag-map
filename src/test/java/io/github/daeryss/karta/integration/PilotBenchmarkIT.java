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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for the parameterised harness on the kafka-4.0.0-pilot corpus.
 *
 * <p>Replaces the three hand-rolled ITs (E001/E002/E003 variants) with one
 * data-driven invocation: corpus.yaml + gold-queries.yaml drive everything.
 * Verifies that the loaders, runner, metric computation, and CSV/JSON writer all
 * agree end-to-end on a real Ollama run.</p>
 *
 * <p>The chunking config defaults to Karta defaults v0.1.0 (recursive 3000/300) but is
 * overridable via system properties so the same test can validate any single point
 * on the sweep without copy-paste.</p>
 */
@EnabledIfEnvironmentVariable(
        named = "RUN_OLLAMA_TESTS",
        matches = "true",
        disabledReason = "Requires local Ollama with nomic-embed-text"
)
class PilotBenchmarkIT {

    private static final Path REPO_ROOT     = Paths.get("").toAbsolutePath();
    private static final Path PILOT_DIR     = REPO_ROOT.resolve("corpora/kafka-4.0.0-pilot");
    private static final Path EXPERIMENTS   = REPO_ROOT.resolve("experiments");
    private static final Path CACHE_DIR     = REPO_ROOT.resolve("build/kafka-corpus/4.0.0");

    @Test
    void runPilotEndToEnd() throws IOException {
        // Knob overrides via -D for sweep-style invocations from CLI.
        int chunkChars   = Integer.getInteger("karta.chunkChars", 3000);
        int overlapChars = Integer.getInteger("karta.overlapChars", 300);
        boolean wholeFile = Boolean.getBoolean("karta.wholeFile");
        String expId = System.getProperty("karta.experimentId",
                wholeFile ? "E001-pilot" : "E-pilot-" + chunkChars + "-" + overlapChars);
        String embedModel = System.getProperty("karta.embeddingModel", "nomic-embed-text");
        String ollamaUrl  = System.getProperty("karta.ollamaUrl",  "http://localhost:11434");
        String hardware   = System.getProperty("karta.hardware",   "Apple M5 Pro, 48 GB RAM");

        // Load corpus + gold set.
        CorpusSpec spec = CorpusLoader.parse(PILOT_DIR.resolve("corpus.yaml"));
        Map<String, String> corpus = CorpusLoader.resolve(spec, CACHE_DIR);
        GoldQuerySet goldSet = GoldQueryLoader.load(PILOT_DIR.resolve("gold-queries.yaml"));

        assertEquals(spec.id(), goldSet.corpusId(),
                "gold-queries.yaml corpus_id must match corpus.yaml id");
        assertEquals(10, corpus.size(), "Pilot corpus must resolve to 10 files");

        // Build config + run.
        BenchmarkConfig config = new BenchmarkConfig(
                expId, chunkChars, overlapChars, wholeFile, embedModel, ollamaUrl, 10);
        EnvironmentSnapshot env = EnvironmentSnapshot.capture(
                "v0.3", hardware, ollamaUrl, embedModel, REPO_ROOT);

        RunResult result = new BenchmarkRunner(config).run(corpus, goldSet);

        // Persist canonical artefacts.
        Path outDir = ResultsWriter.write(EXPERIMENTS, result, env);

        // Print a tight summary so eyeballing is easy in test output.
        printSummary(result, outDir);

        // Sanity checks — never assert specific numbers (those are the data); assert structure.
        assertEquals(goldSet.queries().size(), result.perQuery().size());
        assertNotNull(result.aggregate());
        assertTrue(result.ingest().filesAttempted() > 0);
    }

    private void printSummary(RunResult r, Path outDir) {
        System.out.println();
        System.out.println("=== " + r.config().experimentId() + " — " + r.config().chunkLabel() + " ===");
        System.out.printf(Locale.ROOT,
                "ingest: %d/%d indexed, %d skipped, %d chunks, %d ms total embed%n",
                r.ingest().filesIndexed(), r.ingest().filesAttempted(),
                r.ingest().filesSkipped(), r.ingest().totalChunks(), r.ingest().totalEmbedMs());
        System.out.printf(Locale.ROOT, "aggregate: top-1=%.2f  R@1=%.2f  R@3=%.2f  R@10=%.2f  MRR=%.3f%n",
                r.aggregate().topOneAccuracy(),
                r.aggregate().meanRecallAt1(),
                r.aggregate().meanRecallAt3(),
                r.aggregate().meanRecallAt10(),
                r.aggregate().mrr());
        if (r.aggregate().disambiguationQueries() > 0) {
            System.out.printf(Locale.ROOT,
                    "disambig (%d queries): adv_precision=%.2f  mean_rank_gap=%.2f%n",
                    r.aggregate().disambiguationQueries(),
                    r.aggregate().adversarialPrecision(),
                    r.aggregate().meanRankGap());
        }
        System.out.println("per-query:");
        for (RunResult.QueryRow row : r.perQuery()) {
            System.out.printf(Locale.ROOT,
                    "  %-4s  top1=%-50s  cos=%.4f  hit=%-5s%s%n",
                    row.queryId(),
                    truncate(row.top1File(), 50),
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
