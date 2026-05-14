package com.yizhaoqi.smartpai.service.retrieval;

import com.yizhaoqi.smartpai.entity.EsDocument;

import java.util.List;

/**
 * Pluggable retrieval mode for the multimodal-RAG query pipeline. The four
 * strategies are the F2 axis of the thesis's 3 × 4 factorial evaluation
 * (see thesis §4.1). Each implementation is a Spring {@code @Component}
 * registered under a unique {@link Mode} so a {@code RetrievalRouter}
 * (or {@code HybridSearchService} after refactor) can select one per
 * request from {@code mode}, {@code topK}, and {@code alpha} parameters.
 *
 * <p>Per-tenant permission filters are applied uniformly inside every
 * strategy at the candidate-selection stage; they are not the strategy's
 * responsibility to interpret.
 */
public interface RetrievalStrategy {

    /**
     * Strategy identifier; the name a request parameter or configuration
     * key uses to pick this strategy.
     */
    Mode mode();

    /**
     * Run retrieval and return the top hits in descending relevance order.
     *
     * @param request bundles the user query, the precomputed query
     *                embedding (when applicable), the operating top-K, and
     *                the tenant context
     * @return ordered list of hits, length ≤ {@code request.topK()}
     */
    List<EsDocument> search(RetrievalRequest request);

    /**
     * The four retrieval modes evaluated by the thesis.
     */
    enum Mode {
        /** Pure sparse retrieval over {@code textContent}. Cannot reach
         *  IMAGE_UNIFIED rows by design (their textContent is empty). */
        BM25_ONLY,
        /** Pure kNN over the {@code vector} field. Reaches every
         *  content_type since every row carries a vector. */
        DENSE_ONLY,
        /** Weighted-score fusion of dense kNN and BM25. Default α = 0.5. */
        WEIGHTED_HYBRID,
        /** {@code WEIGHTED_HYBRID} → top-N candidate pool → cross-encoder
         *  rerank (Cohere Rerank v3 by default) → top-K. */
        HYBRID_PLUS_RERANK
    }
}
