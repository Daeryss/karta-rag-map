package io.github.daeryss.karta.integration;

import dev.langchain4j.community.rag.content.retriever.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.document.Metadata;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Whole-file embedding benchmark on a fixed 10-file sample from Apache Kafka
 * (5 Java from clients/, 5 Scala from core/, pinned to tag 4.0.0).
 *
 * Goal: measure per-file embed latency, total ingest cost, vector dim, and
 * eyeball retrieval quality on three natural-language queries. Files larger
 * than the default Ollama context window (~2048 tokens ≈ 8 KB of source) are
 * rejected by the server (HTTP "input length exceeds the context length")
 * and recorded as {@code OVER_CTX} — this is the data point that motivates
 * AST-aware chunking in v0.2.
 *
 * Requires a local Ollama daemon with {@code nomic-embed-text}. Enable
 * explicitly: {@code RUN_OLLAMA_TESTS=true ./gradlew test}.
 */
@EnabledIfEnvironmentVariable(
        named = "RUN_OLLAMA_TESTS",
        matches = "true",
        disabledReason = "Requires local Ollama with nomic-embed-text"
)
class KafkaCorpusBenchmarkIT {

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

    @Test
    void benchmarkWholeFileEmbedAndQuery() throws IOException {
        Path cacheDir = Paths.get("build", "kafka-corpus", KAFKA_TAG);
        Files.createDirectories(cacheDir);

        EmbeddingModel embedder = OllamaEmbeddingModel.builder()
                .baseUrl(OLLAMA_URL)
                .modelName(EMBED_MODEL)
                .timeout(Duration.ofSeconds(300))
                .build();

        LuceneEmbeddingStore store = LuceneEmbeddingStore.builder()
                .directory(new ByteBuffersDirectory())
                .build();

        long totalBytes = 0;
        long totalEmbedMs = 0;
        int dim = -1;
        int indexed = 0;
        int overCtx = 0;

        System.out.println("--- ingest (whole-file embed, Kafka " + KAFKA_TAG + ") ---");
        System.out.printf(Locale.ROOT, "%-46s  %8s  %8s  %9s%n", "file", "bytes", "embed", "status");
        for (CorpusFile f : CORPUS) {
            Path local = cacheDir.resolve(f.name());
            if (Files.notExists(local)) {
                URI uri = URI.create(RAW_BASE + f.relPath());
                try (InputStream in = uri.toURL().openStream()) {
                    Files.copy(in, local);
                }
            }
            String text = Files.readString(local, StandardCharsets.UTF_8);
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            totalBytes += bytes;

            TextSegment seg = TextSegment.from(
                    text,
                    Metadata.from(Map.of("path", f.relPath(), "name", f.name()))
            );

            long t0 = System.nanoTime();
            try {
                Embedding emb = embedder.embed(seg).content();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalEmbedMs += ms;
                dim = emb.dimension();
                store.add(emb, seg);
                indexed++;
                System.out.printf(Locale.ROOT, "%-46s  %8d  %6dms  %9s%n",
                        f.name(), bytes, ms, "ok");
            } catch (InvalidRequestException e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                overCtx++;
                System.out.printf(Locale.ROOT, "%-46s  %8d  %6dms  %9s%n",
                        f.name(), bytes, ms, "OVER_CTX");
            }
        }
        System.out.printf(Locale.ROOT,
                "totals: %d files (indexed=%d, over_ctx=%d), %d bytes, embed=%d ms"
                        + " (mean %d ms/indexed), vector dim=%d%n%n",
                CORPUS.size(), indexed, overCtx, totalBytes, totalEmbedMs,
                indexed > 0 ? totalEmbedMs / indexed : 0, dim);

        System.out.println("--- queries (top-3) ---");
        for (String q : QUERIES) {
            long t0 = System.nanoTime();
            Embedding qe = embedder.embed(q).content();
            EmbeddingSearchResult<TextSegment> res = store.search(
                    EmbeddingSearchRequest.builder().queryEmbedding(qe).maxResults(3).build()
            );
            long ms = (System.nanoTime() - t0) / 1_000_000;
            List<EmbeddingMatch<TextSegment>> matches = res.matches();

            System.out.printf(Locale.ROOT, "Q (%dms): %s%n", ms, q);
            for (EmbeddingMatch<TextSegment> m : matches) {
                System.out.printf(Locale.ROOT, "  %.4f  %s%n",
                        m.score(), m.embedded().metadata().getString("name"));
            }
            if (indexed > 0) {
                assertFalse(matches.isEmpty(), "no matches for query: " + q);
            }
        }
    }
}
