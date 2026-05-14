package com.baozhu.mmrag.service.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baozhu.mmrag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 *
 * <p>Structural placeholder; see class-level note on Bm25OnlyStrategy.
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
        // TODO: build esClient.search(...) with:
        //   - knn (recallK = topK * 30)
        //   - bool.must(match("textContent", request.query()))
        //   - bool.filter(<permission clause>)
        //   - rescore(windowSize=recallK,
        //             queryWeight=alpha,
        //             rescoreQueryWeight=1.0 - alpha,
        //             query=match("textContent", query))
        // and map hits -> List<EsDocument>.
        logger.debug("WEIGHTED_HYBRID search stub — query={}, topK={}, alpha={}",
                request.query(), request.topK(), request.alpha());
        return List.of();
    }
}
