package com.baozhu.mmrag.service;

import com.baozhu.mmrag.client.EmbeddingClient;
import com.baozhu.mmrag.config.MultimodalProperties;
import com.baozhu.mmrag.entity.EsDocument;
import com.baozhu.mmrag.entity.ImageChunk;
import com.baozhu.mmrag.repository.ImageChunkRepository;
import com.baozhu.mmrag.utils.DocxImageExtractor;
import com.baozhu.mmrag.utils.DocxImageExtractor.ExtractedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrator for the .docx image-side ingestion pipeline. Called from
 * {@code ParseService} after the existing Tika text path completes. The
 * service is a no-op when the buffered bytes do not parse as a valid .docx,
 * so callers can pass any uploaded file unchanged.
 *
 * <p>The pipeline for each extracted image is:
 *
 * <ol>
 *   <li>Upload the image bytes to MinIO via {@link ImageStorageService}.</li>
 *   <li>Persist an {@link ImageChunk} row to MySQL with the image URI,
 *       paragraph-level context, and tenant fields. This row exists
 *       regardless of which ingestion architecture is active.</li>
 *   <li>If the active architecture is {@code UNIFIED} (Architecture A),
 *       embed the image bytes via the SigLIP image tower and index a
 *       single IMAGE_UNIFIED row in Elasticsearch.</li>
 *   <li>If the active architecture is {@code DESCRIPTION} (Architecture B),
 *       caption the image with full document context via
 *       {@link ImageCaptionService}, embed the caption text via the SigLIP
 *       text tower, and index a single IMAGE_DESCRIPTION row.</li>
 *   <li>If the active architecture is {@code TEXT_ONLY}, no IMAGE_* row
 *       is indexed; the MinIO upload and the MySQL row are still produced
 *       for record-keeping.</li>
 * </ol>
 *
 * <p>The architecture choice is the F1 axis of the thesis evaluation; a
 * full factorial run reindexes the corpus once per F1 cell with
 * {@code multimodal.ingestion.architecture} flipped between the three
 * values.
 */
