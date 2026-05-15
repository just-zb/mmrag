> ## Master's Thesis Companion Repository
>
> | | |
> |---|---|
> | **Author** | Bao Zhu |
> | **Student ID** | 019733216 |
> | **Email** | ice.michael.zhu@gmail.com |
> | **University** | University of Helsinki |
> | **Faculty** | Faculty of Science |
> | **Department** | Department of Computer Science |
> | **Programme** | MSc in Computer Science (CSM), Software Track |
> | **Supervisor** | Prof. Jiaheng Lu |
> | **Thesis title** | *Multimodal RAG Question-Answering Optimization for Enterprise Knowledge Bases* |
> | **Defense** | 12 May 2026 |
> | **Submission** | 20 May 2026 |
>
> This repository is the backing system for the thesis above. The
> thesis text refers to it with neutral noun phrases ("the implemented
> system" / "the proposed system" / "the backing system" / "our
> system") per the university's anti-plagiarism naming convention; the
> code on this branch is its actual implementation. Examiners and
> readers seeking the full source for the experimental setup of
> Chapter 4 should look here.

---

# mmrag

An enterprise multimodal retrieval-augmented question-answering (RAG) system. Ingests text and embedded images from `.docx` documents into a single Elasticsearch index, supports two ingestion architectures and four retrieval strategies behind one runtime switch, and generates answers with a vision-capable LLM that receives both the textual context and the retrieved images.

The system is designed to run on a single Spring Boot application server **without any co-located GPU service** — every model call (embedding, captioning, generation, reranking) is an HTTPS request to a managed endpoint.

---

## Architecture at a glance

```
┌─────────────── Ingestion (offline) ───────────────┐    ┌──────────── Query (online) ────────────┐
│                                                   │    │                                        │
│  .docx ──► Apache POI                             │    │  user query (WebSocket)                │
│           ├─ text chunks ──► SigLIP text tower ──┐│    │       │                                │
│           └─ images       ──► (A) SigLIP image  ││    │       ▼                                │
│              + section heading,                  ││    │  RetrievalStrategy switch              │
│              prev/next paragraph context         ││    │  ├─ BM25Only                           │
│                            ──► (B) Claude 3.5    ││    │  ├─ DenseOnly                          │
│                                  Sonnet caption ─┘│    │  ├─ WeightedHybrid(α=0.5)              │
│                                  + SigLIP text    │    │  └─ HybridPlusRerank                   │
│                                                   │    │       │       └─ Cohere Rerank v3      │
│  ┌─ MinIO  (images/{md5}/{n}.{ext})               │    │       ▼                                │
│  ├─ MySQL  (image_chunk + chunk_info)             │    │  top-K text + image references         │
│  └─ ES     (knowledge_base, dense_vector 1152,    │    │       │                                │
│             content_type ∈ {TEXT,                 │    │       ▼                                │
│             IMAGE_UNIFIED, IMAGE_DESCRIPTION})    │◄───┤  ┌─────────────────────┐               │
│                                                   │    │  │ Claude 3.5 Sonnet   │               │
└───────────────────────────────────────────────────┘    │  │  receives text +    │ ──► answer ──┐│
                                                         │  │  image_url blocks   │              ││
                                                         │  └─────────────────────┘              ││
                                                         │                                       ▼│
                                                         │  WebSocket frontend  ◄── image_refs ──┘│
                                                         └────────────────────────────────────────┘
```

## Two multimodal ingestion architectures

The system supports two architecturally-different ways to make image evidence retrievable, both running on top of the same hybrid retrieval pipeline. The choice is a runtime configuration:

| | Architecture A — Unified | Architecture B — Description |
|---|---|---|
| **Image embedding** | SigLIP-SO400M image tower (1152-dim) | None at the image step |
| **Image-side text** | None | Vision-language model (Claude 3.5 Sonnet) produces a 2–4 sentence caption from the image **plus its document context** (section heading + preceding paragraph + following paragraph). The caption is embedded as a text chunk by the SigLIP text tower. |
| **Index field** | `content_type = IMAGE_UNIFIED` | `content_type = IMAGE_DESCRIPTION` |
| **At query time** | Text and image vectors share one `dense_vector` field; query vector hits both | Retrieval is purely textual (no image vector branch is ever invoked) |
| **GPU requirement** | Image-tower service (HF Inference Endpoint) | None (captioning is a VLM API call) |

Both architectures converge on the same retrieval pipeline downstream and the same answer-generation pipeline upstream. The `image_uri` returned by retrieval is identical in both arms, so generation receives equivalent visual signal regardless of how the image was indexed.

## Four retrieval strategies

A `RetrievalStrategy` interface lets the same query be evaluated under any of the four configurations without code changes — only a request parameter (`mode`):

| `mode` | Description |
|---|---|
| `BM25_ONLY` | Pure sparse retrieval over `textContent` (English analyzer) |
| `DENSE_ONLY` | Pure kNN over the 1152-dim `vector` field |
| `WEIGHTED_HYBRID` | Dense kNN ⊕ BM25 with weighted-score fusion (α = 0.5 default; configurable per request) |
| `HYBRID_PLUS_RERANK` | `WEIGHTED_HYBRID` produces top-N=30 candidates → Cohere Rerank v3 cross-encoder rescores → top-K=5 returned |

Per-tenant permission filters (`userId` / `orgTag` / `isPublic`) are applied at the candidate-selection stage in every strategy.

## Image dual-routing at generation

When a query retrieves both text chunks and image chunks (Architecture A or B), the resulting `image_uri` list goes down two paths in parallel:

1. **Path 1 — LLM context.** Each unique `image_uri` is fetched from MinIO and added to the chat-completion call as an `image_url` content block, alongside the text context. The vision-capable generator (Claude 3.5 Sonnet) thus reasons over both the textual context and the actual pixels.
2. **Path 2 — UI rendering.** The same `image_uri` list is sent to the frontend over WebSocket as an `image_refs` field; the chat UI renders the images inline next to the streamed answer so the user sees the retrieved visual evidence directly.

Both architectures (A and B) converge on this delivery layer.

## Stack

### Backend
- Java 17 / Spring Boot 3.4.2
- Apache POI 5.2.5 (`.docx` text + image extraction with paragraph-context capture)
- Apache Tika 2.9.1 (legacy text-only extraction path; retained for non-`.docx` formats)
- Elasticsearch 8.10 (`dense_vector` 1152-dim, BM25 + kNN in one index, `content_type` discriminator)
- MinIO 8.5.12 (object storage for source documents and extracted images)
- MySQL 8.0 (`image_chunk` table joins extracted image rows to MinIO URIs and tenant ownership; `chunk_info` for text)
- Apache Kafka 3.2.1 (asynchronous ingestion pipeline)
- Redis (token cache, organisation-tag cache)
- WebSocket (real-time chat with `image_refs` streamed to the UI)
- Spring Security + JWT (authentication and per-tenant permission scoping)

### Model services (all HTTPS, no co-located GPU)
- **SigLIP-SO400M** via HuggingFace Inference Endpoint — text + image embedding (1152-dim, sigmoid contrastive)
- **Claude 3.5 Sonnet** via Anthropic API — image captioning during ingestion **and** multimodal answer generation
- **Cohere Rerank v3** via Cohere API — cross-encoder reranking for the `HYBRID_PLUS_RERANK` strategy
- *(legacy)* DashScope `text-embedding-v4` — kept as the `DashScopeTextAdapter` for the text-only baseline configuration

## Project layout

```
src/main/java/com/baozhu/mmrag/
├── MmragApplication.java
├── client/
│   ├── ClaudeVisionClient.java          # Anthropic API: captioning + multimodal generation
│   ├── CohereRerankerClient.java        # Cohere Rerank v3 cross-encoder
│   ├── EmbeddingClient.java             # interface for embedding adapters
│   └── DeepSeekClient.java              # legacy text-only generator
├── client/embedding/
│   ├── SiglipAdapter.java               # SigLIP-SO400M (text + image towers)
│   ├── CaptionBasedAdapter.java         # Architecture B path: caption → SigLIP text tower
│   └── DashScopeTextAdapter.java        # legacy text-only baseline
├── service/
│   ├── ParseService.java                # ingests .docx via Tika (text) + DocxImageExtractor (images)
│   ├── ImageCaptionService.java         # Architecture B captioning with full document context
│   ├── ImageStorageService.java         # MinIO upload for extracted images
│   ├── HybridSearchService.java         # legacy single-mode search (kept for compatibility)
│   └── ...
├── service/retrieval/
│   ├── RetrievalStrategy.java           # interface
│   ├── Bm25OnlyStrategy.java
│   ├── DenseOnlyStrategy.java
│   ├── WeightedHybridStrategy.java
│   └── HybridPlusRerankStrategy.java
├── interceptor/
│   └── RagasTracerInterceptor.java      # one JSON line per query for offline Ragas
├── entity/
│   ├── EsDocument.java                  # ES indexed document (text or image, discriminated by content_type)
│   └── ImageChunk.java                  # MySQL image_chunk row (md5, idx, page, image_uri, caption, tenant fields)
├── utils/
│   └── DocxImageExtractor.java          # POI XWPFDocument image extraction with paragraph-context capture
├── config/
│   └── MultimodalProperties.java        # binding for embedding.strategy / retrieval defaults
└── ...

src/main/resources/
├── application.yml                      # multimodal model endpoints + strategy switches
├── es-mappings/
│   └── knowledge_base.json              # dense_vector 1152, content_type discriminator
└── ...
```

## Configuration highlights

```yaml
multimodal:
  embedding:
    strategy: siglip          # siglip | caption | dashscope
    siglip:
      endpoint: ${SIGLIP_ENDPOINT}
      api-key:  ${SIGLIP_API_KEY}
      dim: 1152
    captioning:
      provider: claude        # claude | gpt-4o | qwen-vl
  retrieval:
    default-mode: HYBRID_PLUS_RERANK
    default-top-k: 5
    rerank-pool-size: 30
    hybrid-alpha: 0.5
  reranker:
    provider: cohere          # cohere | jina | none
    endpoint: https://api.cohere.com/v1/rerank
    model: rerank-multilingual-v3.0
  generator:
    provider: claude          # claude | gpt-4o | deepseek
    model: claude-3-5-sonnet-20241022
    image-routing: both       # llm | ui | both
```

## Building and running

```bash
# Backend
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean package
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn spring-boot:run

# Tests
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Lombok annotations require JDK 17 on the build path; default JDK 22 fails at compile time.

## Status

This branch is the multimodal-RAG codebase that backs the MSc thesis *Multimodal RAG Question-Answering Optimization for Enterprise Knowledge Bases* (University of Helsinki, 2026). The thesis evaluates the system end-to-end under a 3 × 4 full-factorial design (3 ingestion architectures × 4 retrieval strategies × 6 metrics × 30 QA pairs).

The roadmap and per-phase status are tracked in [CLAUDE.md](./CLAUDE.md).
