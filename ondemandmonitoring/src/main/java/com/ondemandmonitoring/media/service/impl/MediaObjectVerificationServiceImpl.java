package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.service.IMediaObjectVerificationService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaObjectVerificationServiceImpl implements IMediaObjectVerificationService {
    IMediaObjectStorage storage;

    @Override
    public String verify(MediaAsset captured, String key) {
        var object = storage.inspect(captured.getS3Bucket(), key);

        if (!captured.getFileSize().equals(object.contentLength())) {
            return "Object size differs from capture metadata";
        }

        if (!captured.getContentType().equalsIgnoreCase(object.contentType())) {
            return "Object content type differs from capture metadata";
        }

        if (!captured.getId().equals(object.metadata().get("media-id"))
                || !captured.getChecksumSha256().equalsIgnoreCase(object.metadata().get("sha256"))) {
            return "Object metadata differs from upload request";
        }

        java.nio.file.Path metadataFile = null;
        try {
            if (captured.getCaptureTimeSource() != null) metadataFile = java.nio.file.Files.createTempFile("odms-capture-", ".media");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot prepare capture metadata verification", failure);
        }
        try (var metadataOutput = metadataFile == null ? java.io.OutputStream.nullOutputStream()
                    : java.nio.file.Files.newOutputStream(metadataFile);
                InputStream stream = storage.open(captured.getS3Bucket(), key).inputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] signature = stream.readNBytes(12);
            digest.update(signature);
            metadataOutput.write(signature);
            long total = signature.length;
            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > captured.getFileSize()) return "Object exceeds declared size";
                digest.update(buffer, 0, read);
                metadataOutput.write(buffer, 0, read);
            }

            if (!validSignature(captured.getContentType(), signature)) {
                return "File signature differs from declared content type";
            }

            if (!HexFormat.of().formatHex(digest.digest())
                    .equalsIgnoreCase(captured.getChecksumSha256())) {
                return "SHA-256 checksum mismatch";
            }

            metadataOutput.flush();
            if (metadataFile != null) return verifyCaptureTime(captured, metadataFile);
            return null;

        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot inspect uploaded object", error);
        } finally {
            if (metadataFile != null) {
                try { java.nio.file.Files.deleteIfExists(metadataFile); }
                catch (IOException failure) { throw new IllegalStateException("Cannot remove capture metadata temporary file", failure); }
            }
        }
    }

    private String verifyCaptureTime(MediaAsset asset, java.nio.file.Path file) throws IOException {
        var actual = MediaCaptureMetadataReader.read(file);
        asset.setMetadataCapturedAt(actual == null ? null : actual.instant());
        asset.setMetadataTimeZone(actual == null ? null : actual.zone());
        boolean confirmed = "STAFF_CONFIRMED".equals(asset.getCaptureTimeSource())
                && asset.getCaptureTimeConfirmedBy() != null
                && asset.getCaptureTimeConfirmationReason() != null
                && !asset.getCaptureTimeConfirmationReason().isBlank();
        if (actual == null) return confirmed ? null : "Capture metadata missing; confirm capture time and provide a reason";
        var instant = actual.instant();
        if (instant.isBefore(java.time.Instant.parse("2000-01-01T00:00:00Z"))
                || instant.isAfter(java.time.Instant.now().plusSeconds(300))) return "Capture metadata time is invalid";
        var mission = asset.getMission();
        if (mission == null) return "Mission capture window is unavailable";
        var start = mission.getActualStartAt() != null ? mission.getActualStartAt() : mission.getScheduledStartAt();
        var end = mission.getActualEndAt() != null ? mission.getActualEndAt() : mission.getScheduledEndAt();
        if (start == null || end == null || !end.isAfter(start)
                || instant.isBefore(start.minusSeconds(1800)) || instant.isAfter(end.plusSeconds(1800)))
            return "Capture metadata time is outside this Mission's flight window";
        if (Math.abs(java.time.Duration.between(asset.getCapturedAt(), instant).getSeconds()) > 2 && !confirmed)
            return "Capture metadata differs from declared time; confirm the correction and provide a reason";
        return null;
    }

    private boolean validSignature(String contentType, byte[] value) {
        return switch (contentType) {
            case "image/jpeg" -> value.length >= 3 && (value[0] & 0xff) == 0xff
                    && (value[1] & 0xff) == 0xd8 && (value[2] & 0xff) == 0xff;
            case "image/png" -> value.length >= 8 && Arrays.equals(Arrays.copyOf(value, 8),
                    new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "video/mp4" -> value.length >= 8 && value[4] == 'f' && value[5] == 't'
                    && value[6] == 'y' && value[7] == 'p';
            default -> false;
        };
    }

}

