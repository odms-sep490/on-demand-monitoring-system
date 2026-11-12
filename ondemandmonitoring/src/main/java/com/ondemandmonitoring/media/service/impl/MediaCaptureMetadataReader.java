package com.ondemandmonitoring.media.service.impl;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Reads capture tags only; filesystem modification dates are never capture evidence. */
public final class MediaCaptureMetadataReader {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter EXIF = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    public record CaptureTime(Instant instant, String zone) {}

    private MediaCaptureMetadataReader() {}

    public static CaptureTime read(Path file) throws IOException {
        try {
            var metadata = ImageMetadataReader.readMetadata(file.toFile());
            var exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exif != null && exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) != null) {
                String value = exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL).trim();
                String offset = exif.getString(0x9011); // EXIF OffsetTimeOriginal
                return parseExif(value, offset);
            }
            var mp4 = metadata.getFirstDirectoryOfType(Mp4Directory.class);
            if (mp4 != null) {
                var date = mp4.getDate(Mp4Directory.TAG_CREATION_TIME);
                if (date != null && !date.toInstant().equals(Instant.parse("1904-01-01T00:00:00Z")))
                    return new CaptureTime(date.toInstant(), "UTC");
            }
            return null;
        } catch (ImageProcessingException | DateTimeException malformedMetadata) {
            return null;
        }
    }

    static CaptureTime parseExif(String value, String offset) {
        var local = LocalDateTime.parse(value, EXIF);
        if (offset == null || offset.isBlank()) return new CaptureTime(local.atZone(VIETNAM).toInstant(), VIETNAM.getId());
        var zone = ZoneOffset.of(offset.trim());
        return new CaptureTime(local.atOffset(zone).toInstant(), zone.getId());
    }
}
