package com.baozhu.mmrag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persistence row for one image extracted from a source document during
 * multimodal ingestion. The row joins three things together:
 *
 * <ul>
 *   <li>The owning document, identified by {@link #fileMd5}, and the image's
 *       1-indexed order within that document ({@link #idx}).</li>
 *   <li>The blob in MinIO at {@link #imageUri} (canonical layout
 *       {@code images/{fileMd5}/{idx}.{ext}}).</li>
 *   <li>The Architecture B caption ({@link #caption}) produced by the
 *       vision-language model, plus the document context that fed the
 *       captioning prompt ({@link #sectionHeading}, {@link #prevParagraph},
 *       {@link #nextParagraph}). Architecture A leaves {@link #caption} null;
 *       Architecture B always populates it.</li>
 * </ul>
 *
 * <p>The tenant fields ({@link #userId}, {@link #orgTag}, {@link #isPublic})
 * mirror the access-control fields on {@code FileUpload} so the same
 * permission filter applies to image rows during retrieval.
 */
@Entity
@Table(
        name = "image_chunk",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_image_chunk_md5_idx",
                columnNames = {"file_md5", "idx"}
        ),
        indexes = {
                @Index(name = "idx_image_chunk_md5", columnList = "file_md5"),
                @Index(name = "idx_image_chunk_user", columnList = "user_id")
        }
)
@Data
@NoArgsConstructor
public class ImageChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", nullable = false, length = 64)
    private String fileMd5;

    /**
     * 1-indexed order of this image within the source document, matching the
     * {@code sequence} field returned by
     * {@link com.baozhu.mmrag.utils.DocxImageExtractor.ExtractedImage}.
     */
    @Column(name = "idx", nullable = false)
    private Integer idx;

    /**
     * Page-like ordinal. For {@code .docx} this is the index of the paragraph
     * containing the image; for paginated formats it would be the page number.
     */
    @Column(name = "page_num")
    private Integer pageNum;

    /**
     * MinIO URI for the image blob, canonical form
     * {@code minio://images/{fileMd5}/{idx}.{ext}}.
     */
    @Column(name = "image_uri", nullable = false, length = 512)
    private String imageUri;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    /**
     * Vision-language model caption used by Architecture B. Null for
     * documents indexed only under Architecture A.
     */
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    /**
     * Most recent {@code Heading N}-styled paragraph preceding the image.
     * Captured at extraction time and persisted so the captioning prompt is
     * reproducible (see thesis §3.3).
     */
    @Column(name = "section_heading", length = 1024)
    private String sectionHeading;

    @Column(name = "prev_paragraph", columnDefinition = "TEXT")
    private String prevParagraph;

    @Column(name = "next_paragraph", columnDefinition = "TEXT")
    private String nextParagraph;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "org_tag", length = 128)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
