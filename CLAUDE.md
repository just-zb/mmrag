# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PaiSmart (派聪明) is an enterprise-grade AI knowledge management system built with RAG (Retrieval-Augmented Generation) technology. It provides intelligent document processing and retrieval capabilities using a modern tech stack including Spring Boot, Vue 3, Elasticsearch, and AI services.

## Development Environment Setup

### Prerequisites
- Java 17
- Maven 3.8.6+
- Node.js 18.20.0+
- pnpm 8.7.0+
- MySQL 8.0
- Elasticsearch 8.10.0
- MinIO 8.5.12
- Kafka 3.2.1
- Redis 7.0.11
- Docker (optional for services)

### Quick Start with Docker
```bash
# Start all services
cd docs && docker-compose up -d

# Backend
mvn spring-boot:run

# Frontend
cd frontend && pnpm install && pnpm dev
```

## Common Development Commands

### Backend (Spring Boot)
```bash
# Run application
mvn spring-boot:run

# Build
mvn clean package

# Test
mvn test

# Run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Frontend (Vue 3 + TypeScript)
```bash
# Install dependencies
cd frontend && pnpm install

# Development server
pnpm dev

# Build for production
pnpm build

# Type checking
pnpm typecheck

# Linting
pnpm lint

# Preview build
pnpm preview
```

## Architecture Overview

### Backend Structure
```
src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java      # Main application entry
├── client/                       # External API clients (DeepSeek, Embedding)
├── config/                       # Configuration classes (Security, JWT, etc.)
├── consumer/                     # Kafka consumers for async processing
├── controller/                   # REST API endpoints
├── entity/                       # JPA entities
├── exception/                    # Custom exceptions
├── handler/                      # WebSocket handlers
├── model/                        # Domain models
├── repository/                   # Data access layer
├── service/                      # Business logic layer
└── utils/                        # Utility classes
```

### Frontend Structure
```
frontend/src/
├── assets/                       # Static assets (SVG, images)
├── components/                   # Reusable Vue components
├── layouts/                      # Page layouts
├── router/                       # Vue Router configuration
├── service/                      # API integration
├── store/                        # Pinia state management
├── views/                        # Page components
└── utils/                        # Utility functions
```

## Key Components

### Core Services
- **DocumentService**: Handles document upload, parsing, and management
- **ElasticsearchService**: Manages document indexing and search
- **VectorizationService**: Converts text to embeddings using AI models
- **ChatHandler**: Processes AI chat interactions with RAG
- **UserService**: User authentication and management
- **ConversationService**: Chat history and session management

### AI Integration
- **DeepSeek API**: Primary LLM for chat responses
- **Embedding API**: Text-embedding-v4 for document vectorization
- **RAG Pipeline**: Document → Chunk → Embedding → Search → Response

### Multi-tenant Architecture
- **Organization Tags**: Supports multi-tenant isolation
- **Permission System**: Public/private document access control
- **User-Organization Mapping**: Flexible user-to-org relationships

## Configuration Files

### Backend Configuration
- `application.yml`: Main configuration with database, Redis, Kafka, AI services
- `application-dev.yml`: Development-specific settings
- `application-docker.yml`: Docker deployment settings

### Frontend Configuration
- `vite.config.ts`: Vite build configuration
- `tsconfig.json`: TypeScript configuration
- `pnpm-workspace.yaml`: Workspace configuration for monorepo

## Database Schema

The application uses MySQL as the primary database with JPA/Hibernate for ORM. Key entities include:
- `User`: User accounts and authentication
- `FileUpload`: Document metadata and storage info
- `Conversation`: Chat sessions and history
- `OrganizationTag`: Multi-tenant organization structure
- `ChunkInfo`: Document chunks for vector search

## External Dependencies

### Services
- **Elasticsearch 8.10.0**: Document search and vector storage
- **Kafka 3.2.1**: Message queue for async file processing
- **Redis 7.0.11**: Caching and session management
- **MinIO 8.5.12**: File storage service
- **MySQL 8.0**: Primary database

### AI Services
- **DeepSeek API**: LLM for generating responses
- **DashScope Embedding**: Text-embedding-v4 for document vectorization

## Development Workflow

### Adding New Features
1. Backend: Create entity → repository → service → controller
2. Frontend: Create API service → store module → Vue component → router configuration
3. Update database schema if needed (JPA auto-generates DDL)
4. Test with both unit and integration tests

### API Development
- Follow RESTful conventions
- Use proper HTTP status codes
- Implement authentication/authorization via JWT
- Add request validation and error handling

### Frontend Development
- Use Vue 3 Composition API with TypeScript
- Follow established component patterns in `/src/components/`
- Use Pinia for state management
- Implement proper TypeScript types for API responses

## Testing

### Backend Testing
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=UserServiceTest

# Run with coverage
mvn clean verify
```

