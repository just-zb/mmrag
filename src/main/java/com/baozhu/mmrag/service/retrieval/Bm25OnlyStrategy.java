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
 * Pure sparse retrieval over {@code textContent} using the index's English
 * analyzer. Does not query the {@code vector} field; IMAGE_UNIFIED rows are
 * unreachable here because their {@code textContent} is empty by construction
 * (see {@code EsDocument.imageUnified}).
 *
 * <p>This is the F2 BM25 cell of the thesis evaluation; combined with the F1
 * Architecture-A row it reproduces the P1 prediction (A at BM25 collapses
 * to Text-only baseline).
 */
@Component
public class Bm25OnlyStrategy implements RetrievalStrategy {

    private static final Logger logger = LoggerFactory.getLogger(Bm25OnlyStrategy.class);

    private final ElasticsearchClient esClient;

    public Bm25OnlyStrategy(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public Mode mode() {
        return Mode.BM25_ONLY;
    }

    @Override
    public List<EsDocument> search(RetrievalRequest request) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index("knowledge_base")
                            .size(request.topK())
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(mm -> mm
                                            .field("textContent")
                                            .query(request.query())))
                                    .filter(f -> f.bool(bf -> PermissionFilter.applyTo(
                                            bf, request.userDbId(), request.userOrgTags()))))),
                    EsDocument.class);
            return collectHits(response);
        } catch (Exception e) {
            logger.error("BM25_ONLY search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private static List<EsDocument> collectHits(SearchResponse<EsDocument> response) {
        List<EsDocument> hits = new ArrayList<>();
        response.hits().hits().forEach(h -> {
            if (h.source() != null) hits.add(h.source());
        });
        return hits;
    }
}
