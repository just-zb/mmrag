package com.yizhaoqi.smartpai.service.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure kNN retrieval over the {@code vector} field. Reaches every
 * content_type since every indexed row carries a SigLIP vector (text rows:
 * SigLIP text tower; IMAGE_UNIFIED: SigLIP image tower; IMAGE_DESCRIPTION:
 * SigLIP text tower over the caption).
 *
 * <p>This is the F2 Dense cell of the thesis evaluation. Combined with the
 * F1 Architecture-B row it isolates the P3 prediction (dense retrieval
 * favours B over A because B's caption embeddings sit in the same SigLIP
 * text space as the query, while A relies on the learned text-to-image
 * bridge).
 */
@Component
public class DenseOnlyStrategy implements RetrievalStrategy {

    private static final Logger logger = LoggerFactory.getLogger(DenseOnlyStrategy.class);

    private final ElasticsearchClient esClient;

    public DenseOnlyStrategy(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public Mode mode() {
        return Mode.DENSE_ONLY;
    }

    @Override
    public List<EsDocument> search(RetrievalRequest request) {
        if (request.queryVector() == null) {
            throw new IllegalArgumentException("DENSE_ONLY requires a precomputed query vector");
        }
        // Convert primitive float[] -> Float[] for the ES client.
        Float[] qv = boxed(request.queryVector());
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index("knowledge_base")
                            .size(request.topK())
                            .knn(kn -> kn
                                    .field("vector")
                                    .queryVector(List.of(qv))
                                    .k(request.topK())
                                    .numCandidates(Math.max(request.topK() * 10, 100))
                                    .filter(f -> f.bool(PermissionFilter.apply(
                                            request.userDbId(), request.userOrgTags())))
                            ),
                    EsDocument.class);
            List<EsDocument> hits = new ArrayList<>();
            response.hits().hits().forEach(h -> {
                if (h.source() != null) hits.add(h.source());
            });
            return hits;
        } catch (Exception e) {
            logger.error("Dense-only search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private static Float[] boxed(float[] xs) {
        Float[] out = new Float[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = xs[i];
        return out;
    }
}
