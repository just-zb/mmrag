package com.yizhaoqi.smartpai.utils;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocxImageExtractor}. Each test programmatically
 * builds a small .docx in memory using Apache POI, serialises it to a byte
 * array, then asks the extractor to read it back. No on-disk fixtures are
 * required.
 */
class DocxImageExtractorTest {

    @Test
    void extractsImageWithSurroundingContext() throws IOException {
        byte[] docxBytes = buildDocx(doc -> {
            // Paragraph 1: a Heading 1, becomes the running section heading.
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Architecture Overview");

            // Paragraph 2: prose immediately before the image paragraph.
            XWPFParagraph before = doc.createParagraph();
            before.createRun().setText("Before image text.");

            // Paragraph 3: a paragraph carrying the embedded image.
            XWPFParagraph imagePara = doc.createParagraph();
            XWPFRun imageRun = imagePara.createRun();
            try {
                imageRun.addPicture(
                        new ByteArrayInputStream(makeTinyPng()),
                        XWPFDocument.PICTURE_TYPE_PNG,
                        "embedded.png",
                        org.apache.poi.util.Units.toEMU(40),
                        org.apache.poi.util.Units.toEMU(40));
            } catch (Exception e) {
                throw new RuntimeException("Failed to add picture", e);
            }

            // Paragraph 4: prose immediately after the image paragraph.
            XWPFParagraph after = doc.createParagraph();
            after.createRun().setText("After image text.");
        });

        List<DocxImageExtractor.ExtractedImage> images =
                DocxImageExtractor.extract(new ByteArrayInputStream(docxBytes));

        assertEquals(1, images.size(), "exactly one image expected");
        DocxImageExtractor.ExtractedImage img = images.get(0);
        assertEquals(1, img.getSequence(), "first image is sequence 1");
        assertEquals("Architecture Overview", img.getSectionHeading(),
                "section heading should be the most recent Heading1");
        assertEquals("Before image text.", img.getPrevParagraph());
        assertEquals("After image text.", img.getNextParagraph());
        assertTrue(img.getContentType().toLowerCase().contains("png"),
                "content type should be image/png, was: " + img.getContentType());
        assertTrue(img.sizeBytes() > 0, "image bytes should be non-empty");
    }

    @Test
    void returnsEmptyListWhenDocumentHasNoImages() throws IOException {
        byte[] docxBytes = buildDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("Plain text only, no images.");
        });

        List<DocxImageExtractor.ExtractedImage> images =
                DocxImageExtractor.extract(new ByteArrayInputStream(docxBytes));

        assertNotNull(images);
        assertTrue(images.isEmpty(), "no images expected");
    }

    @Test
    void preservesDocumentOrderAcrossMultipleImages() throws IOException {
        byte[] docxBytes = buildDocx(doc -> {
            for (int i = 1; i <= 3; i++) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                try {
                    run.addPicture(
                            new ByteArrayInputStream(makeTinyPng()),
                            XWPFDocument.PICTURE_TYPE_PNG,
                            "p" + i + ".png",
                            org.apache.poi.util.Units.toEMU(40),
                            org.apache.poi.util.Units.toEMU(40));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        List<DocxImageExtractor.ExtractedImage> images =
                DocxImageExtractor.extract(new ByteArrayInputStream(docxBytes));

        assertEquals(3, images.size());
        assertEquals(1, images.get(0).getSequence());
        assertEquals(2, images.get(1).getSequence());
        assertEquals(3, images.get(2).getSequence());
    }

    @Test
    void emptyContextStringsWhenImageIsAtDocumentBoundary() throws IOException {
        // Image as the first paragraph: prevParagraph should be empty.
        // Image as the last paragraph: nextParagraph should be empty.
        // No headings: sectionHeading should be empty.
        byte[] docxBytes = buildDocx(doc -> {
            XWPFParagraph para = doc.createParagraph();
            XWPFRun run = para.createRun();
            try {
                run.addPicture(
                        new ByteArrayInputStream(makeTinyPng()),
                        XWPFDocument.PICTURE_TYPE_PNG,
                        "lonely.png",
                        org.apache.poi.util.Units.toEMU(40),
                        org.apache.poi.util.Units.toEMU(40));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<DocxImageExtractor.ExtractedImage> images =
                DocxImageExtractor.extract(new ByteArrayInputStream(docxBytes));

        assertEquals(1, images.size());
        DocxImageExtractor.ExtractedImage img = images.get(0);
        assertEquals("", img.getSectionHeading(),
                "no headings -> empty sectionHeading");
        assertEquals("", img.getPrevParagraph(),
                "image in first paragraph -> empty prevParagraph");
        assertEquals("", img.getNextParagraph(),
                "image in last paragraph -> empty nextParagraph");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface DocBuilder {
        void populate(XWPFDocument doc) throws IOException;
    }

    /**
     * Build a .docx in memory using the supplied builder, serialise it to a
     * byte array, and return the bytes. Closes the document before returning.
     */
    private static byte[] buildDocx(DocBuilder builder) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            builder.populate(doc);
            doc.write(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Produce a 1x1 PNG using the JDK's ImageIO so the test does not depend
     * on a hardcoded byte fixture. Roughly 70 bytes.
     */
    private static byte[] makeTinyPng() throws IOException {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", baos),
                "JDK should be able to encode a 1x1 PNG");
        byte[] bytes = baos.toByteArray();
        assertFalse(bytes.length == 0, "PNG bytes should not be empty");
        return bytes;
    }
}