### Frontend Testing
```bash
# Type checking
pnpm typecheck

# Linting
pnpm lint

# Build verification
pnpm build
```

## Deployment

### Docker Deployment
```bash
# Build backend
mvn clean package

# Build frontend
cd frontend && pnpm build

# Start services
cd docs && docker-compose up -d
```

### Environment Variables
Key configuration variables that should be set in production:
- `JWT_SECRET_KEY`: JWT signing secret
- `DEEPSEEK_API_KEY`: DeepSeek API credentials
- `EMBEDDING_API_KEY`: Embedding service API key
- Database credentials and service URLs

## Security Considerations

- JWT-based authentication with Spring Security
- Role-based access control (admin/user)
- Organization-level data isolation
- File upload validation and size limits
- CORS configuration for frontend integration
- Input validation and sanitization

## Performance Considerations

- Elasticsearch for efficient document search
- Redis caching for frequently accessed data
- Kafka for asynchronous file processing
- File chunking for large document processing
- Vector embeddings for semantic search
- Connection pooling for database and external services

## Thesis-Driven Evolution

This branch (`main` on `git@github.com:just-zb/paiSmart.git`) carries the engineering work for an MSc thesis at the University of Helsinki, *Multimodal RAG Question-Answering Optimization for Enterprise Knowledge Bases*. The thesis treats this codebase as the backing system on which a multimodal-RAG ablation is run, so the code on this branch evolves from the text-only baseline towards the multimodal target architecture described below. Branches other than `main` (e.g. `study`) keep the original text-only state for reference.

The frontend Vue project is intentionally **not tracked on this branch** (kept on `study` only); it is gitignored here so the publish branch focuses on the Java backend that the thesis evaluates.

### Target Architecture (Multimodal RAG)

The thesis compares two architecturally-different multimodal-RAG implementations on top of the same hybrid retrieval pipeline:

- **Architecture A — Unified image-text vector space.** Text chunks via SigLIP text tower; images via SigLIP-SO400M image tower (`google/siglip-so400m-patch14-384`, 1152-dim). Text and image vectors live in one `dense_vector` field discriminated by `content_type ∈ {TEXT, IMAGE_UNIFIED}`.
- **Architecture B — Image-to-description, all-text retrieval.** For each extracted image, a vision-language model is prompted with the image *plus contextual signals* (section heading + preceding paragraph + following paragraph) to produce a 2–4 sentence description that anchors the visual content to the surrounding text. The description is embedded as a text chunk by the SigLIP text tower (`content_type = IMAGE_DESCRIPTION`). At query time retrieval is purely textual.
- **Retrieval modes.** Four runtime-selectable strategies: pure BM25, pure dense, weighted hybrid (α=0.5), hybrid + cross-encoder reranking (Cohere Rerank v3 by default).
- **Generation.** A vision-capable LLM (Claude 3.5 Sonnet by default) receives both the textual context and the retrieved images; the same image identifiers are streamed to the WebSocket frontend for inline display ("dual routing" of `image_uri`).
- **No co-located GPU service.** SigLIP is consumed via a HuggingFace Inference Endpoint, captioning and generation via the Anthropic API, reranking via the Cohere API. The Spring Boot server itself runs without a GPU.

### Implementation Roadmap

