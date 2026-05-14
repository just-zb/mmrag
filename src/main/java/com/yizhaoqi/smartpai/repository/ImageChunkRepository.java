package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.entity.ImageChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link ImageChunk}. Permission-scoped lookups follow the
 * same pattern as {@code FileUploadRepository}: a row is accessible to a user
 * if it belongs to the user, is public, or is owned by an organisation tag the
 * user belongs to.
 */
@Repository
public interface ImageChunkRepository extends JpaRepository<ImageChunk, Long> {

    Optional<ImageChunk> findByFileMd5AndIdx(String fileMd5, Integer idx);

    List<ImageChunk> findByFileMd5OrderByIdxAsc(String fileMd5);

    @Query("""
            SELECT i FROM ImageChunk i
            WHERE i.fileMd5 = :fileMd5
              AND (i.userId = :userId
                   OR i.isPublic = true
                   OR (i.orgTag IN :orgTags AND i.isPublic = false))
            ORDER BY i.idx ASC
            """)
    List<ImageChunk> findAccessibleByFileMd5(
            @Param("fileMd5") String fileMd5,
            @Param("userId") String userId,
            @Param("orgTags") List<String> orgTags);

    void deleteByFileMd5(String fileMd5);
}
