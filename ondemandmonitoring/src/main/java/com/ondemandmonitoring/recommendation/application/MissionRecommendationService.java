package com.ondemandmonitoring.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.recommendation.application.port.AiEstimatePort;
import com.ondemandmonitoring.recommendation.application.port.MissionRecommendationDataPort;
import com.ondemandmonitoring.recommendation.domain.MissionRecommendation;
import com.ondemandmonitoring.recommendation.domain.RecommendationRisk;
import com.ondemandmonitoring.recommendation.dto.MissionRecommendationsResponse;
import com.ondemandmonitoring.recommendation.persistence.MissionRecommendationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Generates ranked, read-only availability recommendations for one Mission. */
@Service
@RequiredArgsConstructor
public class MissionRecommendationService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_SEARCH_DAYS = 90;
    private static final int MAX_RECOMMENDATIONS = 5;

    private final MissionRecommendationDataPort missionData;
    private final AiEstimatePort aiEstimates;
    private final MissionRecommendationRepository recommendationRepository;
    private final ObjectMapper objectMapper;

    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionRecommendationsResponse generate(String missionId) {
        var scope = missionData.getScope(missionId);
        if (!"PLANNING".equals(scope.status()) || scope.cancelled()) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Recommendations require a PLANNING mission");
        }
        if (scope.checklistCapabilities().isEmpty()) throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
        if (scope.fixedStartTime() == null || scope.fixedEndTime() == null
                || !scope.fixedStartTime().isBefore(scope.fixedEndTime())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Order must contain a valid fixed time window");
        }
        SearchRange range = searchRange(scope.preferredDateFrom(), scope.preferredDateTo());
        AiEstimatePort.Estimate estimate = aiEstimates.estimate(missionId);

        Set<String> requiredCapabilities = scope.checklistCapabilities().stream()
                .flatMap(item -> item.requiredCapabilities().stream())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        List<DayCandidate> candidates = new ArrayList<>();
        for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
            Instant start = date.atTime(scope.fixedStartTime()).atZone(ZONE).toInstant();
            Instant end = date.atTime(scope.fixedEndTime()).atZone(ZONE).toInstant();
            List<MissionRecommendationDataPort.DeviceCandidate> available =
                    missionData.findAvailableDevices(missionId, start, end);
            if (!coversRequiredCapabilities(available, requiredCapabilities)) continue;
            int staffCount = missionData.countAvailableStaff(missionId, start, end);
            if (staffCount == 0) continue;

            List<MissionRecommendationDataPort.DeviceCandidate> relevantDevices = sortCandidates(
                    available, requiredCapabilities);
            if (relevantDevices.isEmpty()) continue;
            int target = Math.min(estimate.recommendedDevices(), relevantDevices.size());
            int deviceShortage = Math.max(0, estimate.recommendedDevices() - relevantDevices.size());
            int staffShortage = Math.max(0, target - staffCount);
            BigDecimal deviceRatio = ratio(Math.min(relevantDevices.size(), estimate.recommendedDevices()), estimate.recommendedDevices());
            BigDecimal staffRatio = target == 0 ? BigDecimal.ONE
                    : ratio(Math.min(staffCount, target), target);
            BigDecimal effective = deviceRatio.min(staffRatio).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
            RecommendationRisk risk = classify(relevantDevices.size(), estimate.recommendedDevices(), staffCount, target);
            BigDecimal rarityScore = rarityScore(relevantDevices, requiredCapabilities, target);
            List<String> warnings = new ArrayList<>();
            if (deviceShortage > 0) warnings.add("Thiếu " + deviceShortage + " thiết bị so với số lượng AI đề xuất.");
            if (staffShortage > 0) warnings.add("Thiếu " + staffShortage + " nhân sự cho số thiết bị khả dụng mục tiêu.");
            candidates.add(new DayCandidate(date, start, end, available, relevantDevices, staffCount,
                    target, deviceShortage, staffShortage, deviceRatio, staffRatio, effective, risk, rarityScore, warnings));
        }

        candidates.sort(Comparator.comparing(DayCandidate::risk)
                .thenComparingInt(DayCandidate::staffShortage)
                .thenComparing(DayCandidate::deviceRatio, Comparator.reverseOrder())
                .thenComparing(DayCandidate::rarityScore, Comparator.reverseOrder())
                .thenComparing(DayCandidate::date));
        List<DayCandidate> top = candidates.stream().limit(MAX_RECOMMENDATIONS).toList();
        List<MissionRecommendation> stored = recommendationRepository.saveAll(top.stream()
                .map(candidate -> toEntity(scope, range, estimate, candidate, requiredCapabilities)).toList());
        List<MissionRecommendationsResponse.RecommendationOption> options = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) options.add(toOption(stored.get(i), top.get(i), requiredCapabilities));

        return new MissionRecommendationsResponse(
                missionId,
                new MissionRecommendationsResponse.SearchWindow(range.from(), range.to()),
                new MissionRecommendationsResponse.FixedTimeWindow(scope.fixedStartTime(), scope.fixedEndTime(), ZONE.getId()),
                new MissionRecommendationsResponse.AiEstimation(estimate.minimumSuggestedDevices(),
                        estimate.recommendedDevices(), estimate.reason(), estimate.assumptions(), estimate.warnings(),
                        estimate.model(), estimate.generatedAt()),
                options);
    }

    private SearchRange searchRange(LocalDate preferredFrom, LocalDate preferredTo) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = preferredFrom == null || preferredFrom.isBefore(today) ? today : preferredFrom;
        LocalDate to = preferredTo == null ? from.plusDays(29) : preferredTo;
        if (to.isAfter(from.plusDays(MAX_SEARCH_DAYS - 1L))) to = from.plusDays(MAX_SEARCH_DAYS - 1L);
        if (to.isBefore(from)) throw new ApiException(ErrorCode.INVALID_REQUEST, "Order preferred date range has expired");
        return new SearchRange(from, to);
    }

    private boolean coversRequiredCapabilities(
            List<MissionRecommendationDataPort.DeviceCandidate> devices, Set<String> required) {
        Set<String> available = devices.stream().flatMap(device -> device.capabilityCodes().stream()).collect(Collectors.toSet());
        return available.containsAll(required);
    }

    private List<MissionRecommendationDataPort.DeviceCandidate> sortCandidates(
            List<MissionRecommendationDataPort.DeviceCandidate> devices, Set<String> required) {
        Map<String, Long> supply = new HashMap<>();
        devices.stream().flatMap(device -> device.capabilityCodes().stream()).filter(required::contains)
                .forEach(code -> supply.merge(code, 1L, Long::sum));
        return devices.stream()
                .filter(device -> required.isEmpty() || device.capabilityCodes().stream().anyMatch(required::contains))
                .sorted(Comparator
                        .comparing((MissionRecommendationDataPort.DeviceCandidate device) -> deviceRarityScore(device, required, supply),
                                Comparator.reverseOrder())
                        .thenComparing(device -> device.code() == null ? "" : device.code())
                        .thenComparing(MissionRecommendationDataPort.DeviceCandidate::id))
                .toList();
    }

    private BigDecimal deviceRarityScore(
            MissionRecommendationDataPort.DeviceCandidate device, Set<String> required, Map<String, Long> supply) {
        return device.capabilityCodes().stream().filter(required::contains).distinct()
                .map(code -> BigDecimal.ONE.divide(BigDecimal.valueOf(supply.getOrDefault(code, 1L)), 6, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal rarityScore(
            List<MissionRecommendationDataPort.DeviceCandidate> candidates, Set<String> required, int target) {
        Map<String, Long> supply = candidates.stream().flatMap(device -> device.capabilityCodes().stream())
                .filter(required::contains).collect(Collectors.groupingBy(code -> code, Collectors.counting()));
        Set<String> scarceCapabilities = candidates.stream().limit(target)
                .flatMap(device -> device.capabilityCodes().stream()).filter(required::contains)
                .collect(Collectors.toSet());
        return scarceCapabilities.stream()
                .map(code -> BigDecimal.ONE.divide(BigDecimal.valueOf(supply.getOrDefault(code, 1L)), 6, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private RecommendationRisk classify(int devices, int recommended, int staff, int target) {
        if (devices >= recommended && staff >= recommended) return RecommendationRisk.LOW;
        boolean meetsTwoThirds = 3L * devices >= 2L * recommended && (target == 0 || 3L * staff >= 2L * target);
        return meetsTwoThirds ? RecommendationRisk.MEDIUM : RecommendationRisk.HIGH;
    }

    private BigDecimal ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(Math.max(1, denominator)), 8, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
    }

    private MissionRecommendation toEntity(
            MissionRecommendationDataPort.MissionScope scope, SearchRange range, AiEstimatePort.Estimate estimate,
            DayCandidate candidate, Set<String> required) {
        MissionRecommendation row = new MissionRecommendation();
        row.setMissionId(scope.missionId());
        row.setExecutionPlanRevision(scope.executionPlanRevision());
        row.setScheduleRevision(scope.scheduleRevision());
        row.setSearchFrom(range.from());
        row.setSearchTo(range.to());
        row.setRecommendationDate(candidate.date());
        row.setScheduledStartAt(candidate.start());
        row.setScheduledEndAt(candidate.end());
        row.setAvailableDeviceCount(candidate.availableDevices().size());
        row.setCapabilityCompatibleDeviceCount(candidate.candidateDevices().size());
        row.setAvailableStaffCount(candidate.staffCount());
        row.setRecommendedDeviceCount(estimate.recommendedDevices());
        row.setDeviceShortageCount(candidate.deviceShortage());
        row.setStaffShortageCount(candidate.staffShortage());
        row.setDeviceAvailabilityRatio(candidate.deviceRatio().setScale(4, RoundingMode.HALF_UP));
        row.setStaffAvailabilityRatio(candidate.staffRatio().setScale(4, RoundingMode.HALF_UP));
        row.setEffectiveAvailabilityRatio(candidate.effectiveRatio());
        row.setRiskLevel(candidate.risk());
        row.setRequiredCapabilitiesJson(writeJson(required));
        row.setDeviceCandidatesJson(writeJson(candidate.candidateDevices()));
        row.setWarningsJson(writeJson(candidate.warnings()));
        row.setAiModel(estimate.model() == null ? "unknown" : estimate.model());
        row.setAiMinimumDevices(estimate.minimumSuggestedDevices());
        row.setAiRecommendedDevices(estimate.recommendedDevices());
        row.setAiReason(estimate.reason());
        row.setAiAssumptionsJson(writeJson(estimate.assumptions()));
        row.setAiWarningsJson(writeJson(estimate.warnings()));
        return row;
    }

    private MissionRecommendationsResponse.RecommendationOption toOption(
            MissionRecommendation row, DayCandidate candidate, Set<String> required) {
        List<MissionRecommendationsResponse.DeviceCandidate> devices = candidate.candidateDevices().stream()
                .map(device -> new MissionRecommendationsResponse.DeviceCandidate(
                        device.id(), device.code(), device.name(), device.capabilityCodes())).toList();
        return new MissionRecommendationsResponse.RecommendationOption(
                row.getId(), row.getRecommendationDate(), row.getAvailableDeviceCount(),
                row.getCapabilityCompatibleDeviceCount(), row.getAvailableStaffCount(), row.getRecommendedDeviceCount(),
                row.getDeviceShortageCount(), row.getStaffShortageCount(), row.getDeviceAvailabilityRatio(),
                row.getStaffAvailabilityRatio(), row.getEffectiveAvailabilityRatio(), row.getRiskLevel(),
                List.copyOf(required), devices, candidate.warnings());
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to store recommendation snapshot", exception); }
    }

    private record SearchRange(LocalDate from, LocalDate to) {}
    private record DayCandidate(
            LocalDate date, Instant start, Instant end,
            List<MissionRecommendationDataPort.DeviceCandidate> availableDevices,
            List<MissionRecommendationDataPort.DeviceCandidate> candidateDevices,
            int staffCount, int targetDeviceCount, int deviceShortage, int staffShortage,
            BigDecimal deviceRatio, BigDecimal staffRatio, BigDecimal effectiveRatio,
            RecommendationRisk risk, BigDecimal rarityScore, List<String> warnings) {}
}
