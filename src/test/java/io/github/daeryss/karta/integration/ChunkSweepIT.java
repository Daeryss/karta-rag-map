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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunk-size sweep on the kafka-4.0.0-pilot corpus, parameterised harness version
 * of the historical {@code KafkaChunkSizeSweepIT}. Same four configurations
 * (750/100, 1500/200, 3000/300, 6000/500) so the migration can be cross-checked
 * against the 2026-05-21 reference numbers stored in {@code experiments/E003/results.txt}.
 *
 * <p>Writes per-config artefacts under {@code experiments/E003-sweep/<exp-id>/}
 * plus consolidated {@code sweep-configs.csv} and {@code sweep-queries.csv} at the
 * sweep root for direct comparison.</p>
 */
@EnabledIfEnvironmentVariable(
        named = "RUN_OLLAMA_TESTS",
        matches = "true",
        disabledReason = "Requires local Ollama with nomic-embed-text"
)
class ChunkSweepIT {

    private static final Path REPO_ROOT   = Paths.get("").toAbsolutePath();
    private static final Path PILOT_DIR   = REPO_ROOT.resolve("corpora/kafka-4.0.0-pilot");
    private static final Path EXPERIMENTS = REPO_ROOT.resolve("experiments");
    private static final Path CACHE_DIR   = REPO_ROOT.resolve("build/kafka-corpus/4.0.0");

    @Test
    void chunkSizeSweepOnPilot() throws IOException {
        String embedModel = System.getProperty("karta.embeddingModel", "nomic-embed-text");
        String ollamaUrl  = System.getProperty("karta.ollamaUrl",      "http://localhost:11434");
        String hardware   = System.getProperty("karta.hardware",       "Apple M5 Pro, 48 GB RAM");
        String sweepId    = System.getProperty("karta.sweepId",        "E003-sweep");

        CorpusSpec spec = CorpusLoader.parse(PILOT_DIR.resolve("corpus.yaml"));
        Map<String, String> corpus = CorpusLoader.resolve(spec, CACHE_DIR);
        GoldQuerySet goldSet = GoldQueryLoader.load(PILOT_DIR.resolve("gold-queries.yaml"));

        assertEquals(10, corpus.size(), "pilot corpus must resolve to 10 files");
        assertEquals(spec.id(), goldSet.corpusId());

        List<BenchmarkConfig> configs = List.of(
                config("E003-750-100",   750,  100, embedModel, ollamaUrl),
                config("E003-1500-200",  1500, 200, embedModel, ollamaUrl),
                config("E003-3000-300",  3000, 300, embedModel, ollamaUrl),
                config("E003-6000-500",  6000, 500, embedModel, ollamaUrl)
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
