package io.github.daeryss.karta.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code corpora/kafka-4.0.0/corpus.yaml} resolves against a locally
 * cloned Apache Kafka 4.0.0 repository pointed to by the {@code KAFKA_4_0_0_PATH}
 * environment variable. No embedding, no Ollama — pure loader smoke test.
 *
 * <p>Reports file count, language breakdown, and total bytes so a regression in the
 * include/exclude globs is immediately visible.</p>
 */
@EnabledIfEnvironmentVariable(
        named = "KAFKA_4_0_0_PATH",
        matches = ".+",
        disabledReason = "Requires KAFKA_4_0_0_PATH pointing at a Kafka 4.0.0 clone"
)
class KafkaCoreCorpusLoadTest {

    private static final Path REPO_ROOT  = Paths.get("").toAbsolutePath();
    private static final Path CORPUS_DIR = REPO_ROOT.resolve("corpora/kafka-4.0.0");

    @Test
    void resolvesAgainstClonedKafka() throws IOException {
        CorpusSpec spec = CorpusLoader.parse(CORPUS_DIR.resolve("corpus.yaml"));
        assertNotNull(spec.basePath());
        assertTrue(spec.isBasePathMode(), "should be in basePath mode, not files mode");

        Map<String, String> resolved = CorpusLoader.resolve(spec, null);
        assertFalse(resolved.isEmpty(), "corpus must resolve to a non-empty file set");

        // Summary — per-module / per-language breakdown.
        Map<String, int[]> byModule = new LinkedHashMap<>();   // [files, bytes]
        int scalaFiles = 0, javaFiles = 0;
        long totalBytes = 0;
        for (Map.Entry<String, String> e : resolved.entrySet()) {
            String rel = e.getKey();
            int b = e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            totalBytes += b;
            String module = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
            byModule.computeIfAbsent(module, k -> new int[2]);
            byModule.get(module)[0]++;
            byModule.get(module)[1] += b;
            if      (rel.endsWith(".scala")) scalaFiles++;
            else if (rel.endsWith(".java"))  javaFiles++;
        }

        System.out.println();
        System.out.println("=== " + spec.id() + " resolution ===");
        System.out.printf(Locale.ROOT, "files: %d total  (%d Scala, %d Java)%n",
                resolved.size(), scalaFiles, javaFiles);
        System.out.printf(Locale.ROOT, "bytes: %,d  (~%.1f MB)%n", totalBytes, totalBytes / 1_048_576.0);
        System.out.println("--- per module ---");
        System.out.printf(Locale.ROOT, "%-22s  %5s  %9s%n", "module", "files", "bytes");
        for (Map.Entry<String, int[]> e : byModule.entrySet()) {
            System.out.printf(Locale.ROOT, "%-22s  %5d  %,9d%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        System.out.println();
        System.out.println("first 5 paths:");
        int n = 0;
        for (String rel : resolved.keySet()) {
            System.out.println("  " + rel);
            if (++n >= 5) break;
        }
    }
}
