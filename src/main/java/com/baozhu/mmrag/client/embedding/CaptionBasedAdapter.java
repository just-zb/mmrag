package com.baozhu.mmrag.client.embedding;

import com.baozhu.mmrag.client.ClaudeVisionClient;
import com.baozhu.mmrag.client.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Architecture B end-to-end adapter. For text inputs this delegates to the
 * SigLIP text tower (so the caption embeddings live in the same vector space
 * as text-chunk embeddings). For image inputs it composes:
 *
 * <ol>
 *   <li>{@link ClaudeVisionClient} produces a 2–4 sentence caption from the
 *       image plus its document context (section heading + adjacent
 *       paragraphs) — the captioning prompt is built upstream by
 *       {@code ImageCaptionService}.</li>
 *   <li>{@link SiglipAdapter#embed(List)} embeds the caption text.</li>
 * </ol>
 *
 * <p>The dimensionality and {@code modelVersion} are inherited from the
 * underlying SigLIP text tower, since that is what actually produced the
 * stored vector.
 *
 * <p><strong>Important:</strong> {@link #embedImage(byte[], String)} on this
 * adapter requires a precomposed caption to make sense; the architecture's
 * captioning flow lives in {@code ImageCaptionService}, which calls
 * {@link ClaudeVisionClient} directly with the full context prompt and then
 * passes the resulting caption back through {@link #embed(List)}. Calling
 * {@link #embedImage} here without context would lose the section-heading and
 * adjacent-paragraph signals that are the architectural contribution of
 * Architecture B, so the method intentionally throws.
 *
 * <p>Active when {@code multimodal.embedding.strategy=caption}.
 */
@Component
@ConditionalOnProperty(name = "multimodal.embedding.strategy", havingValue = "caption")
@Primary
public class CaptionBasedAdapter implements EmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(CaptionBasedAdapter.class);

    private final SiglipAdapter siglipText;
    private final ClaudeVisionClient claude;

    public CaptionBasedAdapter(SiglipAdapter siglipText, ClaudeVisionClient claude) {
        this.siglipText = siglipText;
        this.claude = claude;
    }

    @Override
    public String modelVersion() {
        // The stored vector is produced by the SigLIP text tower over the
        // VLM caption; from a vector-space perspective this is what matters.
        return "caption-via-" + claude.modelId() + "+" + siglipText.modelVersion();
    }

    @Override
    public int dimension() {
        return siglipText.dimension();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return siglipText.embed(texts);
    }

    @Override
    public float[] embedImage(byte[] imageBytes, String contentType) {
        throw new UnsupportedOperationException(
                "CaptionBasedAdapter requires the caller to construct the caption "
                        + "with full document context (section heading + adjacent "
                        + "paragraphs); use ImageCaptionService instead of calling "
                        + "embedImage directly. See thesis §3.3 for why context "
                        + "matters.");
    }
}
