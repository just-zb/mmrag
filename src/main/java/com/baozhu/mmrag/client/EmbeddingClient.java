package com.baozhu.mmrag.client;

import java.util.List;

/**
 * Pluggable embedding backend used by the multimodal-RAG ingestion pipeline.
 *
 * <p>Three implementations live under {@code client/embedding/}:
 * <ul>
 *   <li>{@code SiglipAdapter} — SigLIP-SO400M served behind a HuggingFace
 *       Inference Endpoint. Supports both text and image embedding (1152-dim).
 *       Used by Architecture A (image tower) and by Architecture B (text tower
 *       embedding the caption).</li>
 *   <li>{@code CaptionBasedAdapter} — composes {@code ClaudeVisionClient} and
 *       {@code SiglipAdapter} to implement Architecture B end-to-end: image
 *       bytes in, caption produced by the VLM, vector returned by the SigLIP
 *       text tower over that caption.</li>
 *   <li>{@code DashScopeTextAdapter} — legacy text-only baseline used to
 *       reproduce the legacy text-only baseline. Image
 *       methods throw {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * <p>Selection is driven by {@code multimodal.embedding.strategy} in
 * {@code application.yml}. See {@code MultimodalProperties}.
 */
public interface EmbeddingClient {

    /**
     * @return identifier string for the underlying model + version, written
     *         into the {@code modelVersion} field of every indexed row.
     */
    String modelVersion();

    /**
     * @return output vector dimensionality; must match the {@code dims} of the
     *         {@code vector} field in the Elasticsearch mapping.
     */
    int dimension();

    /**
     * Batch text embedding. Implementations are expected to internally split
     * large input lists into provider-appropriate batches.
     *
     * @param texts non-null, non-empty list of strings to embed
     * @return one float[dimension()] per input text, in the same order
     */
    List<float[]> embed(List<String> texts);

    /**
     * Convenience single-text path. Default implementation delegates to
     * {@link #embed(List)}.
     */
    default float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    /**
     * Architecture A image-side embedding. Encode the raw image bytes into a
     * {@link #dimension()}-d vector that lives in the same space as the text
     * embeddings produced by {@link #embed(List)}.
     *
     * <p>Text-only adapters (e.g. DashScope) throw
     * {@link UnsupportedOperationException}.
     *
     * @param imageBytes  raw image bytes (PNG / JPEG / etc.)
     * @param contentType MIME type of {@code imageBytes}
     */
    default float[] embedImage(byte[] imageBytes, String contentType) {
        throw new UnsupportedOperationException(
                "Image embedding is not supported by this adapter (" + getClass().getSimpleName() + ")");
    }
}
