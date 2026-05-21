package io.github.daeryss.karta.integration;

import dev.langchain4j.community.rag.content.retriever.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test wiring OllamaEmbeddingModel + LuceneEmbeddingStore.
 * Embeds a handful of Kafka-flavoured code snippets, queries with a natural
 * language question, and verifies the top match is producer-related.
 *
 * Requires a local Ollama daemon with the {@code nomic-embed-text} model
 * pulled. Enable explicitly: {@code RUN_OLLAMA_TESTS=true ./gradlew test}.
 */
@EnabledIfEnvironmentVariable(
        named = "RUN_OLLAMA_TESTS",
        matches = "true",
        disabledReason = "Requires local Ollama with nomic-embed-text"
)
class OllamaLuceneSmokeIT {

    private static final String OLLAMA_URL = "http://localhost:11434";
    private static final String EMBED_MODEL = "nomic-embed-text";

    @Test
    void embedKafkaSnippetsAndRetrieveSemanticMatch() {
        EmbeddingModel embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(OLLAMA_URL)
                .modelName(EMBED_MODEL)
                .timeout(Duration.ofSeconds(300))
                .build();

        LuceneEmbeddingStore store = LuceneEmbeddingStore.builder()
                .directory(new ByteBuffersDirectory())
                .build();

        List<TextSegment> segments = List.of(
                TextSegment.from(
                        "public class KafkaProducer<K, V> implements Producer<K, V> { "
                                + "private final Sender sender; "
                                + "public Future<RecordMetadata> send(ProducerRecord<K, V> record) { ... } }"
                ),
                TextSegment.from(
                        "public abstract class AbstractCoordinator { "
                                + "private final Heartbeat heartbeat; "
                                + "void sendHeartbeatRequest() { ... } }"
                ),
                TextSegment.from(
                        "class ConsumerGroupMetadataManager { "
                                + "void commitOffset(TopicPartition tp, long offset) { ... } }"
                ),
                TextSegment.from(
                        "public class LogSegment { "
                                + "private final FileRecords log; "
                                + "void append(MemoryRecords records) { ... } }"
                )
        );

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);

        Embedding query = embeddingModel.embed(
                "how does a producer send records to Kafka?"
        ).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(2)
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        List<EmbeddingMatch<TextSegment>> matches = result.matches();

        System.out.println("--- top matches for 'how does a producer send records to Kafka?' ---");
        for (EmbeddingMatch<TextSegment> m : matches) {
            System.out.printf("  score=%.4f  text=%s%n", m.score(),
                    m.embedded().text().substring(0, Math.min(80, m.embedded().text().length())));
        }

        assertFalse(matches.isEmpty(), "expected at least one match");
        assertEquals(2, matches.size(), "expected exactly 2 matches");

        String topText = matches.get(0).embedded().text();
        assertTrue(
                topText.contains("KafkaProducer") || topText.contains("send"),
                "top match should be producer-related; got: " + topText
        );
    }
}
