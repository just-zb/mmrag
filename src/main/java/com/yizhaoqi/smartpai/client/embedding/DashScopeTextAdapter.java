package com.yizhaoqi.smartpai.client.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Legacy text-only embedding adapter that wraps the Tongyi Qianwen
 * (DashScope) {@code text-embedding-v4} API. Preserved as the baseline
 * configuration for reproducing the original PaiSmart text-only behaviour.
 *
 * <p>Active only when {@code multimodal.embedding.strategy=dashscope}; under
 * the default {@code siglip} strategy this adapter is not registered and
 * {@link com.yizhaoqi.smartpai.client.embedding.SiglipAdapter} is used
 * instead. Marked {@link Primary} so legacy code that {@code @Autowire}s
 * {@code EmbeddingClient} resolves to this adapter when the active strategy
 * is {@code dashscope}.
 *
 * <p>Image embedding is not supported; {@link #embedImage(byte[], String)}
 * inherits the default {@link UnsupportedOperationException} from the
 * interface.
 */
@Component
@ConditionalOnProperty(name = "multimodal.embedding.strategy", havingValue = "dashscope")
@Primary
public class DashScopeTextAdapter implements EmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeTextAdapter.class);

    @Value("${embedding.api.model}")
    private String modelId;

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    @Value("${embedding.api.dimension:2048}")
    private int dimension;

    @Value("${embedding.api.url}")
    private String apiUrl;

    @Value("${embedding.api.key}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public DashScopeTextAdapter(WebClient embeddingWebClient, ObjectMapper objectMapper) {
        this.webClient = embeddingWebClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        logger.info("DashScopeTextAdapter initialised — model: {}, batch size: {}, dimension: {}, endpoint: {}",
                modelId, batchSize, dimension, apiUrl);
        if (apiKey == null || apiKey.isBlank() || !apiKey.startsWith("sk-")) {
            logger.warn("DashScope API key looks invalid (prefix: {})",
                    apiKey == null ? "null" : apiKey.substring(0, Math.min(10, apiKey.length())));
        }
    }

    @Override
    public String modelVersion() {
        return "dashscope/" + modelId;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            logger.debug("DashScope embed batch — count: {}", texts.size());
            List<float[]> all = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                String response = callApiOnce(sub);
                all.addAll(parseVectors(response));
            }
            return all;
        } catch (WebClientResponseException e) {
            logger.error("DashScope API error — status: {}, body: {}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new RuntimeException("DashScope embedding failed: HTTP "
                    + e.getStatusCode().value() + " — " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("DashScope call failed: {}", e.getMessage(), e);
            throw new RuntimeException("DashScope embedding failed: " + e.getMessage(), e);
        }
    }

    private String callApiOnce(List<String> batch) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelId);
        requestBody.put("input", batch);
        requestBody.put("dimension", dimension);
        requestBody.put("encoding_format", "float");

        return webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException)
                        .doBeforeRetry(signal -> logger.warn("Retry DashScope call — attempt: {}, error: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .block(Duration.ofSeconds(30));
    }

    private List<float[]> parseVectors(String response) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");
        if (data == null || !data.isArray()) {
            throw new RuntimeException("DashScope response missing 'data' array");
        }
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }
}