@Service
public class MultimodalIngestionService {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalIngestionService.class);

    private final ImageStorageService imageStorage;
    private final ImageChunkRepository imageChunkRepository;
    private final ImageCaptionService imageCaptionService;
    private final EmbeddingClient embeddingClient;
    private final ElasticsearchService elasticsearchService;
    private final MultimodalProperties multimodalProperties;

    public MultimodalIngestionService(ImageStorageService imageStorage,
                                      ImageChunkRepository imageChunkRepository,
                                      ImageCaptionService imageCaptionService,
                                      EmbeddingClient embeddingClient,
                                      ElasticsearchService elasticsearchService,
                                      MultimodalProperties multimodalProperties) {
        this.imageStorage = imageStorage;
        this.imageChunkRepository = imageChunkRepository;
        this.imageCaptionService = imageCaptionService;
        this.embeddingClient = embeddingClient;
        this.elasticsearchService = elasticsearchService;
        this.multimodalProperties = multimodalProperties;
    }

    /**
     * Process the image side of one uploaded file. Safe to call on any
     * MIME type — the {@link DocxImageExtractor} returns an empty list (or
     * throws an IOException that is caught here) for non-.docx inputs.
     *
     * @param fileMd5  the file's MD5 fingerprint (joins to {@code file_upload})
     * @param bytes    the full file bytes (the same buffer used by the text
     *                 path, so the file is read once at the controller layer)
     * @param userId   uploading user id (tenant)
     * @param orgTag   organisation tag (tenant)
     * @param isPublic public visibility flag
     */
    public void ingestImages(String fileMd5, byte[] bytes,
                             String userId, String orgTag, boolean isPublic) {
        List<ExtractedImage> images;
        try {
            images = DocxImageExtractor.extract(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            // Not a .docx (or POI rejected it): nothing to ingest on the
            // image side. The text path handles every format Tika supports.
            logger.debug("DocxImageExtractor skipped for fileMd5={}: {}", fileMd5, e.getMessage());
            return;
        }
        if (images.isEmpty()) {
            return;
        }

        String architecture = multimodalProperties.getIngestion().getArchitecture();
        logger.info("Ingesting {} image(s) for fileMd5={} under architecture={}",
                images.size(), fileMd5, architecture);

        List<EsDocument> esDocsToIndex = new ArrayList<>();
        for (ExtractedImage image : images) {
            try {
                String uri = imageStorage.upload(fileMd5, image.getSequence(),
                        image.getData(), image.getContentType());
                ImageChunk chunk = persistImageChunk(fileMd5, image, uri,
                        userId, orgTag, isPublic);

                EsDocument esDoc = buildEsDocument(architecture, chunk, image);
                if (esDoc != null) {
                    esDocsToIndex.add(esDoc);
                }
            } catch (Exception e) {
                logger.warn("Failed to ingest image #{} of fileMd5={}: {}",
                        image.getSequence(), fileMd5, e.getMessage(), e);
            }
        }

        if (!esDocsToIndex.isEmpty()) {
            elasticsearchService.bulkIndex(esDocsToIndex);
            logger.info("Indexed {} IMAGE_* rows for fileMd5={}", esDocsToIndex.size(), fileMd5);
        }
    }

    private ImageChunk persistImageChunk(String fileMd5, ExtractedImage image, String imageUri,
                                         String userId, String orgTag, boolean isPublic) {
        ImageChunk chunk = new ImageChunk();
        chunk.setFileMd5(fileMd5);
        chunk.setIdx(image.getSequence());
        chunk.setPageNum(image.getSequence());
        chunk.setImageUri(imageUri);
        chunk.setContentType(image.getContentType());
        chunk.setSizeBytes(image.sizeBytes());
        chunk.setSectionHeading(truncate(image.getSectionHeading(), 1024));
        chunk.setPrevParagraph(image.getPrevParagraph());
        chunk.setNextParagraph(image.getNextParagraph());
        chunk.setUserId(userId);
        chunk.setOrgTag(orgTag);
        chunk.setPublic(isPublic);
        return imageChunkRepository.save(chunk);
    }

    private EsDocument buildEsDocument(String architecture, ImageChunk chunk, ExtractedImage image) {
        return switch (architecture.toUpperCase()) {
            case "TEXT_ONLY" -> null;
            case "UNIFIED" -> buildImageUnifiedRow(chunk, image);
            case "DESCRIPTION" -> buildImageDescriptionRow(chunk, image);
            default -> {
                logger.warn("Unknown ingestion architecture '{}'; skipping image indexing for chunk #{}",
                        architecture, chunk.getIdx());
                yield null;
            }
        };
    }

    private EsDocument buildImageUnifiedRow(ImageChunk chunk, ExtractedImage image) {
        float[] vector = embeddingClient.embedImage(image.getData(), image.getContentType());
        return EsDocument.imageUnified(
                chunk.getFileMd5(), chunk.getIdx(), chunk.getImageUri(),
                chunk.getPageNum(), vector, embeddingClient.modelVersion(),
                chunk.getUserId(), chunk.getOrgTag(), chunk.isPublic());
    }

    private EsDocument buildImageDescriptionRow(ImageChunk chunk, ExtractedImage image) {
        String caption = imageCaptionService.caption(image);
        // Persist the caption back to the image_chunk row so the captioning
        // prompt is reproducible from the database (thesis §3.3 / §4.6).
        chunk.setCaption(caption);
        imageChunkRepository.save(chunk);
        float[] vector = embeddingClient.embedOne(caption);
        return EsDocument.imageDescription(
                chunk.getFileMd5(), chunk.getIdx(), chunk.getImageUri(),
                chunk.getPageNum(), caption, vector, embeddingClient.modelVersion(),
                chunk.getUserId(), chunk.getOrgTag(), chunk.isPublic());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
