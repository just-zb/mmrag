package com.baozhu.mmrag.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baozhu.mmrag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits one structured JSON line per chat-handler query so an external
 * Python evaluator can compute Ragas metrics offline. The trace records the
 * exact retrieval-strategy parameters, the retrieved chunk identifiers, the
 * generator output, and the modality breakdown of the retrieved chunks; the
 * evaluator then joins each line against a curated QA set on the query
 * field and runs Faithfulness / Answer Relevance / Context Recall / Context
 * Precision over the joined records.
 *
 * <p>Each line conforms to the schema:
 * <pre>
 * {
 *   "ts":         "2026-05-15T03:14:00Z",
 *   "mode":       "HYBRID_PLUS_RERANK",
 *   "params":     { "topK": 5, "alpha": 0.5, "rerankPoolSize": 30 },
 *   "query":      "...",
 *   "retrieved":  [
 *     { "fileMd5": "...", "chunkId": 1, "contentType": "TEXT",
 *       "imageUri": null, "score": 0.87 },
 *     ...
 *   ],
 *   "answer":     "...",
 *   "modality_breakdown": { "TEXT": 3, "IMAGE_UNIFIED": 0,
 *                           "IMAGE_DESCRIPTION": 2 }
 * }
 * </pre>
 *
 * <p>Active when {@code multimodal.tracer.enabled=true}. The trace file
 * path is {@code multimodal.tracer.output-path}.
 */
@Component
@ConditionalOnProperty(name = "multimodal.tracer.enabled", havingValue = "true")
public class RagasTracerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RagasTracerInterceptor.class);

    @Value("${multimodal.tracer.output-path:./logs/ragas-trace.jsonl}")
    private String outputPath;

    private final ObjectMapper objectMapper;

    public RagasTracerInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Append one trace record to the output file.
     *
     * @param mode             the retrieval mode used
     * @param topK             top-K parameter at the time of the query
     * @param alpha            weighted-hybrid alpha
     * @param rerankPoolSize   rerank pool size (only meaningful for
     *                         HYBRID_PLUS_RERANK)
     * @param query            user query string
     * @param retrieved        ordered list of retrieved hits
     * @param answer           generator output (final concatenated answer)
     */
    public void trace(String mode, int topK, double alpha, int rerankPoolSize,
                      String query, List<EsDocument> retrieved, String answer) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("ts", Instant.now().toString());
            record.put("mode", mode);
            record.put("params", Map.of(
                    "topK", topK,
                    "alpha", alpha,
                    "rerankPoolSize", rerankPoolSize));
            record.put("query", query);
            record.put("retrieved", buildRetrievedSummary(retrieved));
            record.put("answer", answer);
            record.put("modality_breakdown", buildModalityBreakdown(retrieved));

            String line = objectMapper.writeValueAsString(record) + "\n";
            Path path = Path.of(outputPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Failed to append Ragas trace: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> buildRetrievedSummary(List<EsDocument> retrieved) {
        List<Map<String, Object>> out = new java.util.ArrayList<>(retrieved.size());
        for (EsDocument d : retrieved) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("fileMd5", d.getFileMd5());
            hit.put("chunkId", d.getChunkId());
            hit.put("contentType", d.getContentType() == null ? null : d.getContentType().name());
            hit.put("imageUri", d.getImageUri());
            out.add(hit);
        }
        return out;
    }

    private Map<String, Integer> buildModalityBreakdown(List<EsDocument> retrieved) {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("TEXT", 0);
        breakdown.put("IMAGE_UNIFIED", 0);
        breakdown.put("IMAGE_DESCRIPTION", 0);
        for (EsDocument d : retrieved) {
            if (d.getContentType() != null) {
                breakdown.merge(d.getContentType().name(), 1, Integer::sum);
            }
        }
        return breakdown;
    }
}
