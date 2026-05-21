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
| RAG framework | [LangChain4j 1.12.1](https://docs.langchain4j.dev) | |
| LLM runtime | [Ollama](https://ollama.com) | local-only in Phase A |
| Embedding model | `nomic-embed-text` (768 dim) | candidate; benchmark vs `jina-embeddings-v2-base-code` planned |
| Generation model | `qwen2.5-coder:7b` | for query-time reasoning, lands in v0.2 |
| Vector store | **TBD** — see below | |
| CLI | picocli | |
| Tests | JUnit 5 | |

## Vector store — under investigation

| Option | Type | Ops cost | Hybrid search | Notes |
|---|---|---|---|---|
| Lucene 9.x HNSW | Embedded JVM | Zero | Built-in (BM25 + vector) | Most natural fit for pure-Java tool |
| Qdrant (embedded) | Sidecar | Docker | Yes | Best raw performance |
| pgvector | External | Postgres | Yes (via FTS) | Standard if Postgres already in stack |
| LanceDB | Embedded Rust + JNI | Zero | Limited | Columnar storage, fast |
| sqlite-vec | Embedded via JNI | Zero | Manual | Simplest, Java integration immature |

Decision pending — tracked separately.

## Quick start

(Empty until `v0.1.0` is tagged.)

```bash
./gradlew run
```

## Article series

Each step within a milestone ships one article + matching commit. Article links land here as they go live.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
