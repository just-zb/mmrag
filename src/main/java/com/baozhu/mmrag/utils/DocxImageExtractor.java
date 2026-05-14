package com.baozhu.mmrag.utils;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extracts embedded images from a .docx file together with the surrounding
 * paragraph context (closest preceding heading, immediately preceding paragraph,
 * immediately following paragraph). The captured context is intended to feed a
 * vision-language captioning prompt downstream so the produced caption can
 * anchor against the document's structural location, not just the pixels.
 *
 * <p>This class is a pure utility: it does <em>not</em> write to MinIO, the
 * relational database, or Elasticsearch. Persistence and indexing are the
 * responsibility of the caller. The intent is to make image extraction
 * unit-testable in isolation, and to keep the existing Tika-based text
 * extraction path in {@code ParseService} untouched until the wider ingestion
 * refactor lands.
 *
 * <p>Thread-safety: {@link #extract(InputStream)} is safe to call concurrently
 * from multiple threads, since it allocates a fresh {@link XWPFDocument} per
 * call and holds no shared state.
 */
public final class DocxImageExtractor {

    private DocxImageExtractor() {
        // utility class — no instances
    }

    /**
     * Parse the given .docx stream and return every embedded image with its
     * surrounding context. Images appear in document order; the
     * {@code sequence} field is 1-indexed.
     *
     * @param docxStream an open input stream pointing at a .docx file; the
     *                   caller retains ownership and is responsible for closing
     *                   it (this method consumes the stream into a single
     *                   {@link XWPFDocument} which is closed before returning).
     * @return ordered list of extracted images (empty if the document has no
     *         embedded images). Never {@code null}.
     * @throws IOException if the stream cannot be read or is not a valid .docx
     */
    public static List<ExtractedImage> extract(InputStream docxStream) throws IOException {
        List<ExtractedImage> result = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(docxStream)) {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            String currentHeading = "";
            int seq = 0;

            for (int idx = 0; idx < paragraphs.size(); idx++) {
                XWPFParagraph para = paragraphs.get(idx);

                // Track the running section heading: every heading-styled
                // paragraph replaces it. Pictures inherit whichever heading
                // most recently preceded them in document order.
                if (isHeadingStyle(para)) {
                    String text = paragraphText(para);
                    if (!text.isEmpty()) {
                        currentHeading = text;
                    }
                }

                // Pictures are attached to runs (XWPFRun.getEmbeddedPictures).
                // A single paragraph can contain multiple pictures across
                // multiple runs; each picture independently records the
                // paragraph-level prev/next/heading context.
                String prevPara = (idx > 0)
                        ? paragraphText(paragraphs.get(idx - 1)) : "";
                String nextPara = (idx + 1 < paragraphs.size())
                        ? paragraphText(paragraphs.get(idx + 1)) : "";

                for (XWPFRun run : para.getRuns()) {
                    List<XWPFPicture> pictures = run.getEmbeddedPictures();
                    if (pictures == null || pictures.isEmpty()) {
                        continue;
                    }
                    for (XWPFPicture pic : pictures) {
                        seq++;
                        XWPFPictureData picData = pic.getPictureData();
                        result.add(new ExtractedImage(
                                seq,
                                picData.getFileName(),
                                picData.getPackagePart().getContentType(),
                                picData.getData(),
                                currentHeading,
                                prevPara,
                                nextPara
                        ));
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * True if the paragraph's style indicates a Word heading. Recognises both
     * the English default style IDs ({@code Heading1}, {@code Heading2}, ...)
     * and the Chinese default style IDs ({@code 标题 1}, {@code 标题 2}, ...),
     * plus any custom style whose ID begins with {@code "heading"}
     * case-insensitively.
     */
    private static boolean isHeadingStyle(XWPFParagraph para) {
        String style = para.getStyle();
        if (style == null) {
            return false;
        }
        return style.startsWith("Heading")
                || style.startsWith("标题")
                || style.toLowerCase().startsWith("heading");
    }

    private static String paragraphText(XWPFParagraph para) {
        String text = para.getText();
        return text == null ? "" : text.trim();
    }

    /**
     * One extracted image with its document-order sequence, raw bytes, and
     * surrounding-paragraph context. All string fields are non-null (empty
     * if the corresponding context is unavailable, e.g. the picture is in
     * the first or last paragraph, or the document has no headings).
     */
    public static final class ExtractedImage {
        private final int sequence;
        private final String fileName;
        private final String contentType;
        private final byte[] data;
        private final String sectionHeading;
        private final String prevParagraph;
        private final String nextParagraph;

        public ExtractedImage(int sequence, String fileName, String contentType,
                              byte[] data, String sectionHeading,
                              String prevParagraph, String nextParagraph) {
            this.sequence = sequence;
            this.fileName = fileName == null ? "" : fileName;
            this.contentType = contentType == null
                    ? "application/octet-stream" : contentType;
            this.data = data == null ? new byte[0] : data;
            this.sectionHeading = sectionHeading == null ? "" : sectionHeading;
            this.prevParagraph = prevParagraph == null ? "" : prevParagraph;
            this.nextParagraph = nextParagraph == null ? "" : nextParagraph;
        }

        public int getSequence() { return sequence; }
        public String getFileName() { return fileName; }
        public String getContentType() { return contentType; }
        public byte[] getData() { return data; }
        public String getSectionHeading() { return sectionHeading; }
        public String getPrevParagraph() { return prevParagraph; }
        public String getNextParagraph() { return nextParagraph; }

        public int sizeBytes() {
            return data.length;
        }
    }
}
