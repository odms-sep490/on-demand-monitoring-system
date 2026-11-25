package com.ondemandmonitoring.inspectionreport;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader;
import com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository;
import java.util.*;
import java.io.*;
import java.time.Instant;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class ReportEvidenceService {
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    private final MediaAssetRepository media;
    private final IMediaObjectStorage storage;
    private final DeliveryImageQualityReader quality;
    private final MissionChecklistEvidenceRepository links;
    public record Finding(String id, String text, String assessment, List<String> mediaIds) {}
    public record Evidence(String mediaId, String fileName, Instant capturedAt, int width, int height,
            String qualityStatus, double rankingScore, boolean isEligible, String selectionReason,
            List<String> findingIds, boolean priority, String checksum) {}
    public record Collection(List<Evidence> images, List<Finding> findings, List<String> warnings) {}

    @Transactional(readOnly=true)
    public Collection collect(String missionId) {
        var page = media.findMissionCandidates(missionId, PageRequest.of(0, 200));
        if (page.getTotalElements() > 200) throw fail("Mission has over 200 media files; narrow the evidence collection before generating");
        var assets = page.getContent();
        var signals = quality.read(missionId, assets);
        var active = links.findActiveByMissionId(missionId);
        Map<String,Finding> findings=new LinkedHashMap<>();
        for(var finding:reviewedFindings(missionId))findings.put(finding.id(),finding);
        List<Evidence> images = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Set<String> checksums = new HashSet<>(); List<Long> hashes = new ArrayList<>();
        for (var asset : assets) {
            if (!Set.of("IMAGE", "VIDEO").contains(asset.getType()) || asset.getMediaStatus() != MediaStatus.AVAILABLE
                    || asset.getStorageStatus() != MediaStorageStatus.AVAILABLE) continue;
            String id = asset.getId();
            if ("VIDEO".equals(asset.getType())) {
                if (!Set.of("video/mp4", "video/webm").contains(Objects.toString(asset.getContentType(), ""))) continue;
                var findingIds = findings.values().stream().filter(f -> f.mediaIds().contains(id)).map(Finding::id).toList();
                images.add(new Evidence(id, asset.getOriginalFileName(), asset.getCapturedAt(), 0, 0,
                        "VIDEO_NOT_ANALYZED", findingIds.isEmpty() ? -1 : 40, true,
                        "Video đã duyệt; chưa phân tích nội dung video tự động", findingIds, false, asset.getChecksumSha256()));
                continue;
            }
            var signal = signals.get(id);
            String decision = signal == null ? "NOT_ANALYZED" :
                    signal.humanDecision() != null ? signal.humanDecision() : signal.technicalDecision();
            if (decision == null) decision = "NOT_ANALYZED";
            try {
                var image = readImage(asset);
                boolean duplicate = asset.getChecksumSha256() != null && !checksums.add(asset.getChecksumSha256());
                boolean similar = false;
                if (signal != null && signal.hash() != null) {
                    if (hashes.stream().anyMatch(h -> Long.bitCount(h ^ signal.hash()) <= 5)) similar = true;
                    hashes.add(signal.hash());
                }
                var findingIds = findings.values().stream().filter(f -> f.mediaIds().contains(id)).map(Finding::id).toList();
                boolean priority = active.stream().anyMatch(l -> l.getMediaAsset().getId().equals(id) && l.isSelectedForCustomer());
                boolean eligible = (!duplicate || !findingIds.isEmpty() || priority) && image.getWidth() >= 320 && image.getHeight() >= 240;
                double score = ("PASS".equals(decision) ? 30 : 0) + Math.min(20, image.getWidth() * (double)image.getHeight() / 1_000_000 * 5)
                        + (findingIds.isEmpty() ? 0 : 40) + (priority ? 10 : 0) - (similar ? 10 : 0) - ("FAIL".equals(decision) ? 30 : 0);
                String reason = !eligible ? (duplicate ? "Exact duplicate excluded from automatic selection" : "Image resolution below 320 × 240") : duplicate ? "Exact duplicate retained for reviewed finding or staff priority" :
                        "Resolution + reviewed finding linkage + quality signal + staff priority; no visual scene classification";
                images.add(new Evidence(id, asset.getOriginalFileName(), asset.getCapturedAt(), image.getWidth(), image.getHeight(),
                        decision, score, eligible, reason, findingIds, priority, asset.getChecksumSha256()));
                if ("FAIL".equals(decision)) warnings.add("Low-quality source " + id + ": retain only after explicit human evidence review");
            } catch (Exception ex) { warnings.add("Cannot read approved image " + id + ": " + ex.getClass().getSimpleName()); }
        }
        images.sort(Comparator.comparingDouble(Evidence::rankingScore).reversed().thenComparing(Evidence::mediaId));
        Set<String> usable = new HashSet<>(images.stream().filter(Evidence::isEligible).map(Evidence::mediaId).toList());
        var supported = findings.values().stream().filter(f -> f.mediaIds().stream().anyMatch(usable::contains)).toList();
        return new Collection(images, supported, warnings);
    }

    @Transactional(readOnly=true)
    public List<Finding> reviewedFindings(String missionId) {
        var active=links.findActiveByMissionId(missionId);
        Map<String,Finding> findings=new LinkedHashMap<>();
        for (var link : active) {
            var execution = link.getMissionChecklistExecution();
            if (execution.getAssessedAt() == null || execution.getAssessedBy() == null || execution.getAssessmentStatus()==null
                    || execution.getAssessmentStatus().name().equals("NOT_ASSESSED")) continue;
            String note = execution.getAssessmentNote();
            if (note == null || note.isBlank()) note = execution.getObservation();
            if (note == null || note.isBlank()) continue;
            var ids = active.stream().filter(l -> l.getMissionChecklistExecution().getId().equals(execution.getId()))
                    .map(l -> l.getMediaAsset().getId()).distinct().sorted().toList();
            findings.put(execution.getId(), new Finding(execution.getId(), note,
                    execution.getAssessmentStatus().name(), ids));
        }
        return List.copyOf(findings.values());
    }

    public MediaAsset requireMedia(String missionId, String id) {
        var asset = media.findById(id).orElseThrow(() -> fail("Image no longer exists"));
        if (!missionId.equals(asset.getMissionId()) || asset.getMediaStatus() != MediaStatus.AVAILABLE
                || asset.getStorageStatus() != MediaStorageStatus.AVAILABLE || !Set.of("IMAGE", "VIDEO").contains(asset.getType()))
            throw fail("Image is not approved evidence for this Mission");
        return asset;
    }
    public BufferedImage readImage(MediaAsset asset) throws IOException {
        if (!Set.of("image/jpeg", "image/png").contains(asset.getContentType()) || asset.getFileSize() == null
                || asset.getFileSize() <= 0 || asset.getFileSize() > MAX_BYTES) throw new IOException("Unsupported or oversized image");
        var object = storage.open(asset.getS3Bucket(), asset.getS3Key());
        try (var input = object.inputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Image exceeds size limit");
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException("Invalid image bytes");
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("jpeg", "jpg", "png").contains(format)) throw new IOException("Unsupported actual image format");
                    if ((long)reader.getWidth(0) * reader.getHeight(0) > 30_000_000) throw new IOException("Image pixel limit exceeded");
                    return reader.read(0);
                } finally { reader.dispose(); }
            }
        }
    }
    static ResponseStatusException fail(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
}
