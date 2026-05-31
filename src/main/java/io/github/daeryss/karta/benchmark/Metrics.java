package io.github.daeryss.karta.benchmark;

import io.github.daeryss.karta.query.AdversarialType;
import io.github.daeryss.karta.query.GoldQuery;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-query and aggregated metrics defined in research-methodology.md §4.2.
 *
 * <p>The methods here are pure functions over ranked retrieval results — no I/O,
 * no logging. Each query is resolved to a list of file names (or relative paths)
 * ordered by descending score; the metrics inspect that ranking against the
 * gold annotation.</p>
 *
 * <p>"File" identity for matching purposes is the relative path string as stored
 * in the corpus map keys. Both the gold {@code expected}/{@code adversarial} entries
 * and the ranked retrieval results MUST agree on path format (either full relative
 * paths everywhere, or basenames everywhere) — the caller is responsible for
 * picking one and applying it consistently.</p>
 */
public final class Metrics {

    private Metrics() {}

    /**
     * @param rankedFiles  retrieval result, ordered by descending score; one entry per chunk-or-file
     *                     match (callers can collapse to unique files before passing in if they want
     *                     file-level Recall instead of chunk-level)
     * @param k            cut-off
     * @return 1.0 if any {@code expected} file appears in the first {@code k} entries, else 0.0
     */
    public static double recallAtK(List<String> rankedFiles, Set<String> expected, int k) {
        if (expected.isEmpty()) return 0.0;
        int limit = Math.min(k, rankedFiles.size());
        for (int i = 0; i < limit; i++) {
            if (expected.contains(rankedFiles.get(i))) return 1.0;
        }
        return 0.0;
    }

    /** Reciprocal rank of the first {@code expected} file in {@code rankedFiles}, or 0.0 if absent. */
    public static double reciprocalRank(List<String> rankedFiles, Set<String> expected) {
        for (int i = 0; i < rankedFiles.size(); i++) {
            if (expected.contains(rankedFiles.get(i))) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    /** True iff the top-1 result is an expected file. */
    public static boolean topOneHit(List<String> rankedFiles, Set<String> expected) {
        if (rankedFiles.isEmpty()) return false;
        return expected.contains(rankedFiles.get(0));
    }

    /**
     * Adversarial precision (binary per query): {@code true} iff every adversarial file
     * ranks strictly below the best-ranked expected file. Queries without adversarial
     * annotations must not be passed to this method; the caller should filter first.
     *
     * @return {@code true} when expected is above all declared adversarial; {@code false} otherwise
     */
    public static boolean adversarialPrecisionPasses(
            List<String> rankedFiles, Set<String> expected, List<GoldQuery.Adversarial> adversarial) {
        int expectedRank = firstRankOf(rankedFiles, expected);
        if (expectedRank < 0) return false;  // expected not retrieved at all → fail
        for (GoldQuery.Adversarial a : adversarial) {
            int advRank = firstRankOfName(rankedFiles, a.file());
            // If adversarial is not in the ranking at all, it cannot rank above expected — pass for this one.
            if (advRank < 0) continue;
            if (advRank < expectedRank) return false;
        }
        return true;
    }

    /**
     * Signed rank-gap on a single query: {@code best_adversarial_rank − expected_rank}.
     *
     * <p>Positive → expected ranks above the best adversarial (good).<br>
     * Negative → an adversarial file is above expected (bad).<br>
     * Zero    → cannot happen with this definition (ranks are integers and distinct).</p>
     *
     * <p>If no adversarial file appears in the ranking, the gap is undefined — the method
     * returns {@link Integer#MAX_VALUE} as a sentinel and the caller should exclude such
     * queries from the mean. If expected itself is missing, returns {@link Integer#MIN_VALUE}.</p>
     */
    public static int rankGap(
            List<String> rankedFiles, Set<String> expected, List<GoldQuery.Adversarial> adversarial) {
        int expectedRank = firstRankOf(rankedFiles, expected);
        if (expectedRank < 0) return Integer.MIN_VALUE;
        int bestAdvRank = Integer.MAX_VALUE;
        for (GoldQuery.Adversarial a : adversarial) {
            int r = firstRankOfName(rankedFiles, a.file());
            if (r >= 0 && r < bestAdvRank) bestAdvRank = r;
        }
        if (bestAdvRank == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return bestAdvRank - expectedRank;
    }

    /**
     * Per-type rank-gap breakdown. Returns the gap between expected and the best-ranked
     * adversarial of each type seen on this query. Types absent from {@code adversarial}
     * are simply not present in the returned map.
     */
    public static Map<AdversarialType, Integer> rankGapByType(
            List<String> rankedFiles, Set<String> expected, List<GoldQuery.Adversarial> adversarial) {
        int expectedRank = firstRankOf(rankedFiles, expected);
        Map<AdversarialType, Integer> out = new EnumMap<>(AdversarialType.class);
        if (expectedRank < 0) return out;
        Map<AdversarialType, Integer> bestPerType = new EnumMap<>(AdversarialType.class);
        for (GoldQuery.Adversarial a : adversarial) {
            int r = firstRankOfName(rankedFiles, a.file());
            if (r < 0) continue;
            Integer prev = bestPerType.get(a.type());
            if (prev == null || r < prev) bestPerType.put(a.type(), r);
        }
        for (Map.Entry<AdversarialType, Integer> e : bestPerType.entrySet()) {
            out.put(e.getKey(), e.getValue() - expectedRank);
        }
        return out;
    }

    private static int firstRankOf(List<String> rankedFiles, Set<String> targets) {
        for (int i = 0; i < rankedFiles.size(); i++) {
            if (targets.contains(rankedFiles.get(i))) return i;
        }
        return -1;
    }

    private static int firstRankOfName(List<String> rankedFiles, String target) {
        for (int i = 0; i < rankedFiles.size(); i++) {
            if (rankedFiles.get(i).equals(target)) return i;
        }
        return -1;
    }

    /** Aggregates a list of per-query doubles into a mean, ignoring NaN. */
    public static double mean(List<Double> xs) {
        double sum = 0;
        int n = 0;
        for (Double x : xs) {
            if (x == null || Double.isNaN(x)) continue;
            sum += x;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Aggregates a list of per-query integers into a mean, ignoring sentinels. */
    public static double meanInt(List<Integer> xs, int... excludeSentinels) {
        Set<Integer> excl = new java.util.HashSet<>();
        for (int s : excludeSentinels) excl.add(s);
        double sum = 0;
        int n = 0;
        for (Integer x : xs) {
            if (x == null || excl.contains(x)) continue;
            sum += x;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Counts how many entries equal {@code true} in a boolean list. Convenience for precision. */
    public static int countTrue(List<Boolean> xs) {
        int n = 0;
        for (Boolean b : xs) if (Boolean.TRUE.equals(b)) n++;
        return n;
    }

    /** Defensive copy of expected files as a Set for repeated lookups. */
    public static Set<String> expectedAsSet(GoldQuery q) {
        return new java.util.HashSet<>(q.expected());
    }

    /** Defensive copy of an empty arraylist for convenience. */
    public static <T> List<T> newList() { return new ArrayList<>(); }
}
