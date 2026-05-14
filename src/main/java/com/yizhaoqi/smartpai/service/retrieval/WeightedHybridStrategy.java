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
 * Weighted-score fusion of dense kNN and BM25. Both branches are issued in
 * a single ES query: the kNN clause supplies the candidate window and a
 * dense score; a BM25 {@code must} clause filters to documents that match
 * the query lexically and adds a sparse score; the rescore stage combines
 * the two scores as
 *
 * <pre>
 *   final = α · dense + (1 − α) · BM25
 * </pre>
 *
 * with α taken from {@link RetrievalRequest#alpha()} (default 0.5).
 *
 * <p>The candidate window is sized {@code topK · 30} so the rescore has
 * enough headroom to re-order. The output is the top-K of the fused score.
 *
 * <p>This is the F2 Hybrid cell of the thesis evaluation.
 */
@Component
public class WeightedHybridStrategy implements RetrievalStrategy {

    private static final Logger logger = LoggerFactory.getLogger(WeightedHybridStrategy.class);

    private final ElasticsearchClient esClient;

    public WeightedHybridStrategy(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public Mode mode() {
        return Mode.WEIGHTED_HYBRID;
    }

    @Override
    public List<EsDocument> search(RetrievalRequest request) {
        if (request.queryVector() == null) {
            throw new IllegalArgumentException("WEIGHTED_HYBRID requires a query vector");
        }
        final int recallK = request.topK() * 30;
        final double alpha = request.alpha();
        final double bm25Weight = 1.0 - alpha;
        Float[] qv = boxedQueryVector(request.queryVector());
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index("knowledge_base")
                            .size(request.topK())
                            .knn(kn -> kn
                                    .field("vector")
                                    .queryVector(List.of(qv))
                                    .k(recallK)
                                    .numCandidates(recallK))
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(mm -> mm
                                            .field("textContent")
                                            .query(request.query())))
                                    .filter(f -> f.bool(PermissionFilter.apply(
                                            request.userDbId(), request.userOrgTags())))))
                            .rescore(r -> r
                                    .windowSize(recallK)
                                    .query(rq -> rq
                                            .queryWeight(alpha)
                                            .rescoreQueryWeight(bm25Weight)
                                            .query(qq -> qq.match(mm -> mm
                                                    .field("textContent")
                                                    .query(request.query()))))),
                    EsDocument.class);
            return collectHits(response);
        } catch (Exception e) {
            logger.error("Weighted-hybrid search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    static Float[] boxedQueryVector(float[] xs) {
        Float[] out = new Float[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = xs[i];
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
