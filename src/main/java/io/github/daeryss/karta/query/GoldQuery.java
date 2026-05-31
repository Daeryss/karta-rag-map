package io.github.daeryss.karta.query;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One annotated gold query — natural-language question plus the labels needed
 * to evaluate a retrieval system against it. Format matches research-methodology.md
 * §4.1 (gold query sets + disambiguation flag).
 *
 * @param id              stable identifier within the gold set (e.g. {@code "Q01"})
 * @param query           the natural-language question, verbatim
 * @param type            shape of the question (see {@link QueryType})
 * @param expected        one or more relative file paths that constitute the correct answer
 * @param rationale       short prose explaining why those files are the answer (for human review / PR audit)
 * @param hasAdversarial  whether the query carries an adversarial annotation (disambiguation flag)
 * @param adversarial     adversarial files that must rank below {@code expected}; only meaningful when {@code hasAdversarial}
 */
public record GoldQuery(
        String id,
        String query,
        QueryType type,
        List<String> expected,
        String rationale,
        @JsonProperty("has_adversarial") boolean hasAdversarial,
        List<Adversarial> adversarial
) {

    /** One adversarial alternative attached to a {@link GoldQuery}. */
    public record Adversarial(
            String file,
            AdversarialType type
    ) {}

    public List<Adversarial> adversarialOrEmpty() {
        return adversarial == null ? List.of() : adversarial;
    }
}
