package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates small display copies while preserving the original evidence object byte-for-byte. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaDerivativeService {
    private static final int PREVIEW_EDGE = 2048;
    private static final int THUMBNAIL_EDGE = 480;
    private static final int MAX_CACHED_URLS = 10_000;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final long MAX_DECODED_PIXELS = 20_000_000L;

    private final MediaAssetRepository media;
    private final IMediaObjectStorage storage;
    private final PlatformTransactionManager transactions;
    private final ConcurrentMap<String, CachedUrl> signedUrls = new ConcurrentHashMap<>();

    @Value("${app.media.derivatives.batch-size:8}")
    private int batchSize;

    @Value("${app.media.derivatives.stale-minutes:15}")
    private int staleMinutes;

    @Value("${app.media.derivatives.retry-delay-seconds:300}")
    private int retryDelaySeconds;

    @Value("${app.media.derivatives.max-attempts:5}")
    private int maxAttempts;

    public int processBatch() {
        Instant now = Instant.now();
        var ids = media.findDerivativeCandidates(now.minusSeconds(Math.max(1, staleMinutes) * 60L),
                now.minusSeconds(Math.max(30, retryDelaySeconds)),
                Math.max(1, maxAttempts),
                PageRequest.of(0, Math.max(1, batchSize)));
        int processed = 0;
        for (String id : ids) {
            if (process(id)) processed++;
        }
        return processed;
    }

    public String thumbnailUrl(MediaAsset asset) {
        return ready(asset) && asset.getThumbnailKey() != null
                ? signedUrl(asset.getS3Bucket(), asset.getThumbnailKey()) : null;
    }

    public String previewUrl(MediaAsset asset) {
        return ready(asset) && asset.getPreviewKey() != null
                ? signedUrl(asset.getS3Bucket(), asset.getPreviewKey()) : null;
    }

    private boolean process(String id) {
        Work work = new TransactionTemplate(transactions).execute(status -> {
            MediaAsset asset = media.findByIdForUpdate(id).orElse(null);
            if (asset == null || asset.getStorageStatus() != MediaStorageStatus.AVAILABLE
                    || (asset.getValidatedAt() == null && asset.getMediaStatus() != null)
                    || !"IMAGE".equals(asset.getType())
                    || asset.getDerivativeStatus() == MediaDerivativeStatus.READY
                    || asset.getDerivativeStatus() == MediaDerivativeStatus.UNSUPPORTED) return null;
            if (asset.getDerivativeStatus() == MediaDerivativeStatus.PROCESSING
                    && asset.getDerivativeProcessedAt() != null
                    && asset.getDerivativeProcessedAt().isAfter(
                            Instant.now().minusSeconds(Math.max(1, staleMinutes) * 60L))) return null;
            if (asset.getDerivativeStatus() == MediaDerivativeStatus.PROCESSING
                    && asset.getDerivativeAttemptCount() >= Math.max(1, maxAttempts)) {
                asset.setDerivativeStatus(MediaDerivativeStatus.FAILED);
                asset.setDerivativeProcessedAt(Instant.now());
                asset.setDerivativeErrorCode("DERIVATIVE_RETRY_LIMIT_REACHED");
                media.save(asset);
                return null;
            }
            asset.setDerivativeStatus(MediaDerivativeStatus.PROCESSING);
            asset.setDerivativeAttemptCount(asset.getDerivativeAttemptCount() + 1);
            asset.setDerivativeProcessedAt(Instant.now());
            asset.setDerivativeErrorCode(null);
            media.save(asset);
            return new Work(asset.getId(), asset.getS3Bucket(), asset.getS3Key(), asset.getDerivativeAttemptCount());
        });
        if (work == null) return false;

        String thumbnailKey = derivativeKey(work, "thumbnail.jpg");
        String previewKey = derivativeKey(work, "preview.jpg");
        try (var input = storage.open(work.bucket(), work.originalKey()).inputStream()) {
            BufferedImage decoded = decode(input);
            byte[] preview = jpeg(resize(decoded, PREVIEW_EDGE), 0.84f);
            byte[] thumbnail = jpeg(resize(decoded, THUMBNAIL_EDGE), 0.78f);
            storage.put(thumbnailKey, "image/jpeg", thumbnail.length,
                    new ByteArrayInputStream(thumbnail), "[MEDIA-THUMBNAIL]");
            storage.put(previewKey, "image/jpeg", preview.length,
                    new ByteArrayInputStream(preview), "[MEDIA-PREVIEW]");
            boolean saved = new TransactionTemplate(transactions).execute(status -> {
                MediaAsset asset = media.findByIdForUpdate(id).orElse(null);
                if (asset == null || asset.getStorageStatus() != MediaStorageStatus.AVAILABLE
                        || !ownsAttempt(asset, work)) return false;
                asset.setThumbnailKey(thumbnailKey);
                asset.setPreviewKey(previewKey);
                asset.setThumbnailSizeBytes((long) thumbnail.length);
                asset.setPreviewSizeBytes((long) preview.length);
                asset.setDerivativeStatus(MediaDerivativeStatus.READY);
                asset.setDerivativeProcessedAt(Instant.now());
                asset.setDerivativeErrorCode(null);
                media.save(asset);
                return true;
            });
            if (!Boolean.TRUE.equals(saved)) {
                storage.deleteQuietly(work.bucket(), thumbnailKey);
                storage.deleteQuietly(work.bucket(), previewKey);
            }
            return Boolean.TRUE.equals(saved);
        } catch (Exception failure) {
            storage.deleteQuietly(work.bucket(), thumbnailKey);
            storage.deleteQuietly(work.bucket(), previewKey);
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    media.findByIdForUpdate(id).ifPresent(asset -> {
                        if (ownsAttempt(asset, work)) {
                            asset.setDerivativeStatus(failure instanceof UnsupportedImageException
                                    ? MediaDerivativeStatus.UNSUPPORTED : MediaDerivativeStatus.FAILED);
                            asset.setDerivativeProcessedAt(Instant.now());
                            asset.setDerivativeErrorCode(asset.getDerivativeAttemptCount() >= Math.max(1, maxAttempts)
                                    && !(failure instanceof UnsupportedImageException)
                                    ? "DERIVATIVE_RETRY_LIMIT_REACHED" : errorCode(failure));
                            media.save(asset);
                        }
                    }));
            log.warn("Media derivative creation failed. mediaId={}, cause={}",
                    id, failure.getClass().getSimpleName());
            return false;
        }
    }

    private BufferedImage decode(java.io.InputStream input) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) throw new IOException("Unreadable image stream");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new UnsupportedImageException();
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width <= 0 || height <= 0 || pixels > MAX_SOURCE_PIXELS)
                    throw new UnsupportedImageException("Image dimensions exceed processing limits");
                int sample = Math.max(1, (int) Math.ceil(Math.sqrt((double) pixels / MAX_DECODED_PIXELS)));
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, params);
                if (decoded == null) throw new UnsupportedImageException();
                return decoded;
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage resize(BufferedImage source, int maxEdge) {
        double scale = Math.min(1d, (double) maxEdge / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] jpeg(BufferedImage image, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG encoder unavailable");
        var writer = writers.next();
        try (var bytes = new ByteArrayOutputStream(); var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private boolean ownsAttempt(MediaAsset asset, Work work) {
        return asset.getDerivativeStatus() == MediaDerivativeStatus.PROCESSING
                && asset.getDerivativeAttemptCount() == work.attempt()
                && java.util.Objects.equals(asset.getS3Key(), work.originalKey());
    }

    private String derivativeKey(Work work, String name) {
        String prefix = storage.prefix();
        String base = prefix == null || prefix.isBlank() ? "" : prefix.strip().replaceAll("^/+|/+$", "") + "/";
        return base + "derived/" + work.id() + "/v1/attempt-" + work.attempt() + "/" + name;
    }

    private boolean ready(MediaAsset asset) {
        return asset.getStorageStatus() == MediaStorageStatus.AVAILABLE
                && asset.getDerivativeStatus() == MediaDerivativeStatus.READY;
    }

    private synchronized String signedUrl(String bucket, String key) {
        String cacheKey = bucket + "\n" + key;
        Instant now = Instant.now();
        CachedUrl cached = signedUrls.get(cacheKey);
        if (cached != null && cached.refreshAt().isAfter(now)) return cached.url();
        if (signedUrls.size() >= MAX_CACHED_URLS) {
            signedUrls.entrySet().removeIf(entry -> !entry.getValue().refreshAt().isAfter(now));
            if (signedUrls.size() >= MAX_CACHED_URLS)
                return storage.createPresignedGetUrl(bucket, key);
        }
        long cacheSeconds = Math.max(1, storage.presignedUrlExpiresSeconds() - 60);
        cached = new CachedUrl(storage.createPresignedGetUrl(bucket, key), now.plusSeconds(cacheSeconds));
        signedUrls.put(cacheKey, cached);
        return cached.url();
    }

    private String errorCode(Exception failure) {
        return failure instanceof UnsupportedImageException ? "UNSUPPORTED_IMAGE_FORMAT"
                : "DERIVATIVE_PROCESSING_FAILED";
    }

    private record Work(String id, String bucket, String originalKey, int attempt) {}
    private record CachedUrl(String url, Instant refreshAt) {}

    private static final class UnsupportedImageException extends IOException {
        private UnsupportedImageException() { super("Image format cannot be decoded"); }
        private UnsupportedImageException(String message) { super(message); }
    }
}
