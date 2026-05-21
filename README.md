# karta-rag-map

RAG-based code cartographer for large Java codebases. Builds semantic vector maps of source repositories, visualises dependency graphs, and overlays test coverage to surface under-tested hot paths.

**Status:** `v0.1.0-SNAPSHOT` — early skeleton, work in progress.

## Goal

Map the Apache Kafka `core/` module (~150k LOC) into a navigable semantic atlas. Surface:
- which subsystems are well-covered by tests, which are not
- which call paths integration tests actually exercise vs which are dead code
- where the cyclomatic-complexity-to-coverage ratio is alarming

## Roadmap

**Phase A — local CLI (v0.1 → v0.3)**
- v0.1: skeleton + Ollama embedding pipeline + vector-store decision
- v0.2: source ingestion + chunking strategy for Java sources
- v0.3: query interface + dependency-graph visualisation (Obsidian-style)

**Phase B — cloud service (v0.4+)**
- v0.4: package as AWS Lambda + DynamoDB + API Gateway via Terraform
- v0.5: GitHub Action that runs Karta on PRs and emits carto-diff reports

## Stack (v0.1)

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21 (Corretto) | |
| Build | Gradle (Kotlin DSL) + wrapper | |
| RAG framework | [LangChain4j 1.15.0](https://docs.langchain4j.dev) | core + `langchain4j-community-lucene:1.15.0-beta25` |
| LLM runtime | [Ollama](https://ollama.com) | local-only in Phase A |
| Embedding model | `nomic-embed-text` (768 dim) | candidate; benchmark vs `jina-embeddings-v2-base-code` planned |
| Generation model | `qwen2.5-coder:7b` | for query-time reasoning, lands in v0.2 |
| Vector store | **Apache Lucene 9.x** via `langchain4j-community-lucene` | pure-JVM, hybrid BM25+vector in one index |
| CLI | picocli | |
| Tests | JUnit 5 | |

## Vector store — decision

**Locked: Apache Lucene 9.x via `langchain4j-community-lucene:1.15.0-beta25`** (pure JVM, embedded, zero ops).

### Why Lucene over the alternatives

| Candidate | LC4j module | Ops in Phase A | Hybrid BM25+vec | Verdict |
|---|---|---|---|---|
| **Lucene** | ✅ `langchain4j-community-lucene` | Zero | ✅ **same index, same library** | **picked** |
| JVector | ✅ `langchain4j-community-jvector` | Zero | ❌ vector-only; needs separate BM25 + RRF in app code | runner-up |
| Qdrant | ✅ `langchain4j-qdrant` | Requires Docker | ✅ native sparse+dense fusion | fallback if Lucene perf disappoints |
| pgvector | ✅ `langchain4j-pgvector` | Requires Postgres | ✅ via FTS | rejected — violates zero-ops constraint |
| LanceDB | ❌ no LC4j module | Java SDK remote-only as of 05/2026 | n/a | rejected |
| sqlite-vec | ❌ no LC4j module | JNI loader pain | manual | rejected |
| Chroma | ✅ `langchain4j-chroma` | Docker | limited | rejected — Docker-or-bust with no upside over Qdrant |
| Weaviate | ✅ `langchain4j-weaviate` | Docker (Java embedded mode does not exist) | ✅ | rejected |

The decisive factor: Lucene serves **both** v0.1 (vector-only) **and** v0.2 (BM25+vector hybrid) from a single embedded library with a single index — no app-side RRF, no second store. The alternative two-store architecture (JVector + Lucene-sidecar + Reciprocal Rank Fusion) is technically interesting but adds an operational and conceptual layer for no functional gain in a Phase A CLI tool.

### Pending validation

A throughput benchmark on the target machine (M5 Pro, 48 GB) with the Apache Kafka `core/` corpus is scheduled for v0.1.x. If ingest or p95 query latency disappoint at 150k chunks × 768 dim, the fallback is `langchain4j-qdrant` (Docker sidecar in Phase A, managed cloud in Phase B).

## Quick start

(Empty until `v0.1.0` is tagged.)

```bash
./gradlew run
```

## Article series

Each step within a milestone ships one article + matching commit. Article links land here as they go live.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
