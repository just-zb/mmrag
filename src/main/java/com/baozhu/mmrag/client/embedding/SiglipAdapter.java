package com.baozhu.mmrag.client.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baozhu.mmrag.client.EmbeddingClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Multimodal embedding adapter backed by SigLIP-SO400M
 * ({@code google/siglip-so400m-patch14-384}, 1152-dim, sigmoid contrastive
 * loss; Zhai et al. 2023). The model is hosted behind a HuggingFace
 * Inference Endpoint and accessed over HTTPS — no local GPU is required.
 *
 * <p>Both towers share one HTTP client. The endpoint is expected to honour
 * the inputs:
 * <pre>
 *   POST /  {"inputs": ["text1", "text2"], "modality": "text"}
 *   POST /  {"inputs": "&lt;base64-image&gt;", "modality": "image",
 *            "content_type": "image/png"}
 * </pre>
 * and return {@code {"embeddings": [[float, ...], ...]}}.
 *
 * <p>Active when {@code multimodal.embedding.strategy=siglip} (the default).
 * Used directly by Architecture A. Architecture B's
 * {@code CaptionBasedAdapter} composes this adapter's text tower with
 * {@code ClaudeVisionClient} captioning.
 */
@Component
@ConditionalOnProperty(
        name = "multimodal.embedding.strategy",
        havingValue = "siglip",
        matchIfMissing = true)
@Primary
public class SiglipAdapter implements EmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(SiglipAdapter.class);
    private static final String VERSION_TAG =
            "siglip-so400m-patch14-384";

    @Value("${multimodal.embedding.siglip.endpoint}")
    private String endpoint;

    @Value("${multimodal.embedding.siglip.api-key}")
    private String apiKey;

    @Value("${multimodal.embedding.siglip.dim:1152}")
    private int dim;

    @Value("${multimodal.embedding.siglip.timeout-seconds:30}")
    private int timeoutSeconds;

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public SiglipAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.http = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        logger.info("SiglipAdapter initialised — endpoint: {}, dim: {}", endpoint, dim);
    }

    @Override
    public String modelVersion() {
        return VERSION_TAG;
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        Map<String, Object> body = Map.of(
                "inputs", texts,
                "modality", "text"
        );
        String response = http.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1)))
                .block(Duration.ofSeconds(timeoutSeconds));
        return parseEmbeddings(response);
    }

    @Override
    public float[] embedImage(byte[] imageBytes, String contentType) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> body = Map.of(
                "inputs", b64,
                "modality", "image",
                "content_type", contentType == null ? "application/octet-stream" : contentType
        );
        String response = http.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1)))
                .block(Duration.ofSeconds(timeoutSeconds));
        return parseEmbeddings(response).get(0);
    }

    private List<float[]> parseEmbeddings(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddings = root.get("embeddings");
            if (embeddings == null || !embeddings.isArray()) {
                throw new RuntimeException("SigLIP response missing 'embeddings' array");
            }
            List<float[]> out = new java.util.ArrayList<>(embeddings.size());
            for (JsonNode emb : embeddings) {
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    v[i] = (float) emb.get(i).asDouble();
                }
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SigLIP response: " + e.getMessage(), e);
        }
    }
}
