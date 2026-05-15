package com.baozhu.mmrag.service;

import com.baozhu.mmrag.client.ClaudeVisionClient;
import com.baozhu.mmrag.client.EmbeddingClient;
import com.baozhu.mmrag.config.MultimodalProperties;
import com.baozhu.mmrag.entity.EsDocument;
import com.baozhu.mmrag.exception.CustomException;
import com.baozhu.mmrag.interceptor.RagasTracerInterceptor;
import com.baozhu.mmrag.model.User;
import com.baozhu.mmrag.repository.UserRepository;
import com.baozhu.mmrag.service.retrieval.RetrievalRequest;
import com.baozhu.mmrag.service.retrieval.RetrievalStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.InputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Thesis-aligned chat service. Replaces the legacy
 * {@link com.baozhu.mmrag.service.ChatHandler#processMessage} flow with the
 * five-step pipeline described in thesis §3.5–§3.6:
 *
 * <ol>
 *   <li>Embed the user query into the same SigLIP space as the indexed
 *       chunks via {@link EmbeddingClient}.</li>
 *   <li>Dispatch to one of the four {@link RetrievalStrategy} implementations
 *       per the configured retrieval mode (or per a request-level override).</li>
 *   <li>Split retrieved hits into a text context (concatenated
 *       {@code textContent}) and a deduplicated list of {@code imageUri}s.</li>
 *   <li>Image dual-routing: send {@code image_refs} to the WebSocket
 *       client (Path 2) and inline the same images as Anthropic
 *       {@code image_url} content blocks for {@link ClaudeVisionClient}
 *       (Path 1). Both architectures (A and B) converge here.</li>
 *   <li>Stream Claude's answer back over the WebSocket; on completion,
 *       emit one structured trace line via {@link RagasTracerInterceptor}
 *       (when enabled) so the offline Python evaluator can compute the
 *       four Ragas metrics over the trace file.</li>
 * </ol>
 *
 * <p>Tenant resolution (userId → userDbId + effective org tags) follows the
 * same convention as the legacy {@code HybridSearchService} so the
 * permission filter behaves identically across the legacy and the
 * thesis-aligned chat paths.
 */
@Service
public class MultimodalChatService {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalChatService.class);

    private final EmbeddingClient embeddingClient;
    private final ClaudeVisionClient claudeVisionClient;
    private final Map<RetrievalStrategy.Mode, RetrievalStrategy> strategies;
    private final MultimodalProperties multimodalProperties;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;
    private final RagasTracerInterceptor ragasTracer; // optional

    public MultimodalChatService(EmbeddingClient embeddingClient,
                                 ClaudeVisionClient claudeVisionClient,
                                 List<RetrievalStrategy> strategyBeans,
                                 MultimodalProperties multimodalProperties,
                                 UserRepository userRepository,
                                 OrgTagCacheService orgTagCacheService,
                                 MinioClient minioClient,
                                 ObjectMapper objectMapper,
                                 org.springframework.beans.factory.ObjectProvider<RagasTracerInterceptor>
                                         ragasTracerProvider) {
        this.embeddingClient = embeddingClient;
        this.claudeVisionClient = claudeVisionClient;
        this.strategies = new EnumMap<>(RetrievalStrategy.Mode.class);
        strategyBeans.forEach(s -> this.strategies.put(s.mode(), s));
        this.multimodalProperties = multimodalProperties;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.minioClient = minioClient;
        this.objectMapper = objectMapper;
        this.ragasTracer = ragasTracerProvider.getIfAvailable();
    }

    /**
     * Run the multimodal chat pipeline for one user message and stream the
     * answer back over the given WebSocket session. Blocking; intended to
     * be called from {@code ChatWebSocketHandler.handleTextMessage}.
     */
    public void processMultimodalMessage(String userId, String userMessage,
                                          WebSocketSession session) {
        // 1. Resolve tenant context
        String userDbId;
        List<String> userOrgTags;
        try {
            userDbId = resolveUserDbId(userId);
            userOrgTags = resolveUserEffectiveOrgTags(userId);
        } catch (Exception e) {
            logger.error("Tenant resolution failed for {}", userId, e);
            sendError(session, "Tenant resolution failed: " + e.getMessage());
            return;
        }

        // 2. Embed the user query
        float[] queryVector;
        try {
            queryVector = embeddingClient.embedOne(userMessage);
        } catch (Exception e) {
            logger.warn("Query embedding failed; falling back to BM25_ONLY: {}", e.getMessage());
            queryVector = null;
        }

        // 3. Build the retrieval request
        MultimodalProperties.Retrieval rcfg = multimodalProperties.getRetrieval();
        RetrievalStrategy.Mode mode = parseMode(rcfg.getDefaultMode(), queryVector == null);
        RetrievalRequest request = new RetrievalRequest(
                userMessage,
                queryVector,
                rcfg.getDefaultTopK(),
                rcfg.getHybridAlpha(),
                rcfg.getRerankPoolSize(),
                userDbId,
                userOrgTags);

        // 4. Dispatch to the strategy
        RetrievalStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            sendError(session, "Retrieval mode not registered: " + mode);
            return;
        }
        List<EsDocument> retrieved;
        try {
            retrieved = strategy.search(request);
        } catch (Exception e) {
            logger.error("Retrieval strategy {} failed", mode, e);
            sendError(session, "Retrieval failed: " + e.getMessage());
            return;
        }

        // 5. Split retrieved hits into text context + deduplicated image URIs
        String textContext = buildTextContext(retrieved);
        List<String> imageUris = retrieved.stream()
                .map(EsDocument::getImageUri)
                .filter(Objects::nonNull)
                .filter(u -> !u.isBlank())
                .distinct()
                .collect(Collectors.toList());

        // 6. Image dual-routing — Path 2: image_refs to UI
        if (!imageUris.isEmpty()) {
            sendImageRefs(session, imageUris);
        }

        // 7. Image dual-routing — Path 1: Anthropic image_url content blocks
        List<Map<String, Object>> imageBlocks = imageUris.stream()
                .map(this::buildImageContentBlock)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 8. Stream the multimodal answer
        StringBuilder fullAnswer = new StringBuilder();
        try {
            claudeVisionClient.generate(userMessage, textContext, imageBlocks)
                    .doOnNext(token -> {
                        fullAnswer.append(token);
                        sendChunk(session, token);
                    })
                    .blockLast();
            sendComplete(session);
        } catch (Exception e) {
            logger.error("Claude generation failed", e);
            sendError(session, "Generation failed: " + e.getMessage());
            // Continue to tracer so the failure is recorded in the trace.
        }

        // 9. Emit the Ragas trace
        if (ragasTracer != null) {
            try {
                ragasTracer.trace(
                        mode.name(),
                        rcfg.getDefaultTopK(),
                        rcfg.getHybridAlpha(),
                        rcfg.getRerankPoolSize(),
                        userMessage,
                        retrieved,
                        fullAnswer.toString());
            } catch (Exception e) {
                logger.warn("Ragas trace emission failed: {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private RetrievalStrategy.Mode parseMode(String configured, boolean noVector) {
        try {
            RetrievalStrategy.Mode m = RetrievalStrategy.Mode.valueOf(configured);
            // Auto-fallback: if vectorisation failed, downgrade vector-using
            // modes to BM25_ONLY so the chat still answers something.
            if (noVector && m != RetrievalStrategy.Mode.BM25_ONLY) {
                logger.warn("No query vector — overriding mode {} -> BM25_ONLY", m);
                return RetrievalStrategy.Mode.BM25_ONLY;
            }
            return m;
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown retrieval mode '{}', falling back to HYBRID_PLUS_RERANK", configured);
            return RetrievalStrategy.Mode.HYBRID_PLUS_RERANK;
        }
    }

    private String buildTextContext(List<EsDocument> retrieved) {
        return retrieved.stream()
                .map(EsDocument::getTextContent)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Build an Anthropic Messages API {@code image} content block from a
     * MinIO URI of the form {@code minio://<bucket>/<key>}. Fetches the
     * bytes and base64-encodes them so the vision model receives the image
     * inline (the MinIO bucket is typically not public).
     *
     * @return null on fetch failure (the answer still streams without that
     *         image)
     */
    private Map<String, Object> buildImageContentBlock(String minioUri) {
        try {
            // Strip "minio://" prefix and split bucket/key.
            String stripped = minioUri.startsWith("minio://")
                    ? minioUri.substring("minio://".length())
                    : minioUri;
            int slash = stripped.indexOf('/');
            if (slash < 0) {
                logger.warn("Malformed MinIO URI (no bucket/key separator): {}", minioUri);
                return null;
            }
            String bucket = stripped.substring(0, slash);
            String key = stripped.substring(slash + 1);
            try (InputStream s = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(key).build())) {
                byte[] bytes = s.readAllBytes();
                String mediaType = guessMediaType(key);
                return Map.of(
                        "type", "image",
                        "source", Map.of(
                                "type", "base64",
                                "media_type", mediaType,
                                "data", Base64.getEncoder().encodeToString(bytes)));
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch image {} for inlining: {}", minioUri, e.getMessage());
            return null;
        }
    }

    private static String guessMediaType(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private void sendImageRefs(WebSocketSession session, List<String> imageUris) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "image_refs");
        message.put("image_refs", imageUris);
        sendJson(session, message);
    }

    private void sendChunk(WebSocketSession session, String token) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "chunk");
        message.put("content", token);
        sendJson(session, message);
    }

    private void sendComplete(WebSocketSession session) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "complete");
        sendJson(session, message);
    }

    private void sendError(WebSocketSession session, String error) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "error");
        message.put("error", error);
        sendJson(session, message);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> message) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            logger.warn("WebSocket send failed: {}", e.getMessage());
        }
    }

    // Tenant resolution (mirrors HybridSearchService's helpers so the same
    // permission semantics apply across the legacy and multimodal paths).

    private String resolveUserDbId(String userId) {
        try {
            Long.parseLong(userId);
            return userId; // already a numeric id
        } catch (NumberFormatException e) {
            User user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            return user.getId().toString();
        }
    }

    private List<String> resolveUserEffectiveOrgTags(String userId) {
        try {
            User user;
            try {
                Long uid = Long.parseLong(userId);
                user = userRepository.findById(uid)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            }
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.warn("Org-tag resolution failed for {}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
