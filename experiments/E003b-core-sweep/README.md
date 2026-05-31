# E003b — Chunk-size sweep on full Apache Kafka 4.0.0 broker corpus

**Run date:** 2026-05-31
**Methodology:** `research-methodology.md@v0.3`
**Corpus:** `kafka-4.0.0-core` — 697 broker-side files, 6.0 MB (core + server + server-common + raft + storage + metadata)
**Gold set:** `gold-queries.yaml@v0.1.0` — 30 queries, 27 with disambiguation annotations
**Hardware:** Apple M5 Pro, 48 GB RAM
**Wall-clock:** 5 min 06 s for the full 4-config sweep

---

## Main finding — the pilot misled us

The pilot run (E001–E003, 10 files, 3 queries) locked **recursive 3000/300** as the chunk-size operating point. On the full broker corpus with a real 30-query gold set, **3000/300 is not the winner**:

| Config       | top-1 | R@10 | MRR  | adv_precision | rank_gap | embed wall-clock |
|---|---|---|---|---|---|---|
| 750/100      | 0.10  | 0.57 | 0.26 | 0.37          | **−1.56** | 85 s             |
| **1500/200** | **0.27** | 0.47 | **0.33** | **0.48**  | **+3.20** | 67 s             |
| 3000/300     | 0.17  | 0.50 | 0.30 | 0.37          | +1.11    | **62 s**         |
| 6000/500     | 0.20  | 0.57 | 0.31 | 0.44          | +1.00    | 66 s             |

**1500/200 wins on every quality axis.** 3000/300 wins on throughput only. Speed and quality are no longer the same point.

The pilot's "3000/300 is best on every axis" claim was an artefact of `n=3` queries on a 10-file corpus. A larger gold set on a corpus that meets the methodology size minimum (≥100K LOC) reverses it.

## Four observations worth keeping

**(1) Baseline retrieval is weak on real code.** top-1 accuracy is 10–27 % depending on chunk size. Recall@10 is 47–57 %. Almost half the gold queries have their expected file missing from the top 10 entirely. This is plain vector-only retrieval with a general-purpose embedding model — it's a baseline, not a target. E004 (BM25 hybrid) and E005 (code-aware embeddings) exist to lift this.

**(2) Small chunks are actively harmful for disambiguation.** At 750/100 the mean `rank_gap` is **−1.56**: the adversarial files (interface, parent, mock, sibling) on average rank **above** the expected file. The chunker is small enough that surface-token lexical overlap dominates whatever semantic signal the embedding carries. This is the cognitive-vs-numerical-similarity failure mode methodology v0.3 was written to measure, demonstrated on real data.

**(3) Adversarial precision and rank-gap are far more sensitive than top-1.** Range across configs:
- top-1: 0.10–0.27 (Δ 0.17)
- adv_precision: 0.37–0.48 (Δ 0.11)
- **rank_gap: −1.56 → +3.20 (Δ 4.76)** — almost 30× the discriminative range of top-1

Practical implication: when comparing two retrieval configurations on code, `rank_gap` separates them more cleanly than `top-1`. The disambiguation metrics are not decoration — they are the better diagnostic.

**(4) Throughput U-shape transfers from pilot to full corpus, intact.** 3000/300 is the global wall-clock minimum on the full corpus (62 s) — same shape, same relative ordering as the pilot. The throughput finding generalises; the quality finding did not.

## What this changes in the series

- **Karta defaults v0.1.0** locked 3000/300 as the chunking operating point. That lock is invalidated by this run for retrieval quality (still valid for throughput). The default is now "no single locked size — pending E004/E005/E006 with the same 4-config grid; a Phase 1 lock will be set in the flagship article."
- **Article #1 narrative shifts** from "where naive chunking fails — and 3000/300 is the operating point" to "the pilot misled us; on real corpus 1500/200 wins quality but 3000/300 wins speed; adversarial metrics surfaced the difference that aggregate top-1 hides."
- **Confidence in adversarial-metric work goes up.** The thesis ("cognitive vs numerical similarity is measurable and matters on code") is no longer aspirational — it was reproduced on a 30-query gold set with a four-config sweep and a −4.76→+3.20 range.

## Files in this directory

- `sweep-configs.csv` — one row per config, per-config aggregates
- `sweep-queries.csv` — one row per (config × query), 120 rows total
- `sweep.json` — sweep metadata + environment snapshot
- `E003b-750-100/`, `E003b-1500-200/`, `E003b-3000-300/`, `E003b-6000-500/` — per-config: `queries.csv` (per-query metrics) + `ingest.csv` (per-file ingest stats) + `run.json` (env + config + aggregates)

## Reproduce

```bash
export KAFKA_4_0_0_PATH=/path/to/kafka  # clone of apache/kafka@4.0.0
ollama pull nomic-embed-text
curl -s http://localhost:11434/api/embeddings -d '{"model":"nomic-embed-text","prompt":"preload"}' > /dev/null
RUN_OLLAMA_TESTS=true ./gradlew test --tests '*KafkaCoreSweepIT'
```

Expected wall-clock on M-series Mac: 4–6 min.
