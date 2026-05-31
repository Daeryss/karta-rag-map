package io.github.daeryss.karta.integration;

import io.github.daeryss.karta.benchmark.BenchmarkConfig;
import io.github.daeryss.karta.benchmark.SweepRunner;
import io.github.daeryss.karta.corpus.CorpusLoader;
import io.github.daeryss.karta.corpus.CorpusSpec;
import io.github.daeryss.karta.output.EnvironmentSnapshot;
import io.github.daeryss.karta.query.GoldQueryLoader;
import io.github.daeryss.karta.query.GoldQuerySet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E003b — Chunk-size sweep on the full Apache Kafka 4.0.0 broker corpus (kafka-4.0.0-core)
 * with the v0.1.0 gold query set (30 queries, 27 disambiguation). Same 4 configurations
 * as the historical pilot sweep so cross-corpus deltas are directly comparable.
 *
 * <p>Output: per-config artefacts under {@code experiments/E003b-core-sweep/<exp-id>/}
 * plus consolidated {@code sweep-configs.csv} and {@code sweep-queries.csv} at the
 * sweep root. This is the first measurement run under methodology v0.3 — every result
 * cites {@code judge-quorum@v0.x.0} when layer 2 is added; for now (layer 1 only) the
 * sweep reports Recall@k, MRR, top-1 accuracy, adversarial precision, and rank-gap.</p>
 */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "RUN_OLLAMA_TESTS", matches = "true",
                disabledReason = "Requires local Ollama with nomic-embed-text"),
        @EnabledIfEnvironmentVariable(named = "KAFKA_4_0_0_PATH", matches = ".+",
                disabledReason = "Requires KAFKA_4_0_0_PATH pointing at a Kafka 4.0.0 clone")
})
class KafkaCoreSweepIT {

    private static final Path REPO_ROOT   = Paths.get("").toAbsolutePath();
    private static final Path CORPUS_DIR  = REPO_ROOT.resolve("corpora/kafka-4.0.0");
    private static final Path EXPERIMENTS = REPO_ROOT.resolve("experiments");

    @Test
    void chunkSizeSweepOnFullCore() throws IOException {
        String embedModel = System.getProperty("karta.embeddingModel", "nomic-embed-text");
        String ollamaUrl  = System.getProperty("karta.ollamaUrl",      "http://localhost:11434");
        String hardware   = System.getProperty("karta.hardware",       "Apple M5 Pro, 48 GB RAM");
        String sweepId    = System.getProperty("karta.sweepId",        "E003b-core-sweep");

        CorpusSpec spec = CorpusLoader.parse(CORPUS_DIR.resolve("corpus.yaml"));
        Map<String, String> corpus = CorpusLoader.resolve(spec, null);
        GoldQuerySet goldSet = GoldQueryLoader.load(CORPUS_DIR.resolve("gold-queries.yaml"));

        assertEquals(spec.id(), goldSet.corpusId());
        assertTrue(corpus.size() > 100, "expected full broker corpus");
        assertEquals(30, goldSet.queries().size(), "gold set must be v0.1.0 with 30 queries");

        long bytes = corpus.values().stream()
                .mapToLong(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        System.out.printf("%nresolved: %d files, %.1f MB; gold set: %d queries (v%s)%n",
                corpus.size(), bytes / 1_048_576.0, goldSet.queries().size(), goldSet.version());

        List<BenchmarkConfig> configs = List.of(
                config("E003b-750-100",   750,  100, embedModel, ollamaUrl),
                config("E003b-1500-200",  1500, 200, embedModel, ollamaUrl),
                config("E003b-3000-300",  3000, 300, embedModel, ollamaUrl),
                config("E003b-6000-500",  6000, 500, embedModel, ollamaUrl)
        );

        EnvironmentSnapshot env = EnvironmentSnapshot.capture(
                "v0.3", hardware, ollamaUrl, embedModel, REPO_ROOT);

        Path sweepDir = new SweepRunner(sweepId, configs).run(EXPERIMENTS, corpus, goldSet, env);
        System.out.println("sweep artefacts: " + sweepDir);
        assertTrue(sweepDir.toFile().isDirectory());
    }

    private static BenchmarkConfig config(String expId, int chunk, int overlap,
                                          String embedModel, String ollamaUrl) {
        return new BenchmarkConfig(expId, chunk, overlap, false, embedModel, ollamaUrl, 10);
    }
}
