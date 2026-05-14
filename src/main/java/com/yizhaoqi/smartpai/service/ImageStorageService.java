package com.yizhaoqi.smartpai.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * MinIO object-store façade for extracted images. Canonical key layout is
 * {@code images/{fileMd5}/{idx}.{ext}}; the returned URI is the same key
 * prefixed with {@code minio://images/} so consumers do not depend on
 * bucket-name details.
 *
 * <p>Bucket name and endpoint come from the existing
 * {@code minio.bucket-name} / {@code minio.endpoint} keys in
 * {@code application.yml}. The bucket is assumed pre-created by the
 * existing document-upload flow; this service does not create it.
 */
@Service
public class ImageStorageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageStorageService.class);

    @Value("${minio.bucket-name}")
    private String bucket;

    private final MinioClient minio;

    public ImageStorageService(MinioClient minio) {
        this.minio = minio;
    }

    /**
     * Upload one image and return the canonical {@code minio://...} URI.
     *
     * @param fileMd5     owning document fingerprint
     * @param idx         1-indexed image order within the document
     * @param data        raw image bytes
     * @param contentType MIME type of {@code data}
     * @return canonical URI for use in {@code ImageChunk.imageUri} and
     *         {@code EsDocument.imageUri}
     */
    public String upload(String fileMd5, int idx, byte[] data, String contentType) {
        String ext = guessExtension(contentType);
        String key = "images/" + fileMd5 + "/" + idx + "." + ext;
        try {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            logger.debug("Uploaded image to MinIO: {} ({} bytes)", key, data.length);
            return "minio://" + bucket + "/" + key;
        } catch (MinioException | java.io.IOException
                | java.security.NoSuchAlgorithmException
                | java.security.InvalidKeyException e) {
            throw new RuntimeException("MinIO upload failed for " + key + ": " + e.getMessage(), e);
        }
    }

    private static String guessExtension(String contentType) {
        if (contentType == null) return "bin";
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            case "image/tiff" -> "tiff";
            case "image/x-emf", "image/emf" -> "emf";
            case "image/x-wmf", "image/wmf" -> "wmf";
            default -> "bin";
        };
    }
}
