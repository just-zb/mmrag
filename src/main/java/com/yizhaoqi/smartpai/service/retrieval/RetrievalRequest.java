package com.yizhaoqi.smartpai.service.retrieval;

import java.util.List;

/**
 * Input bundle for {@link RetrievalStrategy#search(RetrievalRequest)}.
 *
 * @param query           original user query string (used by BM25 and reranker)
 * @param queryVector     SigLIP text-tower embedding of the query (1152-dim,
 *                        used by the dense branch; null for BM25-only mode)
 * @param topK            number of hits to return
 * @param alpha           weight on the dense score in WEIGHTED_HYBRID and
 *                        HYBRID_PLUS_RERANK fusion; default 0.5
 * @param rerankPoolSize  size of the candidate pool fed to the reranker in
 *                        HYBRID_PLUS_RERANK; default 30
 * @param userDbId        owning user id used for the permission filter
 * @param userOrgTags     organisation tags the user belongs to; used for the
 *                        org-scoped clause of the permission filter
 */
public record RetrievalRequest(
        String query,
        float[] queryVector,
        int topK,
        double alpha,
        int rerankPoolSize,
        String userDbId,
        List<String> userOrgTags
) {
    /** Conventional defaults used by the thesis main-factorial cells. */
    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_ALPHA = 0.5;
    public static final int DEFAULT_RERANK_POOL = 30;
}
