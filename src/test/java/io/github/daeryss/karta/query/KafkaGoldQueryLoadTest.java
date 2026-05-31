package io.github.daeryss.karta.query;

import io.github.daeryss.karta.corpus.CorpusLoader;
import io.github.daeryss.karta.corpus.CorpusSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses {@code corpora/kafka-4.0.0/gold-queries.yaml}, validates schema, and verifies
 * that every {@code expected} and {@code adversarial} file actually resolves inside the
 * full kafka-4.0.0-core corpus. Prints a balance summary so type / disambiguation drift
 * is immediately visible when the set grows.
 */
@EnabledIfEnvironmentVariable(
        named = "KAFKA_4_0_0_PATH",
        matches = ".+",
        disabledReason = "Requires KAFKA_4_0_0_PATH pointing at a Kafka 4.0.0 clone"
)
class KafkaGoldQueryLoadTest {

    private static final Path REPO_ROOT  = Paths.get("").toAbsolutePath();
    private static final Path CORPUS_DIR = REPO_ROOT.resolve("corpora/kafka-4.0.0");

    @Test
    void goldSetParsesAndReferencesValidFiles() throws IOException {
        CorpusSpec spec = CorpusLoader.parse(CORPUS_DIR.resolve("corpus.yaml"));
        Map<String, String> corpus = CorpusLoader.resolve(spec, null);
        Set<String> corpusPaths = corpus.keySet();

        GoldQuerySet set = GoldQueryLoader.load(CORPUS_DIR.resolve("gold-queries.yaml"));
        assertEquals(spec.id(), set.corpusId(),
                "gold-queries corpus_id must match corpus.yaml id");

        Set<String> missing = new HashSet<>();
        for (GoldQuery q : set.queries()) {
            for (String exp : q.expected()) {
                if (!corpusPaths.contains(exp)) missing.add("expected:" + q.id() + " " + exp);
            }
            for (GoldQuery.Adversarial a : q.adversarialOrEmpty()) {
                if (!corpusPaths.contains(a.file())) missing.add("adversarial:" + q.id() + " " + a.file());
            }
        }
        assertTrue(missing.isEmpty(), "Unresolved paths: " + missing);

        // Balance summary.
        Map<QueryType, Integer> byType = new EnumMap<>(QueryType.class);
        Map<AdversarialType, Integer> byAdvType = new EnumMap<>(AdversarialType.class);
        int disambig = 0, totalAdv = 0;
        for (GoldQuery q : set.queries()) {
            byType.merge(q.type(), 1, Integer::sum);
            if (q.hasAdversarial()) {
                disambig++;
                for (GoldQuery.Adversarial a : q.adversarialOrEmpty()) {
                    byAdvType.merge(a.type(), 1, Integer::sum);
                    totalAdv++;
                }
            }
        }

        System.out.println();
        System.out.printf(Locale.ROOT, "=== %s gold-queries (version=%s, author=%s) ===%n",
                set.corpusId(), set.version(), set.author());
        System.out.printf(Locale.ROOT, "queries: %d total, %d disambiguation (%d adversarial entries)%n",
                set.queries().size(), disambig, totalAdv);
        System.out.println("by query type:");
        for (QueryType t : QueryType.values()) {
            System.out.printf(Locale.ROOT, "  %-16s  %d%n", t, byType.getOrDefault(t, 0));
        }
        System.out.println("by adversarial type:");
        for (AdversarialType t : AdversarialType.values()) {
            int n = byAdvType.getOrDefault(t, 0);
            if (n > 0) System.out.printf(Locale.ROOT, "  %-12s  %d%n", t, n);
        }
        assertFalse(set.queries().isEmpty(), "gold set must be non-empty");
    }
}
