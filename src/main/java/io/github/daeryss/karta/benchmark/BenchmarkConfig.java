package io.github.daeryss.karta.benchmark;

/**
 * Static configuration of one benchmark run. Each {@code E*} or {@code M*} experiment
 * varies exactly one of these knobs at a time (research-methodology.md §5.1).
 *
 * <p>Held as a record because runs are immutable once started — the harness validates
 * the config up-front and then never mutates it. Building several configs and running
 * them sequentially is how a sweep is expressed.</p>
 *
 * @param experimentId    e.g. {@code "E001"}, {@code "E003-3000-300"}, {@code "M001-pilot"}
 * @param chunkChars      recursive splitter target; ignored when {@code wholeFile == true}
 * @param overlapChars    recursive splitter overlap; ignored when {@code wholeFile == true}
 * @param wholeFile       if true, skip chunking and embed each file as a single segment
 * @param embeddingModel  Ollama model name, e.g. {@code "nomic-embed-text"}
 * @param ollamaUrl       base URL of the local Ollama daemon
 * @param topK            number of results to retrieve per query (must be ≥ 10 to support Recall@10)
 */
public record BenchmarkConfig(
        String experimentId,
        int chunkChars,
        int overlapChars,
        boolean wholeFile,
        String embeddingModel,
        String ollamaUrl,
        int topK
) {

    public BenchmarkConfig {
        if (experimentId == null || experimentId.isBlank())
            throw new IllegalArgumentException("experimentId is required");
        if (!wholeFile) {
            if (chunkChars <= 0) throw new IllegalArgumentException("chunkChars must be > 0 when chunking");
            if (overlapChars < 0) throw new IllegalArgumentException("overlapChars must be ≥ 0");
            if (overlapChars >= chunkChars)
                throw new IllegalArgumentException("overlapChars must be < chunkChars");
        }
        if (embeddingModel == null || embeddingModel.isBlank())
            throw new IllegalArgumentException("embeddingModel is required");
        if (ollamaUrl == null || ollamaUrl.isBlank())
            throw new IllegalArgumentException("ollamaUrl is required");
        if (topK < 10) throw new IllegalArgumentException("topK must be ≥ 10 to support Recall@10");
    }

    /** Human-readable chunking label for reports, e.g. {@code "3000/300"} or {@code "whole-file"}. */
    public String chunkLabel() {
        return wholeFile ? "whole-file" : (chunkChars + "/" + overlapChars);
    }
}
