package io.github.daeryss.karta.query;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * The four query types defined in research-methodology.md §4.1. A query's type
 * describes its <em>shape</em> (where the answer lives in the corpus). It is
 * orthogonal to the disambiguation flag (see {@link GoldQuery#hasAdversarial()}),
 * which is set independently per query in the context of a specific corpus.
 */
public enum QueryType {
    /** Answer lives in a single named class/file. */
    DIRECT_CONCEPT,
    /** Answer spans multiple files. */
    CROSS_CUTTING,
    /** Requires interpreting code intent. */
    BEHAVIOURAL,
    /** Phrasing sounds direct but the answer is fragmented. */
    ANTI_PATTERN;

    @JsonCreator
    public static QueryType fromString(String raw) {
        if (raw == null) throw new IllegalArgumentException("QueryType is required");
        // Normalise: lowercase, hyphens and spaces → underscore.
        String key = raw.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        return QueryType.valueOf(key.toUpperCase());
    }
}
