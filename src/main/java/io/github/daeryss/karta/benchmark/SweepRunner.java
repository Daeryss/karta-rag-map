package io.github.daeryss.karta.benchmark;

import io.github.daeryss.karta.output.EnvironmentSnapshot;
import io.github.daeryss.karta.output.ResultsWriter;
import io.github.daeryss.karta.output.SweepWriter;
import io.github.daeryss.karta.query.GoldQuerySet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs a sequence of {@link BenchmarkConfig}s against the same corpus and gold set,
 * collecting per-config {@link RunResult}s and writing consolidated artefacts.
 *
 * <p>Sweep semantics: each member config gets its own fresh embedding store
 * (the runner already does this per-config) and its own {@code experiments/<sweep-id>/<exp-id>/}
 * sub-directory. Two additional sweep-level CSVs are written at the sweep root so
 * one row per (config × query) and per-config aggregates are both directly pivotable
 * without combining files manually.</p>
 *
 * <p>The same corpus map is reused across all configs — load it once. The same gold
 * set is reused — load it once. Only the chunking config varies between members,
 * per the one-axis-at-a-time discipline of research-methodology.md §5.1.</p>
 */
public final class SweepRunner {

    private final String sweepId;
    private final List<BenchmarkConfig> configs;

    public SweepRunner(String sweepId, List<BenchmarkConfig> configs) {
        if (sweepId == null || sweepId.isBlank())
            throw new IllegalArgumentException("sweepId is required");
        if (configs == null || configs.isEmpty())
            throw new IllegalArgumentException("configs must be non-empty");
        // Defensive uniqueness check on experimentId so per-config sub-dirs do not collide.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BenchmarkConfig c : configs) {
            if (!seen.add(c.experimentId()))
                throw new IllegalArgumentException(
                        "Duplicate experimentId in sweep '" + sweepId + "': " + c.experimentId());
        }
        this.sweepId = sweepId;
        this.configs = List.copyOf(configs);
    }

    public Path run(Path experimentsRoot, Map<String, String> corpus,
                    GoldQuerySet goldSet, EnvironmentSnapshot env) throws IOException {
        Path sweepDir = experimentsRoot.resolve(sweepId);
        List<RunResult> results = new ArrayList<>(configs.size());

        for (BenchmarkConfig cfg : configs) {
            long t0 = System.nanoTime();
            RunResult r = new BenchmarkRunner(cfg).run(corpus, goldSet);
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            ResultsWriter.write(sweepDir, r, env);
            results.add(r);
            System.out.printf(Locale.ROOT,
                    "sweep[%s] %-26s  chunks=%-5d  embed=%5dms  top1=%.2f  R@1=%.2f  MRR=%.3f  wall=%dms%n",
                    sweepId, cfg.experimentId(),
                    r.ingest().totalChunks(), r.ingest().totalEmbedMs(),
                    r.aggregate().topOneAccuracy(),
                    r.aggregate().meanRecallAt1(),
                    r.aggregate().mrr(),
                    wallMs);
        }

        SweepWriter.write(sweepDir, sweepId, results, env);
        return sweepDir;
    }
}