| # | Phase | Scope | Status |
|---|---|---|---|
| **P1a** | Image extraction (utility) | `DocxImageExtractor` walks `XWPFDocument` paragraphs, captures images with section heading + adjacent paragraph context. Pure utility, no DB / MinIO / ES side effects. | ✅ Done — `src/main/java/com/yizhaoqi/smartpai/utils/DocxImageExtractor.java` + `src/test/java/.../utils/DocxImageExtractorTest.java` |
| **P1b** | Image persistence | MinIO bucket `images/{fileMd5}/{n}.{ext}` upload; new MySQL table `image_chunk(file_md5, chunk_id, page_num, image_uri, caption, user_id, org_tag, is_public)`; per-tenant permission fields wired through. | ⏳ Next |
| **P1c** | ES schema extension | `vector` dim 2048 → 1152 (SigLIP-SO400M); add `content_type` (TEXT / IMAGE_UNIFIED / IMAGE_DESCRIPTION), `imageUri`, `caption`, `pageNum`. One-time reindex. | ⏳ |
| **P1d** | ParseService integration | Branch `.docx` ingestion: keep existing Tika text path, add POI image-extraction call, persist images, emit image-chunk records for embedding. PDF / `.pptx` / `.xlsx` paths remain Tika text-only and out of thesis scope. | ⏳ |
| **P2** | Embedding adapter + image embedder | Refactor `EmbeddingClient` into adapter interface (`DashScopeTextAdapter` (legacy) / `SiglipAdapter` / `CaptionBasedAdapter`). HF Inference Endpoint client for SigLIP image+text towers. Strategy switch in `application.yml`. | ⏳ |
| **P3** | Retrieval-strategy switch | Replace single `HybridSearchService.search()` with a `RetrievalStrategy` interface (`BM25Only`, `DenseOnly`, `WeightedHybrid(α)`, `HybridPlusRerank`). Cohere Rerank v3 client. Expose `mode`, `alpha`, `topK`, `rerankTopN` per request. | ⏳ |
| **P4** | Evaluation harness | `RagasTracer` Spring interceptor on the chat path emitting one JSON line per query: `{ts, mode, params, query, retrieved[], scores[], answer}`. Standalone Python evaluator computing the four Ragas metrics over the trace files. | ⏳ |

### Current Implementation Status (P1a)

`DocxImageExtractor` is a pure utility:
- Input: `InputStream` to a `.docx` file.
- Output: ordered `List<ExtractedImage>` where each image carries `sequence` (1-indexed), `fileName`, `contentType`, `data` (raw bytes), `sectionHeading` (closest preceding `Heading N`-styled paragraph, English or Chinese), `prevParagraph`, `nextParagraph`.
- Side effects: none. Persistence and indexing are deferred to P1b/P1c.
- Tests: 4 JUnit 5 tests build a `.docx` in memory using POI and verify single-image extraction with full context, multi-image document order, no-image documents, and document-boundary edge cases.

### Build Notes

The project targets Java 17 (`<java.version>17</java.version>` in `pom.xml`). If your default JVM is JDK 22+, run Maven with `JAVA_HOME` pinned to JDK 17:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=DocxImageExtractorTest
```

Lombok was bumped 1.18.30 → 1.18.36 in P1a for forward compatibility with newer JDKs, but Lombok still cannot fully process annotations under JDK 22+ on the maven-compiler-plugin path; staying on JDK 17 for build is currently required.

### Files added by P1a

```
pom.xml                                                       # +poi-ooxml 5.2.5, lombok 1.18.36
.gitignore                                                    # +.env, +frontend/
src/main/java/com/yizhaoqi/smartpai/utils/DocxImageExtractor.java
src/test/java/com/yizhaoqi/smartpai/utils/DocxImageExtractorTest.java
```

### Where the thesis side lives

Thesis source (LaTeX + figures) is in a separate repo at
`/Users/michael/Desktop/thesis/CSM-thesis-Bao_Zhu/` and is published at
`git@github.com:just-zb/thesis.git`. The thesis text deliberately
avoids naming this codebase ("the implemented system" / "the backing
system" / "our system") to satisfy the university's anti-plagiarism
naming convention; this repository's docs and code are free to use the
PaiSmart name internally.