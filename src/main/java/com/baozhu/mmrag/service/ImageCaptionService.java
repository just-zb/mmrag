package com.baozhu.mmrag.service;

import com.baozhu.mmrag.client.ClaudeVisionClient;
import com.baozhu.mmrag.utils.DocxImageExtractor.ExtractedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Architecture B captioning service. For every image extracted from a
 * source document, builds a captioning prompt that includes the image's
 * <em>document context</em> (the section heading + the immediately
 * preceding paragraph + the immediately following paragraph captured by
 * {@link com.baozhu.mmrag.utils.DocxImageExtractor}), calls
 * {@link ClaudeVisionClient#caption(byte[], String, String)}, and returns
 * the resulting 2–4 sentence caption.
 *
 * <p>The context-bearing prompt is the architectural distinction the
 * thesis labels Architecture B (see thesis §3.3): a vanilla "describe this
 * image" prompt would lose the structural provenance that the §4.5 case
 * studies depend on.
 *
 * <p>The default prompt template is below; it can be overridden via
 * {@code multimodal.captioning.prompt-template} in {@code application.yml}.
 */
@Service
public class ImageCaptionService {

    private static final Logger logger = LoggerFactory.getLogger(ImageCaptionService.class);

    private static final String DEFAULT_PROMPT_TEMPLATE = """
            You are looking at an image embedded in a document. Write a 2–4
            sentence description of what the image shows that would let a
            reader find this image again by searching for it in plain text.

            Use the surrounding document context below to ground your
            description: refer to section names where relevant, and use
            the same vocabulary the document uses.

            === Section heading ===
            {section_heading}

            === Preceding paragraph ===
            {prev_paragraph}

            === Following paragraph ===
            {next_paragraph}
            """;

    @Value("${multimodal.captioning.prompt-template:}")
    private String configuredTemplate;

    private final ClaudeVisionClient claude;

    public ImageCaptionService(ClaudeVisionClient claude) {
        this.claude = claude;
    }

    /**
     * Caption a single extracted image with its document context. Returns
     * the caption text suitable for indexing as the {@code textContent}
     * of an IMAGE_DESCRIPTION row.
     */
    public String caption(ExtractedImage image) {
        String template = (configuredTemplate == null || configuredTemplate.isBlank())
                ? DEFAULT_PROMPT_TEMPLATE
                : configuredTemplate;
        String prompt = template
                .replace("{section_heading}", nz(image.getSectionHeading()))
                .replace("{prev_paragraph}", nz(image.getPrevParagraph()))
                .replace("{next_paragraph}", nz(image.getNextParagraph()));
        try {
            String caption = claude.caption(image.getData(), image.getContentType(), prompt);
            logger.debug("Captioned image #{} ({} bytes) -> {} chars",
                    image.getSequence(), image.sizeBytes(), caption.length());
            return caption;
        } catch (Exception e) {
            logger.warn("Captioning failed for image #{}: {}", image.getSequence(), e.getMessage());
            // Degrade gracefully: surface the heading so something is indexed.
            return image.getSectionHeading();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
