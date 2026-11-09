package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MediaVideoDerivativeTest {
    @TempDir Path temp;
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    final MediaDerivativeService service = new MediaDerivativeService(media, storage, tx);
    MediaAsset asset;
    void setup(byte[] bytes, String ffmpeg, String ffprobe) {
        for (var entry : Map.<String, Object>of("batchSize", 1, "videoBatchSize", 1, "staleMinutes", 15,
                "retryDelaySeconds", 30, "maxAttempts", 5, "videoTimeoutSeconds", 30,
                "maxVideoInputBytes", 100_000_000L, "maxVideoPreviewBytes", 20_000_000L,
                "ffmpegBinary", ffmpeg, "ffprobeBinary", ffprobe).entrySet())
            ReflectionTestUtils.setField(service, entry.getKey(), entry.getValue());
        asset = new MediaAsset(); asset.setId("video"); asset.setType("VIDEO");
        asset.setStorageProvider("S3"); asset.setS3Bucket("bucket"); asset.setS3Key("source.mp4");
        asset.setFileSize((long) bytes.length); asset.setMediaStatus(MediaStatus.AVAILABLE); asset.setValidatedAt(Instant.now());
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(media.findDerivativeCandidates(eq("VIDEO"), any(), any(), anyInt(), any())).thenReturn(List.of("video"));
        when(media.findByIdForUpdate("video")).thenReturn(Optional.of(asset));
        when(storage.open("bucket", "source.mp4")).thenAnswer(call -> new IMediaObjectStorage.StoredObjectStream(
                new ByteArrayInputStream(bytes), (long) bytes.length, "video/mp4"));
    }
    @Test void missingProbeHasActionableErrorEvenAtRetryLimit() {
        setup(new byte[]{1, 2, 3}, "ffmpeg", temp.resolve("missing-ffprobe.exe").toString());
        asset.setDerivativeAttemptCount(4);
        assertThat(service.processBatch()).isZero();
        assertThat(asset.getDerivativeStatus()).isEqualTo(MediaDerivativeStatus.FAILED);
        assertThat(asset.getDerivativeErrorCode()).isEqualTo("FFPROBE_UNAVAILABLE");
        verify(storage, never()).put(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
    }
    @Test void realVideoProducesPosterAndPreviewUsingConfiguredExecutables() throws Exception {
        String ffmpeg = System.getProperty("qa.ffmpeg");
        String ffprobe = System.getProperty("qa.ffprobe");
        Assumptions.assumeTrue(ffmpeg != null && ffprobe != null, "Set qa.ffmpeg and qa.ffprobe for real transcoding verification");
        Path source = temp.resolve("source.mp4");
        Path log = temp.resolve("fixture.log");
        Process process = new ProcessBuilder(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc=size=640x360:rate=10", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv444p", source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
        setup(Files.readAllBytes(source), ffmpeg, ffprobe);
        Path encodedPreview = temp.resolve("encoded-preview.mp4");
        doAnswer(call -> {
            Files.copy(call.getArgument(4, java.io.InputStream.class), encodedPreview);
            return null;
        }).when(storage).put(eq("bucket"), endsWith("preview.mp4"), eq("video/mp4"), anyLong(), any(), anyString());
        assertThat(service.processBatch()).isEqualTo(1);
        assertThat(asset.getDerivativeStatus()).isEqualTo(MediaDerivativeStatus.READY);
        assertThat(asset.getThumbnailKey()).endsWith("thumbnail.jpg");
        assertThat(asset.getPreviewKey()).endsWith("preview.mp4");
        assertThat(asset.getThumbnailSizeBytes()).isPositive();
        assertThat(asset.getPreviewSizeBytes()).isPositive();
        Path pixelFormat = temp.resolve("pixel-format.txt");
        Process probe = new ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=pix_fmt", "-of", "csv=p=0", encodedPreview.toString())
                .redirectErrorStream(true).redirectOutput(pixelFormat.toFile()).start();
        boolean probeFinished = probe.waitFor(15, TimeUnit.SECONDS);
        if (!probeFinished) probe.destroyForcibly();
        assertThat(probeFinished).isTrue();
        assertThat(probe.exitValue()).isZero();
        assertThat(Files.readString(pixelFormat).trim()).isEqualTo("yuv420p");
        verify(storage).put(eq("bucket"), endsWith("thumbnail.jpg"), eq("image/jpeg"), anyLong(), any(), anyString());
        verify(storage).put(eq("bucket"), endsWith("preview.mp4"), eq("video/mp4"), anyLong(), any(), anyString());
    }
}
