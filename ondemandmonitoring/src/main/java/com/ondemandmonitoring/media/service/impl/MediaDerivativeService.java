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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    private final Semaphore videoProcessor = new Semaphore(1);

    @Value("${app.media.derivatives.batch-size:8}")
    private int batchSize;

    @Value("${app.media.derivatives.stale-minutes:15}")
    private int staleMinutes;

    @Value("${app.media.derivatives.retry-delay-seconds:300}")
    private int retryDelaySeconds;

    @Value("${app.media.derivatives.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.media.derivatives.video-batch-size:1}")
    private int videoBatchSize;

    @Value("${app.media.derivatives.ffmpeg-bin:ffmpeg}")
    private String ffmpegBinary;

    @Value("${app.media.derivatives.ffprobe-bin:ffprobe}")
    private String ffprobeBinary;

    @Value("${app.media.derivatives.video-timeout-seconds:900}")
    private int videoTimeoutSeconds;

    @Value("${app.media.derivatives.max-video-input-bytes:1073741824}")
    private long maxVideoInputBytes;

    @Value("${app.media.derivatives.max-video-preview-bytes:536870912}")
    private long maxVideoPreviewBytes;

    public int processBatch() {
        int processed = processType("IMAGE", batchSize);
        if (!videoProcessor.tryAcquire()) return processed;
        try {
            return processed + processType("VIDEO", videoBatchSize);
        } finally {
            videoProcessor.release();
        }
    }

    private int processType(String mediaType, int limit) {
        Instant now = Instant.now();
        var ids = media.findDerivativeCandidates(mediaType,
                now.minusSeconds(Math.max(1, staleMinutes) * 60L),
                now.minusSeconds(Math.max(30, retryDelaySeconds)),
                Math.max(1, maxAttempts),
                PageRequest.of(0, Math.max(1, limit)));
        int processed = 0;
        for (String id : ids) {
            if (process(id, mediaType)) processed++;
        }
        return processed;
    }

    public String thumbnailUrl(MediaAsset asset) {
        if (ready(asset) && hasKey(asset.getThumbnailKey()))
            return signedUrl(asset.getS3Bucket(), asset.getThumbnailKey());
        // A video URL cannot be used as an <img> source or video poster.
        if (!"IMAGE".equals(asset.getType())) return null;
        if (ready(asset) && hasKey(asset.getPreviewKey()))
            return signedUrl(asset.getS3Bucket(), asset.getPreviewKey());
        return originalViewingFallback(asset);
    }

    /** Viewing URLs may reference the original after derivative failure; callers must authorize access. */
    public String previewUrl(MediaAsset asset) {
        return ready(asset) && hasKey(asset.getPreviewKey())
                ? signedUrl(asset.getS3Bucket(), asset.getPreviewKey()) : originalViewingFallback(asset);
    }

    private String originalViewingFallback(MediaAsset asset) {
        var state = asset.getDerivativeStatus();
        if (asset.getStorageStatus() != MediaStorageStatus.AVAILABLE
                || !"S3".equals(asset.getStorageProvider())
                || !("IMAGE".equals(asset.getType()) || "VIDEO".equals(asset.getType()))
                || !hasKey(asset.getS3Bucket()) || !hasKey(asset.getS3Key())
                || (state != MediaDerivativeStatus.FAILED && state != MediaDerivativeStatus.UNSUPPORTED
                    && state != MediaDerivativeStatus.READY
                    && !(state == MediaDerivativeStatus.PROCESSING && asset.getDerivativeAttemptCount() > 1))) return null;
        return signedUrl(asset.getS3Bucket(), asset.getS3Key());
    }

    private boolean hasKey(String value) {
        return value != null && !value.isBlank();
    }

    private boolean process(String id, String mediaType) {
        Work work = new TransactionTemplate(transactions).execute(status -> {
            MediaAsset asset = media.findByIdForUpdate(id).orElse(null);
            if (asset == null || asset.getStorageStatus() != MediaStorageStatus.AVAILABLE
                    || (asset.getValidatedAt() == null && asset.getMediaStatus() != null)
                    || !mediaType.equals(asset.getType())
                    || asset.getDerivativeStatus() == MediaDerivativeStatus.READY
                    || asset.getDerivativeStatus() == MediaDerivativeStatus.UNSUPPORTED) return null;
            Instant now = Instant.now();
            boolean staleProcessing = asset.getDerivativeStatus() == MediaDerivativeStatus.PROCESSING
                    && (asset.getDerivativeProcessedAt() == null || asset.getDerivativeProcessedAt()
                            .isBefore(now.minusSeconds(Math.max(1, staleMinutes) * 60L)));
            if (asset.getDerivativeStatus() == MediaDerivativeStatus.PROCESSING && !staleProcessing) return null;
            if (asset.getDerivativeStatus() == MediaDerivativeStatus.FAILED
                    && asset.getDerivativeProcessedAt() != null
                    && asset.getDerivativeProcessedAt().isAfter(now.minusSeconds(Math.max(30, retryDelaySeconds)))) return null;
            if (asset.getDerivativeAttemptCount() >= Math.max(1, maxAttempts)) {
                asset.setDerivativeStatus(MediaDerivativeStatus.FAILED);
                asset.setDerivativeProcessedAt(now);
                asset.setDerivativeErrorCode("DERIVATIVE_RETRY_LIMIT_REACHED");
                media.save(asset);
                return null;
            }
            asset.setDerivativeStatus(MediaDerivativeStatus.PROCESSING);
            asset.setDerivativeAttemptCount(asset.getDerivativeAttemptCount() + 1);
            asset.setDerivativeProcessedAt(now);
            asset.setDerivativeErrorCode(null);
            media.save(asset);
            return new Work(asset.getId(), asset.getType(), asset.getS3Bucket(), asset.getS3Key(),
                    asset.getFileSize(), asset.getDerivativeAttemptCount());
        });
        if (work == null) return false;

        String thumbnailKey = derivativeKey(work, "thumbnail.jpg");
        String previewKey = derivativeKey(work, "VIDEO".equals(work.mediaType()) ? "preview.mp4" : "preview.jpg");
        Path tempDirectory = null;
        try {
            if ("IMAGE".equals(work.mediaType())) {
                try (var input = storage.open(work.bucket(), work.originalKey()).inputStream()) {
                    BufferedImage decoded = decode(input);
                    byte[] preview = jpeg(resize(decoded, PREVIEW_EDGE), 0.84f);
                    byte[] thumbnail = jpeg(resize(decoded, THUMBNAIL_EDGE), 0.78f);
                    storage.put(work.bucket(), thumbnailKey, "image/jpeg", thumbnail.length,
                            new ByteArrayInputStream(thumbnail), "[MEDIA-THUMBNAIL]");
                    storage.put(work.bucket(), previewKey, "image/jpeg", preview.length,
                            new ByteArrayInputStream(preview), "[MEDIA-PREVIEW]");
                    return persistSuccess(work, thumbnailKey, previewKey, thumbnail.length, preview.length);
                }
            }

            tempDirectory = Files.createTempDirectory("odms-media-derivative-");
            Path inputPath = tempDirectory.resolve("source.bin");
            Path thumbnailPath = tempDirectory.resolve("poster.jpg");
            Path previewPath = tempDirectory.resolve("preview.mp4");
            copyVideoToDisk(work, inputPath);
            VideoInfo source = probeVideo(inputPath, tempDirectory, "source");
            createVideoPreview(inputPath, thumbnailPath, previewPath, tempDirectory);
            long thumbnailSize = Files.size(thumbnailPath);
            long previewSize = Files.size(previewPath);
            if (thumbnailSize <= 0 || previewSize <= 0 || previewSize > maxVideoPreviewBytes)
                throw new IOException("Video derivative output is empty or exceeds configured size");
            VideoInfo output = probeVideo(previewPath, tempDirectory, "preview");
            if (output.width() > 1280 || output.height() > 720
                    || output.durationSeconds() + 2 < source.durationSeconds())
                throw new IOException("Video preview failed output validation");
            try (InputStream poster = Files.newInputStream(thumbnailPath);
                 InputStream preview = Files.newInputStream(previewPath)) {
                storage.put(work.bucket(), thumbnailKey, "image/jpeg", thumbnailSize, poster, "[MEDIA-VIDEO-POSTER]");
                storage.put(work.bucket(), previewKey, "video/mp4", previewSize, preview, "[MEDIA-VIDEO-PREVIEW]");
            }
            return persistSuccess(work, thumbnailKey, previewKey, thumbnailSize, previewSize);
        } catch (Exception failure) {
            storage.deleteQuietly(work.bucket(), thumbnailKey);
            storage.deleteQuietly(work.bucket(), previewKey);
            markFailure(work, failure);
            log.warn("Media derivative creation failed. mediaId={}, mediaType={}, cause={}",
                    id, mediaType, failure.getClass().getSimpleName());
            return false;
        } finally {
            if (tempDirectory != null) deleteTemporaryFiles(tempDirectory);
        }
    }

    private boolean persistSuccess(Work work, String thumbnailKey, String previewKey,
            long thumbnailSize, long previewSize) {
            boolean saved = new TransactionTemplate(transactions).execute(status -> {
                MediaAsset asset = media.findByIdForUpdate(work.id()).orElse(null);
                if (asset == null || asset.getStorageStatus() != MediaStorageStatus.AVAILABLE
                        || !ownsAttempt(asset, work)) return false;
                asset.setThumbnailKey(thumbnailKey);
                asset.setPreviewKey(previewKey);
                asset.setThumbnailSizeBytes(thumbnailSize);
                asset.setPreviewSizeBytes(previewSize);
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
    }

    private void markFailure(Work work, Exception failure) {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                media.findByIdForUpdate(work.id()).ifPresent(asset -> {
                    if (ownsAttempt(asset, work)) {
                        asset.setDerivativeStatus(failure instanceof UnsupportedMediaException
                                ? MediaDerivativeStatus.UNSUPPORTED : MediaDerivativeStatus.FAILED);
                        asset.setDerivativeProcessedAt(Instant.now());
                        asset.setDerivativeErrorCode(asset.getDerivativeAttemptCount() >= Math.max(1, maxAttempts)
                                && !(failure instanceof UnsupportedMediaException)
                                ? "DERIVATIVE_RETRY_LIMIT_REACHED" : errorCode(failure));
                        media.save(asset);
                    }
                }));
    }

    private void copyVideoToDisk(Work work, Path destination) throws IOException {
        if (work.fileSize() == null || work.fileSize() <= 0 || work.fileSize() > maxVideoInputBytes)
            throw new UnsupportedMediaException("Video file exceeds configured processing limit");
        try (InputStream input = storage.open(work.bucket(), work.originalKey()).inputStream();
             OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > maxVideoInputBytes) throw new UnsupportedMediaException("Video file exceeds configured processing limit");
                output.write(buffer, 0, read);
            }
            if (copied == 0) throw new IOException("Video file is empty");
        }
    }

    private VideoInfo probeVideo(Path file, Path tempDirectory, String label) throws IOException, InterruptedException {
        Path dimensionsOutput = tempDirectory.resolve(label + "-dimensions.txt");
        Path dimensionsError = tempDirectory.resolve(label + "-dimensions.err");
        runTool(List.of(ffprobeBinary, "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=width,height", "-of", "csv=s=x:p=0", file.toString()),
                dimensionsOutput, dimensionsError);
        String dimensions = Files.readString(dimensionsOutput).trim();
        String[] dimensionParts = dimensions.split("x");
        if (dimensionParts.length != 2) throw new UnsupportedMediaException("Video has no readable video stream");
        int width;
        int height;
        try {
            width = Integer.parseInt(dimensionParts[0].trim());
            height = Integer.parseInt(dimensionParts[1].trim());
        } catch (NumberFormatException invalid) {
            throw new UnsupportedMediaException("Video dimensions are invalid");
        }
        Path durationOutput = tempDirectory.resolve(label + "-duration.txt");
        Path durationError = tempDirectory.resolve(label + "-duration.err");
        runTool(List.of(ffprobeBinary, "-v", "error", "-show_entries", "format=duration", "-of",
                "default=noprint_wrappers=1:nokey=1", file.toString()), durationOutput, durationError);
        double duration;
        try {
            duration = Double.parseDouble(Files.readString(durationOutput).trim());
        } catch (NumberFormatException invalid) {
            throw new UnsupportedMediaException("Video duration is invalid");
        }
        if (width <= 0 || height <= 0 || duration <= 0 || !Double.isFinite(duration))
            throw new UnsupportedMediaException("Video metadata is invalid");
        return new VideoInfo(width, height, duration);
    }

    private void createVideoPreview(Path input, Path thumbnail, Path preview,
            Path tempDirectory) throws IOException, InterruptedException {
        Path posterError = tempDirectory.resolve("poster.err");
        runTool(List.of(ffmpegBinary, "-y", "-hide_banner", "-loglevel", "error", "-i", input.toString(),
                "-map", "0:v:0", "-frames:v", "1", "-vf",
                "scale=w='min(480,iw)':h='min(480,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-q:v", "3", thumbnail.toString()), tempDirectory.resolve("poster.out"), posterError);
        Path previewError = tempDirectory.resolve("preview.err");
        runTool(List.of(ffmpegBinary, "-y", "-hide_banner", "-loglevel", "error", "-i", input.toString(),
                "-map", "0:v:0", "-map", "0:a?", "-vf",
                "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "29", "-maxrate", "2500k",
                "-bufsize", "5000k", "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart",
                "-fs", Long.toString(Math.max(1, maxVideoPreviewBytes)), preview.toString()),
                tempDirectory.resolve("preview.out"), previewError);
    }

    private void runTool(List<String> command, Path stdout, Path stderr) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(new ArrayList<>(command))
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
        } catch (IOException unavailable) {
            throw new IOException("Media processing tool is unavailable: " + command.get(0), unavailable);
        }
        boolean completed;
        try {
            completed = process.waitFor(Math.max(30, videoTimeoutSeconds), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new IOException("Media processing timed out: " + command.get(0));
        }
        if (process.exitValue() != 0)
            throw new IOException("Media processing failed: " + readTail(stderr));
    }

    private String readTail(Path path) {
        try {
            long length = Files.size(path);
            try (var stream = Files.newInputStream(path)) {
                stream.skip(Math.max(0, length - 4096));
                return new String(stream.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("[\\r\\n]+", " ").trim();
            }
        } catch (IOException ignored) {
            return "tool returned a nonzero status";
        }
    }

    private void deleteTemporaryFiles(Path directory) {
        try (var paths = Files.list(directory)) {
            paths.forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException failure) { log.debug("Could not remove media temp file {}", path.getFileName()); }
            });
        } catch (IOException failure) {
            log.debug("Could not list media temp directory");
        }
        try { Files.deleteIfExists(directory); }
        catch (IOException failure) { log.debug("Could not remove media temp directory"); }
    }

    private BufferedImage decode(java.io.InputStream input) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) throw new IOException("Unreadable image stream");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new UnsupportedMediaException();
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width <= 0 || height <= 0 || pixels > MAX_SOURCE_PIXELS)
                    throw new UnsupportedMediaException("Image dimensions exceed processing limits");
                int sample = Math.max(1, (int) Math.ceil(Math.sqrt((double) pixels / MAX_DECODED_PIXELS)));
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, params);
                if (decoded == null) throw new UnsupportedMediaException();
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
        return failure instanceof UnsupportedMediaException
                ? "UNSUPPORTED_MEDIA_FORMAT"
                : "DERIVATIVE_PROCESSING_FAILED";
    }

    private record Work(String id, String mediaType, String bucket, String originalKey, Long fileSize, int attempt) {}
    private record VideoInfo(int width, int height, double durationSeconds) {}
    private record CachedUrl(String url, Instant refreshAt) {}

    private static final class UnsupportedMediaException extends IOException {
        private UnsupportedMediaException() { super("Media format cannot be decoded"); }
        private UnsupportedMediaException(String message) { super(message); }
    }
}
