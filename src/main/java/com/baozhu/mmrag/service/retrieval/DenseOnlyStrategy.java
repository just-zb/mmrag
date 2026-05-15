package com.baozhu.mmrag.service.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.baozhu.mmrag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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
        final List<Float> qv = boxed(request.queryVector());
        final int numCandidates = Math.max(request.topK() * 10, 100);
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index("knowledge_base")
                            .size(request.topK())
                            .knn(kn -> kn
                                    .field("vector")
                                    .queryVector(qv)
                                    .k(request.topK())
                                    .numCandidates(numCandidates))
                            .query(q -> q.bool(b -> b
                                    .filter(f -> f.bool(bf -> PermissionFilter.applyTo(
                                            bf, request.userDbId(), request.userOrgTags()))))),
                    EsDocument.class);
            return collectHits(response);
        } catch (Exception e) {
            logger.error("DENSE_ONLY search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    static List<Float> boxed(float[] xs) {
        List<Float> out = new ArrayList<>(xs.length);
        for (float x : xs) out.add(x);
        return out;
    }

    static List<EsDocument> collectHits(SearchResponse<EsDocument> response) {
        List<EsDocument> hits = new ArrayList<>();
        response.hits().hits().forEach(h -> {
            if (h.source() != null) hits.add(h.source());
        });
        return hits;
    }
}
