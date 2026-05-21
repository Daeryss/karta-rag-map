package io.github.daeryss.karta.integration;

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
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sweeps chunk-size / overlap parameters of the recursive splitter over the
 * fixed 10-file Kafka 4.0.0 corpus and the same 3 queries, then prints one
 * side-by-side comparison table.
 *
 * Per-config measurements: total chunks produced, total embed wall-time,
 * mean ms/chunk, and the top-1 hit (file + cosine) for each query.
 *
 * Larger configurations may push individual chunks past the Ollama default
 * 2048-token window; affected files are recorded as skipped and noted in
 * the per-config summary line.
 *
 * Requires a local Ollama daemon with {@code nomic-embed-text}. Enable
 * explicitly: {@code RUN_OLLAMA_TESTS=true ./gradlew test}.
 */
@EnabledIfEnvironmentVariable(
        named = "RUN_OLLAMA_TESTS",
        matches = "true",
        disabledReason = "Requires local Ollama with nomic-embed-text"
)
class KafkaChunkSizeSweepIT {

    private static final String OLLAMA_URL = "http://localhost:11434";
    private static final String EMBED_MODEL = "nomic-embed-text";
    private static final String KAFKA_TAG = "4.0.0";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/apache/kafka/" + KAFKA_TAG + "/";

    private record CorpusFile(String relPath) {
        String name() { return relPath.substring(relPath.lastIndexOf('/') + 1); }
    }

    private static final List<CorpusFile> CORPUS = List.of(
            new CorpusFile("clients/src/main/java/org/apache/kafka/clients/producer/KafkaProducer.java"),
            new CorpusFile("clients/src/main/java/org/apache/kafka/clients/consumer/KafkaConsumer.java"),
            new CorpusFile("clients/src/main/java/org/apache/kafka/clients/admin/KafkaAdminClient.java"),
            new CorpusFile("clients/src/main/java/org/apache/kafka/common/serialization/Serdes.java"),
            new CorpusFile("clients/src/main/java/org/apache/kafka/clients/CommonClientConfigs.java"),
            new CorpusFile("core/src/main/scala/kafka/log/UnifiedLog.scala"),
            new CorpusFile("core/src/main/scala/kafka/server/KafkaApis.scala"),
            new CorpusFile("core/src/main/scala/kafka/network/SocketServer.scala"),
            new CorpusFile("core/src/main/scala/kafka/coordinator/group/GroupCoordinator.scala"),
            new CorpusFile("core/src/main/scala/kafka/cluster/Replica.scala")
    );

    private static final List<String> QUERIES = List.of(
            "how does a producer send records to Kafka?",
            "how are consumer group offsets coordinated?",
            "how does the broker accept and dispatch network connections?"
    );

    private record Config(int chunkChars, int overlapChars) {
        @Override public String toString() { return chunkChars + "/" + overlapChars; }
    }

    private static final List<Config> CONFIGS = List.of(
            new Config(750, 100),
            new Config(1500, 200),
            new Config(3000, 300),
            new Config(6000, 500)
    );

    private record QueryHit(String topFile, double cosine, long queryMs) {}
    private record ConfigResult(
            Config config, int chunks, int filesSkipped, long embedMs, List<QueryHit> hits) {}

