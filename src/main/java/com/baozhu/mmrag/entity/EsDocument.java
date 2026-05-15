package com.baozhu.mmrag.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Elasticsearch indexed document. A single index ({@code knowledge_base})
 * holds three kinds of rows discriminated by {@link #contentType}:
 *
 * <ul>
 *   <li>{@code TEXT} — a plain text chunk extracted from a document. The
 *       {@link #textContent} carries the chunk text; {@link #vector} is the
 *       SigLIP text-tower embedding of that text. Image fields are unused.</li>
 *   <li>{@code IMAGE_UNIFIED} — Architecture A. The {@link #vector} is the
 *       SigLIP image-tower embedding of an extracted picture; {@link #imageUri}
 *       points to the picture in MinIO. {@link #textContent} is empty (the
 *       index has no text for this row, so BM25 cannot reach it — this is the
 *       structural property the thesis P1 prediction relies on).</li>
 *   <li>{@code IMAGE_DESCRIPTION} — Architecture B. The {@link #vector} is the
 *       SigLIP text-tower embedding of a 2–4 sentence caption produced by a
 *       vision-language model from the image plus its document context.
 *       {@link #textContent} carries the caption itself so BM25 can also reach
 *       this row; {@link #imageUri} still points to the source picture so
 *       generation can deliver the actual pixels via image dual-routing.</li>
 * </ul>
 *
 * <p>The vector dimensionality is 1152 (SigLIP-SO400M); see
 * {@code src/main/resources/es-mappings/knowledge_base.json}.
 */
@Data
public class EsDocument {

    private String id;             // ES document ID
    private String fileMd5;        // owning document fingerprint
    private Integer chunkId;       // 1-indexed chunk order within the document
    private ContentType contentType; // see class javadoc
    private String textContent;    // chunk text or caption (empty for IMAGE_UNIFIED)
    private String imageUri;       // MinIO URI for an image row, null for TEXT rows
    private String caption;        // VLM caption for IMAGE_DESCRIPTION rows; null otherwise
    private Integer pageNum;       // page-like ordinal (paragraph index for .docx)
    private float[] vector;        // SigLIP-SO400M embedding (1152-dim)
    private String modelVersion;   // embedder version tag
    private String userId;         // owning user (tenant)
    private String orgTag;         // owning organisation tag

    /**
     * Public visibility flag. The {@link com.fasterxml.jackson.annotation.JsonProperty}
     * pin keeps Jackson from stripping the {@code is} prefix during
     * serialisation (Jackson would otherwise produce {@code "public": true}
     * for a {@code boolean isPublic} field with the Lombok-generated
     * {@code isPublic()} getter), which would mismatch the {@code isPublic}
     * field name in {@code es-mappings/knowledge_base.json} and the
     * permission-filter clauses in the retrieval strategies.
     */
    @JsonProperty("isPublic")
    private boolean isPublic;

    public EsDocument() {
    }

    /**
     * Backward-compatibility constructor used by the legacy
     * {@code VectorizationService} which still produces TEXT rows only.
     * New call sites should prefer the typed factory methods
     * ({@link #text}, {@link #imageUnified}, {@link #imageDescription}).
     */
    @Deprecated
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                      float[] vector, String modelVersion,
                      String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.contentType = ContentType.TEXT;
        this.textContent = content;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }

    /**
     * Convenience factory for a plain text chunk.
     */
    public static EsDocument text(String fileMd5, int chunkId, String textContent,
                                  float[] vector, String modelVersion,
                                  String userId, String orgTag, boolean isPublic) {
        EsDocument d = new EsDocument();
        d.fileMd5 = fileMd5;
        d.chunkId = chunkId;
        d.contentType = ContentType.TEXT;
        d.textContent = textContent;
        d.vector = vector;
        d.modelVersion = modelVersion;
        d.userId = userId;
        d.orgTag = orgTag;
        d.isPublic = isPublic;
        return d;
    }

    /**
     * Convenience factory for an Architecture A unified-image row. The text
     * content is empty by design — IMAGE_UNIFIED rows are unreachable by BM25.
     */
    public static EsDocument imageUnified(String fileMd5, int chunkId, String imageUri,
                                          int pageNum, float[] vector, String modelVersion,
                                          String userId, String orgTag, boolean isPublic) {
        EsDocument d = new EsDocument();
        d.fileMd5 = fileMd5;
        d.chunkId = chunkId;
        d.contentType = ContentType.IMAGE_UNIFIED;
        d.textContent = "";
        d.imageUri = imageUri;
        d.pageNum = pageNum;
        d.vector = vector;
        d.modelVersion = modelVersion;
        d.userId = userId;
        d.orgTag = orgTag;
        d.isPublic = isPublic;
        return d;
    }

    /**
     * Convenience factory for an Architecture B image-description row. The
     * caption serves as both the indexed text (BM25 can reach it) and the
     * source for the dense embedding (which is the SigLIP text-tower vector
     * of the caption).
     */
    public static EsDocument imageDescription(String fileMd5, int chunkId, String imageUri,
                                              int pageNum, String caption, float[] vector,
                                              String modelVersion, String userId,
                                              String orgTag, boolean isPublic) {
        EsDocument d = new EsDocument();
        d.fileMd5 = fileMd5;
        d.chunkId = chunkId;
        d.contentType = ContentType.IMAGE_DESCRIPTION;
        d.textContent = caption;
        d.imageUri = imageUri;
        d.caption = caption;
        d.pageNum = pageNum;
        d.vector = vector;
        d.modelVersion = modelVersion;
        d.userId = userId;
        d.orgTag = orgTag;
        d.isPublic = isPublic;
        return d;
    }

    /**
     * Discriminator for the three row kinds in the unified index.
     */
    public enum ContentType {
        TEXT,
        IMAGE_UNIFIED,
        IMAGE_DESCRIPTION
    }
}
