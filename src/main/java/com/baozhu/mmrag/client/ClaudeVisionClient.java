package com.baozhu.mmrag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude client used in two distinct stages of the multimodal-RAG
 * pipeline:
 *
 * <ol>
 *   <li><strong>Captioning during ingestion</strong> (Architecture B). For
 *       each extracted image, {@code ImageCaptionService} composes a prompt
 *       containing the section heading + preceding paragraph + following
 *       paragraph + the image, calls
 *       {@link #caption(byte[], String, String)}, and indexes the returned
 *       2–4 sentence description.</li>
 *   <li><strong>Multimodal answer generation at query time</strong>. The chat
 *       handler calls {@link #generate(String, List, List)} with the user
 *       query, the retrieved text-chunk context, and the deduplicated list of
 *       retrieved {@code image_uri}s; Claude receives the text context plus
 *       {@code image_url} content blocks and produces the streamed answer.</li>
 * </ol>
 *
 * <p>Pinning a single VLM across both stages eliminates a category of
 * cross-VLM behavioural drift (see thesis §3.5). Alternative VLMs (GPT-4o,
 * Qwen-VL) can be swapped in behind this same client interface.
 *
 * <p>The Anthropic Messages API endpoint and key are read from
 * {@code multimodal.generator.*} keys in {@code application.yml}.
 */
@Component
public class ClaudeVisionClient {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeVisionClient.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${multimodal.generator.endpoint:https://api.anthropic.com/v1/messages}")
    private String endpoint;

    @Value("${multimodal.generator.api-key}")
    private String apiKey;

    @Value("${multimodal.generator.model:claude-3-5-sonnet-20241022}")
    private String model;

    @Value("${multimodal.generator.max-tokens:2000}")
    private int maxTokens;

    @Value("${multimodal.generator.temperature:0.3}")
    private double temperature;

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public ClaudeVisionClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.http = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        logger.info("ClaudeVisionClient initialised — model: {}, endpoint: {}", model, endpoint);
    }

    public String modelId() {
        return model;
    }

    /**
     * Synchronously call the Claude Messages API with a single image and a
     * caption-producing prompt. Used during ingestion.
     *
     * @param imageBytes  raw image bytes
     * @param contentType MIME type of {@code imageBytes}
     * @param prompt      pre-built text prompt that should already include the
     *                    section-heading + adjacent-paragraph context (built
     *                    by {@code ImageCaptionService})
     * @return the model's text response (the caption)
     */
    public String caption(byte[] imageBytes, String contentType, String prompt) {
        Map<String, Object> imageBlock = Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "base64",
                        "media_type", contentType == null ? "image/png" : contentType,
                        "data", Base64.getEncoder().encodeToString(imageBytes)
                )
        );
        Map<String, Object> textBlock = Map.of(
                "type", "text",
                "text", prompt
        );
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "temperature", temperature,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(imageBlock, textBlock)
                ))
        );

        String response = http.post()
                .uri(endpoint)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(60));

        return extractText(response);
    }

    /**
     * Streaming multimodal answer generation. Used at query time. Delivers a
     * vision-capable Claude prompt containing the user query, the retrieved
     * text context, and zero or more image URLs that were returned by
     * retrieval. Each image is fetched and inlined as an {@code image_url}
     * content block — this is Path 1 of the dual-routing design (see thesis
     * §3.5).
     *
     * @param query                   the original user question
     * @param textContext             concatenated retrieved text chunks
     * @param imageUrlsAlreadyInlined image URLs already converted to the
     *                                Anthropic content-block form by the
     *                                caller (since the caller may want to
     *                                resolve MinIO URIs to public URLs first)
     * @return streamed answer tokens
     */
    public Flux<String> generate(String query, String textContext, List<Map<String, Object>> imageUrlsAlreadyInlined) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>(imageUrlsAlreadyInlined);
        contentBlocks.add(Map.of(
                "type", "text",
                "text", buildPrompt(query, textContext)
        ));
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "temperature", temperature,
                "stream", true,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", contentBlocks
                ))
        );

        return http.post()
                .uri(endpoint)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToFlux(String.class)
                .map(this::extractStreamedDelta)
                .filter(s -> !s.isEmpty());
    }

    private String buildPrompt(String query, String textContext) {
        return "You are a knowledge-base assistant. Use the retrieved context "
                + "(text and any images shown) to answer the user's question. "
                + "Cite the visual evidence when it is the source of the answer.\n\n"
                + "Retrieved text context:\n" + textContext + "\n\n"
                + "User question: " + query;
    }

    private String extractText(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText("");
            }
            return "";
        } catch (Exception e) {
            logger.warn("Failed to parse Anthropic response: {}", e.getMessage());
            return "";
        }
    }

    private String extractStreamedDelta(String sseLine) {
        // Server-Sent Events parsing for Anthropic's content_block_delta events.
        // Returns the incremental text token, or empty string for non-text events.
        try {
            JsonNode evt = objectMapper.readTree(sseLine);
            if ("content_block_delta".equals(evt.path("type").asText())) {
                return evt.path("delta").path("text").asText("");
            }
        } catch (Exception ignored) {
            // not a JSON event line (e.g. SSE keep-alive)
        }
        return "";
    }
}
