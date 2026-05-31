package io.github.daeryss.karta.query;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Why an adversarial file is plausibly close to the expected answer but
 * semantically wrong. See research-methodology.md §4.1 (Disambiguation flag).
 *
 * <p>The type is reported per-adversarial in the gold set so the rank-gap metric
 * can be broken down by category — "model confuses interface 40 % of the time,
 * mock 10 %" is more actionable than a single aggregate.</p>
 */
public enum AdversarialType {
    /** The expected file implements this interface. */
    INTERFACE,
    /** Expected extends this base class. */
    PARENT,
    /** A subclass of the expected file. */
    CHILD,
    /** Test double / stub / fake of the expected behaviour. */
    MOCK,
    /** Configuration file for the expected component. */
    CONFIG,
    /** Peer implementation of the same interface. */
    SIBLING;

    @JsonCreator
    public static AdversarialType fromString(String raw) {
        if (raw == null) throw new IllegalArgumentException("AdversarialType is required");
        return AdversarialType.valueOf(raw.trim().toUpperCase());
    }
}