    @Test
    void sweepChunkSizes() throws IOException {
        Path cacheDir = Paths.get("build", "kafka-corpus", KAFKA_TAG);
        Files.createDirectories(cacheDir);

        Map<CorpusFile, String> texts = new LinkedHashMap<>();
        for (CorpusFile f : CORPUS) {
            Path local = cacheDir.resolve(f.name());
            if (Files.notExists(local)) {
                URI uri = URI.create(RAW_BASE + f.relPath());
                try (InputStream in = uri.toURL().openStream()) {
                    Files.copy(in, local);
                }
            }
            texts.put(f, Files.readString(local, StandardCharsets.UTF_8));
        }

        EmbeddingModel embedder = OllamaEmbeddingModel.builder()
                .baseUrl(OLLAMA_URL)
                .modelName(EMBED_MODEL)
                .timeout(Duration.ofSeconds(300))
                .build();

        List<ConfigResult> results = new ArrayList<>();
        for (Config cfg : CONFIGS) {
            ConfigResult r = runConfig(cfg, texts, embedder);
            results.add(r);
            System.out.printf(Locale.ROOT,
                    "done %s — %d chunks, %d files skipped, embed=%d ms%n",
                    cfg, r.chunks(), r.filesSkipped(), r.embedMs());
        }

        printComparisonTable(results);
    }

    private ConfigResult runConfig(Config cfg, Map<CorpusFile, String> texts, EmbeddingModel embedder) {
        LuceneEmbeddingStore store = LuceneEmbeddingStore.builder()
                .directory(new ByteBuffersDirectory())
                .build();
        DocumentSplitter splitter = DocumentSplitters.recursive(cfg.chunkChars(), cfg.overlapChars());

        int totalChunks = 0;
        int filesSkipped = 0;
        long totalEmbedMs = 0;

        for (Map.Entry<CorpusFile, String> e : texts.entrySet()) {
            Document doc = Document.from(
                    e.getValue(),
                    Metadata.from(Map.of("path", e.getKey().relPath(), "name", e.getKey().name()))
            );
            List<TextSegment> segments = splitter.split(doc);

            long t0 = System.nanoTime();
            try {
                List<Embedding> embs = embedder.embedAll(segments).content();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalEmbedMs += ms;
                totalChunks += segments.size();
                store.addAll(embs, segments);
            } catch (InvalidRequestException ex) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalEmbedMs += ms;
                filesSkipped++;
            }
        }

        List<QueryHit> hits = new ArrayList<>();
        for (String q : QUERIES) {
            long t0 = System.nanoTime();
            Embedding qe = embedder.embed(q).content();
            EmbeddingSearchResult<TextSegment> res = store.search(
                    EmbeddingSearchRequest.builder().queryEmbedding(qe).maxResults(1).build()
            );
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (res.matches().isEmpty()) {
                hits.add(new QueryHit("(none)", 0.0, ms));
            } else {
                EmbeddingMatch<TextSegment> m = res.matches().get(0);
                hits.add(new QueryHit(m.embedded().metadata().getString("name"), m.score(), ms));
            }
        }

        return new ConfigResult(cfg, totalChunks, filesSkipped, totalEmbedMs, hits);
    }

    private void printComparisonTable(List<ConfigResult> results) {
        System.out.println();
        System.out.println("=== chunk-size sweep over Kafka " + KAFKA_TAG + " corpus ===");
        System.out.println();
        System.out.printf(Locale.ROOT,
                "%-10s  %7s  %8s  %9s  %9s%n",
                "cfg", "chunks", "skipped", "embed_ms", "ms/chunk");
        for (ConfigResult r : results) {
            int meanMs = r.chunks() > 0 ? (int) (r.embedMs() / r.chunks()) : 0;
            System.out.printf(Locale.ROOT,
                    "%-10s  %7d  %8d  %9d  %9d%n",
                    r.config(), r.chunks(), r.filesSkipped(), r.embedMs(), meanMs);
        }

        System.out.println();
        for (int i = 0; i < QUERIES.size(); i++) {
            System.out.println("Q" + (i + 1) + ": " + QUERIES.get(i));
            System.out.printf(Locale.ROOT, "  %-10s  %-32s  %s%n", "cfg", "top-1 file", "cosine");
            for (ConfigResult r : results) {
                QueryHit h = r.hits().get(i);
                System.out.printf(Locale.ROOT, "  %-10s  %-32s  %.4f%n",
                        r.config(), h.topFile(), h.cosine());
            }
            System.out.println();
        }
    }
}
