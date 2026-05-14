package com.baozhu.mmrag.service.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baozhu.mmrag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 *
 * <p>Structural placeholder; see class-level note on Bm25OnlyStrategy.
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
        // TODO: build esClient.search(...) with:
        //   - knn(field="vector", queryVector=request.queryVector(),
        //         k=request.topK(), numCandidates=max(topK*10, 100))
        //   - permission filter as in Bm25OnlyStrategy
        // and map hits -> List<EsDocument>.
        logger.debug("DENSE_ONLY search stub — topK={}, dim={}",
                request.topK(), request.queryVector().length);
        return List.of();
    }
}
