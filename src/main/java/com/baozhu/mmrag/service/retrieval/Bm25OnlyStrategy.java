package com.baozhu.mmrag.service.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baozhu.mmrag.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pure sparse retrieval over {@code textContent} using the index's English
 * analyzer. The strategy does not query the {@code vector} field;
 * IMAGE_UNIFIED rows are unreachable here because their {@code textContent}
 * is empty by construction (see {@code EsDocument.imageUnified}).
 *
 * <p>This is the F2 BM25 cell of the thesis evaluation; combined with the F1
 * Architecture-A row it reproduces the P1 prediction (A at BM25 collapses
 * to Text-only baseline).
 *
 * <p>The actual ES query construction is left as TODO: this class is a
 * structural placeholder that satisfies the {@link RetrievalStrategy}
 * contract and registers a Spring bean under {@code Mode.BM25_ONLY}; the
 * legacy {@code HybridSearchService} still serves real queries while the
 * strategy switch is being wired in.
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
        // TODO: build esClient.search(...) with:
        //   - index "knowledge_base"
        //   - size = request.topK()
        //   - bool.must(match("textContent", request.query()))
        //   - bool.filter(<permission clause: userId OR isPublic OR orgTag IN userOrgTags>)
        // and map response.hits().hits() -> List<EsDocument>.
        logger.debug("BM25_ONLY search stub — query={}, topK={}", request.query(), request.topK());
        return List.of();
    }
}
