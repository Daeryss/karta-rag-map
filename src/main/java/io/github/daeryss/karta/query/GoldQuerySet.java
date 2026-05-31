package io.github.daeryss.karta.query;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Root document of {@code gold-queries.yaml}. Wraps the per-query list with
 * provenance fields so a gold set carries enough metadata to be audited.
 *
 * @param corpusId  must match {@code CorpusSpec.id} this set is curated for
 * @param version   gold-set version (bump when queries are added / corrected)
 * @param author    who authored the set (single-author bias is a methodology threat)
 * @param queries   the gold queries themselves
 */
public record GoldQuerySet(
        @JsonProperty("corpus_id") String corpusId,
        String version,
        String author,
        List<GoldQuery> queries
) {}
