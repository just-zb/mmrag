package com.baozhu.mmrag.service.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.baozhu.mmrag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Weighted-score fusion of dense kNN and BM25. Both branches are issued in
 * a single ES query: the kNN clause supplies the candidate window and a
 * dense score; a BM25 {@code must} clause filters to documents that match
 * the query lexically and adds a sparse score; the rescore stage combines
 * the two scores as approximately
 *
 * <pre>
 *   final ≈ α · dense + (1 − α) · BM25
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
        final int recallK = Math.max(request.topK() * 30, request.topK());
        final double alpha = request.alpha();
        final double bm25Weight = 1.0 - alpha;
        final List<Float> qv = DenseOnlyStrategy.boxed(request.queryVector());
        final String query = request.query();
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index("knowledge_base")
                            .size(request.topK())
                            .knn(kn -> kn
                                    .field("vector")
                                    .queryVector(qv)
                                    .k(recallK)
                                    .numCandidates(recallK))
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(mm -> mm
                                            .field("textContent")
                                            .query(query)))
                                    .filter(f -> f.bool(bf -> PermissionFilter.applyTo(
                                            bf, request.userDbId(), request.userOrgTags())))))
                            .rescore(r -> r
                                    .windowSize(recallK)
                                    .query(rq -> rq
                                            .queryWeight(alpha)
                                            .rescoreQueryWeight(bm25Weight)
                                            .query(rqq -> rqq.match(mm -> mm
                                                    .field("textContent")
                                                    .query(query)
                                                    .operator(Operator.And))))),
                    EsDocument.class);
            return DenseOnlyStrategy.collectHits(response);
        } catch (Exception e) {
            logger.error("WEIGHTED_HYBRID search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
