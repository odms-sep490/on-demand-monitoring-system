package com.ondemandmonitoring.media.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.domain.Mission;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MediaCaptureMetadataVerificationTest {
    static final Instant TIME = Instant.parse("2026-11-10T01:15:30Z");
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final MediaObjectVerificationServiceImpl verification = new MediaObjectVerificationServiceImpl(storage);

    @Test void exifOffsetsAreIndependentOfMachineTimezone() {
        assertThat(MediaCaptureMetadataReader.parseExif("2026:11:10 08:15:30", null).instant()).isEqualTo(TIME);
        assertThat(MediaCaptureMetadataReader.parseExif("2026:11:10 03:15:30", "+02:00").instant()).isEqualTo(TIME);
        assertThatThrownBy(() -> MediaCaptureMetadataReader.parseExif("2026:02:30 08:15:30", null))
                .isInstanceOf(DateTimeException.class);
    }

    @Test void metadataExtractionIsIndependentOfJvmTimezone() throws Exception {
        var previous = TimeZone.getDefault();
        var file = Files.createTempFile("odms-capture-zone-test-", ".media");
        try {
            for (String zone : List.of("UTC", "America/New_York", "Asia/Ho_Chi_Minh")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                for (byte[] bytes : List.of(jpeg(true), jpeg(false), mp4())) {
                    Files.write(file, bytes);
                    assertThat(MediaCaptureMetadataReader.read(file).instant()).as(zone).isEqualTo(TIME);
                }
            }
        } finally {
            TimeZone.setDefault(previous);
            Files.deleteIfExists(file);
        }
    }

    @Test void readsRealJpegExifAndKeepsOffsetProvenance() throws Exception {
        var asset = asset(jpeg(true), "image/jpeg");
        assertThat(verification.verify(asset, "key")).isNull();
        assertThat(asset.getMetadataCapturedAt()).isEqualTo(TIME);
        assertThat(asset.getMetadataTimeZone()).isEqualTo("+07:00");
    }

    @Test void readsOffsetlessJpegAsVietnamTime() throws Exception {
        var asset = asset(jpeg(false), "image/jpeg");
        assertThat(verification.verify(asset, "key")).isNull();
        assertThat(asset.getMetadataCapturedAt()).isEqualTo(TIME);
        assertThat(asset.getMetadataTimeZone()).isEqualTo("Asia/Ho_Chi_Minh");
    }

    @Test void readsPngExifCaptureTime() throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        var ihdr = ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte)8).put((byte)2).put(new byte[3]).array();
        chunk(out, "IHDR", ihdr);
        chunk(out, "eXIf", Arrays.copyOfRange(jpeg(true), 12, 95));
        chunk(out, "IEND", new byte[0]);
        var asset = asset(bytes.toByteArray(), "image/png");
        assertThat(verification.verify(asset, "key")).isNull();
        assertThat(asset.getMetadataCapturedAt()).isEqualTo(TIME);
    }

    static void chunk(DataOutputStream out, String type, byte[] payload) throws IOException {
        var name = type.getBytes(StandardCharsets.US_ASCII); var crc = new java.util.zip.CRC32();
        crc.update(name); crc.update(payload);
        out.writeInt(payload.length); out.write(name); out.write(payload); out.writeInt((int)crc.getValue());
    }

    @Test void readsRealMp4MovieHeaderAsUtcWithoutAddingVietnamOffset() throws Exception {
        var asset = asset(mp4(), "video/mp4");
        assertThat(verification.verify(asset, "key")).isNull();
        assertThat(asset.getMetadataCapturedAt()).isEqualTo(TIME);
        assertThat(asset.getMetadataTimeZone()).isEqualTo("UTC");
    }

    @Test void rejectsMetadataMismatchUnlessStaffExplicitlyConfirmsWithReason() throws Exception {
        var asset = asset(jpeg(true), "image/jpeg"); asset.setCapturedAt(TIME.plusSeconds(60));
        assertThat(verification.verify(asset, "key")).contains("differs from declared");
        asset.setCaptureTimeSource("STAFF_CONFIRMED"); asset.setCaptureTimeConfirmedBy("staff");
        assertThat(verification.verify(asset, "key")).contains("differs from declared");
        asset.setCaptureTimeConfirmationReason("Camera clock adjustment checked against flight log");
        assertThat(verification.verify(asset, "key")).isNull();
    }

    @Test void missingMetadataRequiresStaffConfirmationAndReason() throws Exception {
        var asset = asset(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, "image/jpeg");
        assertThat(verification.verify(asset, "key")).contains("metadata missing");
        asset.setCaptureTimeSource("STAFF_CONFIRMED"); asset.setCaptureTimeConfirmedBy("staff");
        asset.setCaptureTimeConfirmationReason("Confirmed from drone log");
        assertThat(verification.verify(asset, "key")).isNull();
    }

    @Test void outOfMissionMetadataIsNeverPublished() throws Exception {
        var asset = asset(jpeg(true), "image/jpeg");
        asset.getMission().setActualStartAt(TIME.plusSeconds(7200));
        asset.getMission().setActualEndAt(TIME.plusSeconds(7800));
        assertThat(verification.verify(asset, "key")).contains("outside this Mission");
    }

    @Test void historicalAndFlightControllerAssetsKeepTheirExistingVerificationContract() throws Exception {
        var asset = asset(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, "image/jpeg");
        asset.setCaptureTimeSource(null);
        assertThat(verification.verify(asset, "key")).isNull();
    }

    MediaAsset asset(byte[] bytes, String type) throws Exception {
        var asset = new MediaAsset(); asset.setId("media"); asset.setS3Bucket("qa");
        asset.setContentType(type); asset.setFileSize((long)bytes.length); asset.setCapturedAt(TIME);
        asset.setCaptureTimeSource("METADATA");
        var mission = new Mission(); mission.setActualStartAt(TIME.minusSeconds(60)); mission.setActualEndAt(TIME.plusSeconds(300));
        asset.setMission(mission);
        var checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        asset.setChecksumSha256(checksum);
        when(storage.inspect("qa", "key")).thenReturn(new IMediaObjectStorage.StoredObjectInfo((long)bytes.length, type, Map.of("media-id", "media", "sha256", checksum)));
        when(storage.open("qa", "key")).thenAnswer(call -> new IMediaObjectStorage.StoredObjectStream(new ByteArrayInputStream(bytes), (long)bytes.length, type));
        return asset;
    }

    static byte[] jpeg(boolean offset) throws IOException {
        var tiff = ByteBuffer.allocate(83).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8);
        tiff.putShort((short)1).putShort((short)0x8769).putShort((short)4).putInt(1).putInt(26).putInt(0);
        tiff.putShort((short)(offset ? 2 : 1));
        tiff.putShort((short)0x9003).putShort((short)2).putInt(20).putInt(56);
        if (offset) tiff.putShort((short)0x9011).putShort((short)2).putInt(7).putInt(76);
        tiff.putInt(0); tiff.position(56); tiff.put("2026:11:10 08:15:30\0".getBytes(StandardCharsets.US_ASCII));
        tiff.position(76); tiff.put("+07:00\0".getBytes(StandardCharsets.US_ASCII));
        var result = new ByteArrayOutputStream(); var out = new DataOutputStream(result);
        out.writeShort(0xffd8); out.writeShort(0xffe1); out.writeShort(91);
        out.write(new byte[]{'E','x','i','f',0,0}); out.write(tiff.array()); out.writeShort(0xffd9);
        return result.toByteArray();
    }

    static byte[] mp4() {
        var b = ByteBuffer.allocate(136).order(ByteOrder.BIG_ENDIAN);
        b.putInt(20).put("ftyp".getBytes(StandardCharsets.US_ASCII)).put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isom".getBytes(StandardCharsets.US_ASCII));
        b.putInt(116).put("moov".getBytes(StandardCharsets.US_ASCII)).putInt(108).put("mvhd".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0).putInt((int)(TIME.getEpochSecond()+2082844800L)).putInt(0).putInt(1000).putInt(1000);
        return b.array();
    }
}
