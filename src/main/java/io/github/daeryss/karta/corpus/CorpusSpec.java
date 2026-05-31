package io.github.daeryss.karta.corpus;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Static description of a corpus loaded from {@code corpus.yaml}.
 *
 * <p>Two source modes are supported:</p>
 * <ul>
 *   <li>{@code files} — explicit list of relative paths fetched from {@code baseUrl} (HTTP raw).
 *       Suitable for small curated corpora (pilots, reference runs).</li>
 *   <li>{@code basePath} + {@code include}/{@code exclude} — local filesystem walk over a
 *       pre-cloned repository. Suitable for large corpora where listing files explicitly
 *       is impractical.</li>
 * </ul>
 *
 * <p>Exactly one mode must be active per corpus; the loader rejects ambiguous specs.</p>
 *
 * @param id        unique identifier, e.g. {@code "kafka-4.0.0-pilot"} or {@code "kafka-4.0.0-core"}
 * @param name      human-readable name shown in reports
 * @param tag       upstream version tag (e.g. {@code "4.0.0"}) — pinned for reproducibility
 * @param baseUrl   HTTP base URL for {@code files} mode (e.g. raw.githubusercontent.com path)
 * @param basePath  local filesystem root for {@code basePath} mode
 * @param files     explicit file list ({@code files} mode); each entry is a relative path
 * @param include   glob patterns to include ({@code basePath} mode), e.g. {@code "**\/*.java"}
 * @param exclude   glob patterns to exclude ({@code basePath} mode), e.g. {@code "**\/test/**"}
 */
public record CorpusSpec(
        String id,
        String name,
        String tag,
        @JsonProperty("base_url")   String baseUrl,
        @JsonProperty("base_path")  String basePath,
        List<String> files,
        List<String> include,
        List<String> exclude
) {
    public boolean isFilesMode()    { return files != null && !files.isEmpty(); }
    public boolean isBasePathMode() { return basePath != null && !basePath.isBlank(); }
}
