package com.baozhu.mmrag.service.retrieval;

import com.baozhu.mmrag.client.CohereRerankerClient;
import com.baozhu.mmrag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-stage retrieval: stage 1 issues a {@link WeightedHybridStrategy} call
 * with an enlarged {@code topK = rerankPoolSize} (default 30) to produce a
 * candidate pool; stage 2 hands the pool to a cross-encoder reranker
 * (Cohere Rerank v3 by default) and returns the top-K of the rescored
 * permutation.
 *
 * <p>This is the F2 Hybrid+Rerank cell of the thesis evaluation, the cell
 * that the §4.3 main result and the §5.3 practical recommendation both
 * select as the best-cell configuration.
 *
 * <p>The IMAGE_UNIFIED row's {@code textContent} is empty by construction;
 * to give the reranker something to score on, this strategy substitutes the
 * row's section heading as a placeholder document — the reranker is then
 * matching the query against structural metadata, not pixel content. This
 * is a deliberate compromise; see thesis §4.6 (Threats) and the §6.3
 * pipeline-extension item on richer image-row reranking.
 */
@Component
public class HybridPlusRerankStrategy implements RetrievalStrategy {

    private static final Logger logger = LoggerFactory.getLogger(HybridPlusRerankStrategy.class);

    private final WeightedHybridStrategy stage1;
    private final CohereRerankerClient reranker;

    public HybridPlusRerankStrategy(WeightedHybridStrategy stage1,
                                    CohereRerankerClient reranker) {
        this.stage1 = stage1;
        this.reranker = reranker;
    }

    @Override
    public Mode mode() {
        return Mode.HYBRID_PLUS_RERANK;
    }

    @Override
    public List<EsDocument> search(RetrievalRequest request) {
        // Stage 1: enlarge topK to the rerank pool size.
        RetrievalRequest poolRequest = new RetrievalRequest(
                request.query(),
                request.queryVector(),
                request.rerankPoolSize(),
                request.alpha(),
                request.rerankPoolSize(),
                request.userDbId(),
                request.userOrgTags()
        );
        List<EsDocument> pool = stage1.search(poolRequest);
        if (pool.size() <= request.topK()) {
            return pool;
        }

        // Stage 2: rerank.
        List<String> texts = new ArrayList<>(pool.size());
        for (EsDocument d : pool) {
            texts.add(rerankTextFor(d));
        }
        List<Integer> permutation = reranker.rerank(request.query(), texts, request.topK());
        List<EsDocument> top = new ArrayList<>(permutation.size());
        for (Integer idx : permutation) {
            if (idx >= 0 && idx < pool.size()) top.add(pool.get(idx));
        }
        return top;
    }

    /**
     * Choose what text the reranker scores against. Text rows use their own
     * content; IMAGE_DESCRIPTION rows use the caption (which is also the
     * textContent); IMAGE_UNIFIED rows fall back to an empty placeholder
     * since they carry no text — this is the asymmetry recorded in §4.6.
     */
    private String rerankTextFor(EsDocument d) {
        if (d.getTextContent() != null && !d.getTextContent().isBlank()) {
            return d.getTextContent();
        }
        if (d.getCaption() != null && !d.getCaption().isBlank()) {
            return d.getCaption();
        }
        return "";
    }
}
