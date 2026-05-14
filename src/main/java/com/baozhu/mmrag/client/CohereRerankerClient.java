package com.baozhu.mmrag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-encoder reranker backed by Cohere Rerank v3
 * ({@code rerank-multilingual-v3.0} by default). Used by the
 * {@code HybridPlusRerank} retrieval strategy: the first stage produces a
 * top-N candidate pool (default N=30) from weighted hybrid retrieval, and
 * this client rescores the pool with the cross-encoder, returning the
 * permutation indices in descending relevance order.
 *
 * <p>Active when {@code multimodal.reranker.provider=cohere}.
 *
 * <p>Network calls are blocking — invoked synchronously in the request
 * thread. The endpoint is expected to follow the v1 API surface
 * {@code POST https://api.cohere.com/v1/rerank}.
 */
@Component
@ConditionalOnProperty(
        name = "multimodal.reranker.provider",
        havingValue = "cohere",
        matchIfMissing = true)
public class CohereRerankerClient {

    private static final Logger logger = LoggerFactory.getLogger(CohereRerankerClient.class);

    @Value("${multimodal.reranker.endpoint:https://api.cohere.com/v1/rerank}")
    private String endpoint;

    @Value("${multimodal.reranker.api-key}")
    private String apiKey;

    @Value("${multimodal.reranker.model:rerank-multilingual-v3.0}")
    private String model;

    @Value("${multimodal.reranker.timeout-seconds:15}")
    private int timeoutSeconds;

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public CohereRerankerClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.http = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        logger.info("CohereRerankerClient initialised — model: {}, endpoint: {}", model, endpoint);
    }

    /**
     * Rerank a list of candidate documents against a query and return the
     * indices of the top {@code topK} candidates in descending relevance
     * order. The {@code candidates} list is not mutated.
     *
     * @param query      the user query string
     * @param candidates indexable text payloads (typically the
     *                   {@code textContent} of each top-N hit; for
     *                   IMAGE_UNIFIED rows the caller substitutes a
     *                   placeholder or the section heading)
     * @param topK       number of indices to return (caller clips to
     *                   {@code candidates.size()} if larger)
     * @return list of indices into {@code candidates}, length min(topK, size),
     *         in descending relevance order
     */
    public List<Integer> rerank(String query, List<String> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "query", query,
                "documents", candidates,
                "top_n", Math.min(topK, candidates.size()),
                "return_documents", false
        );
        try {
            String response = http.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));
            return parseIndices(response);
        } catch (Exception e) {
            logger.error("Cohere rerank failed: {}", e.getMessage(), e);
            // Fall back to identity ordering so retrieval still returns
            // something usable even if the reranker is unavailable.
            List<Integer> fallback = new ArrayList<>();
            int n = Math.min(topK, candidates.size());
            for (int i = 0; i < n; i++) fallback.add(i);
            return fallback;
        }
    }

    private List<Integer> parseIndices(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            throw new RuntimeException("Cohere response missing 'results' array");
        }
        List<Integer> indices = new ArrayList<>(results.size());
        for (JsonNode r : results) {
            indices.add(r.get("index").asInt());
        }
        return indices;
    }
}
