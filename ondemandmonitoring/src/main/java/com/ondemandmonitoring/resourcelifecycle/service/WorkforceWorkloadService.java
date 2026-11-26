package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.StaffWorkloadResponse;
import com.ondemandmonitoring.resourcelifecycle.WorkTimeCalculator;
import com.ondemandmonitoring.resourcelifecycle.WorkforceRuleEngine;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceUsageRecordEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceUsageRecordRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes staff workload strictly separating verified actual records and assignment-estimated spans,
 * using Asia/Ho_Chi_Minh calendar boundaries and active WORKFORCE policies.
 */
@Service
@RequiredArgsConstructor
public class WorkforceWorkloadService {

    private static final ZoneId VN_ZONE = WorkTimeCalculator.DEFAULT_ZONE;

    private final UserRepository userRepository;
    private final ResourceUsageRecordRepository usageRepository;
    private final ResourceTimeLockRepository lockRepository;
    private final ResourcePolicyRepository policyRepository;
    private final LifecycleJsonHelper jsonHelper;

    private final WorkTimeCalculator workTimeCalculator = new WorkTimeCalculator();
    private final WorkforceRuleEngine workforceRuleEngine = new WorkforceRuleEngine();

    @Transactional(readOnly = true)
    public List<StaffWorkloadResponse> listAllStaffWorkloads() {
        return listAllStaffWorkloads(null);
    }

    @Transactional(readOnly = true)
    public List<StaffWorkloadResponse> listAllStaffWorkloads(LocalDate asOfDate) {
        List<User> activeStaff = userRepository
                .findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.STAFF);
        return activeStaff.stream()
                .map(staff -> evaluateStaff(staff, null, null, null, null, asOfDate))
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffWorkloadResponse getStaffWorkload(String staffId) {
        User staff = userRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Staff not found: " + staffId));
        return evaluateStaff(staff, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public StaffWorkloadResponse evaluateStaff(
            User staff,
            Instant projectedStart,
            Instant projectedEnd,
            String excludeMissionId,
            ResourcePolicyEntity overridePolicy) {
        return evaluateStaff(staff, projectedStart, projectedEnd, excludeMissionId, overridePolicy, null);
    }

    @Transactional(readOnly = true)
    public StaffWorkloadResponse evaluateStaff(
            User staff,
            Instant projectedStart,
            Instant projectedEnd,
            String excludeMissionId,
            ResourcePolicyEntity overridePolicy,
            LocalDate asOfDate) {
        ResourcePolicyEntity policy = overridePolicy != null
                ? overridePolicy
                : resolveActiveWorkforcePolicy(staff);

        List<ResourceUsageRecordEntity> verifiedRecords = usageRepository
                .findByResourceKindAndResourceIdAndStatusOrderByStartedAtAsc("STAFF", staff.getId(), "VERIFIED");

        List<WorkTimeCalculator.Span> actualSpans = verifiedRecords.stream()
                .filter(record -> record.getActualSeconds() > 0L)
                .map(record -> new WorkTimeCalculator.Span(
                        record.getStartedAt(),
                        record.getStartedAt().plusSeconds(record.getActualSeconds())))
                .toList();

        List<WorkTimeCalculator.Span> estimatedSpans = collectEstimatedLockSpans(
                staff.getId(), excludeMissionId, verifiedRecords);

        List<WorkTimeCalculator.Span> combinedSpans = new ArrayList<>();
        combinedSpans.addAll(actualSpans);
        combinedSpans.addAll(estimatedSpans);

        Instant now = Instant.now();
        LocalDate today = now.atZone(VN_ZONE).toLocalDate();
        Instant referenceInstant;
        LocalDate referenceDate;
        if (projectedStart != null) {
            referenceInstant = projectedStart;
            referenceDate = projectedStart.atZone(VN_ZONE).toLocalDate();
        } else if (asOfDate != null && !asOfDate.equals(today)) {
            referenceDate = asOfDate;
            referenceInstant = asOfDate.plusDays(1).atStartOfDay(VN_ZONE).toInstant().minusSeconds(1);
        } else {
            referenceInstant = now;
            referenceDate = today;
        }
        YearMonth referenceMonth = YearMonth.from(referenceDate);
        int referenceYear = referenceDate.getYear();

        Long restBeforeMission = workTimeCalculator.restSecondsBefore(combinedSpans, referenceInstant);

        if (projectedStart != null && projectedEnd != null && projectedStart.isBefore(projectedEnd)) {
            combinedSpans.add(new WorkTimeCalculator.Span(projectedStart, projectedEnd));
        }

        long dailyActual = workTimeCalculator.dailySeconds(actualSpans, VN_ZONE).getOrDefault(referenceDate, 0L);
        long dailyEstimated = workTimeCalculator.dailySeconds(estimatedSpans, VN_ZONE).getOrDefault(referenceDate, 0L);
        long weeklyActual = workTimeCalculator.weeklySeconds(actualSpans, referenceDate, VN_ZONE);
        long weeklyEstimated = workTimeCalculator.weeklySeconds(estimatedSpans, referenceDate, VN_ZONE);
        long monthlyActual = workTimeCalculator.monthlySeconds(actualSpans, referenceMonth, VN_ZONE);
        long monthlyEstimated = workTimeCalculator.monthlySeconds(estimatedSpans, referenceMonth, VN_ZONE);
        long yearlyActual = workTimeCalculator.yearlySeconds(actualSpans, referenceYear, VN_ZONE);
        long yearlyEstimated = workTimeCalculator.yearlySeconds(estimatedSpans, referenceYear, VN_ZONE);

        long projectedDaily = workTimeCalculator.dailySeconds(combinedSpans, VN_ZONE).getOrDefault(referenceDate, 0L);
        long projectedWeekly = workTimeCalculator.weeklySeconds(combinedSpans, referenceDate, VN_ZONE);
        long projectedMonthly = workTimeCalculator.monthlySeconds(combinedSpans, referenceMonth, VN_ZONE);

        WorkforceRuleEngine.PolicyLimits limits = parseLimits(policy);
        WorkforceRuleEngine.Evaluation evaluation = workforceRuleEngine.evaluate(
                projectedDaily,
                projectedWeekly,
                projectedMonthly,
                restBeforeMission,
                Set.of(),
                limits);

        return new StaffWorkloadResponse(
                staff.getId(),
                staff.getFullName(),
                staff.getEmail(),
                dailyActual,
                dailyEstimated,
                weeklyActual,
                weeklyEstimated,
                monthlyActual,
                monthlyEstimated,
                yearlyActual,
                yearlyEstimated,
                restBeforeMission,
                evaluation.status(),
                evaluation.maxUtilizationPercent(),
                evaluation.blocked(),
                policy == null ? null : policy.getId(),
                policy == null ? null : policy.getName(),
                evaluation.blockingReasons(),
                evaluation.warningReasons());
    }

    private ResourcePolicyEntity resolveActiveWorkforcePolicy(User staff) {
        Optional<ResourcePolicyEntity> direct = policyRepository
                .findFirstByFamilyAndTargetIdAndActiveTrue("WORKFORCE", staff.getId());
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<ResourcePolicyEntity> roleScoped = policyRepository
                .findFirstByFamilyAndTargetIdAndActiveTrue("WORKFORCE", "STAFF");
        if (roleScoped.isPresent()) {
            return roleScoped.get();
        }
        return policyRepository.findFirstByFamilyAndTargetIdAndActiveTrue("WORKFORCE", "ALL").orElse(null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository staffAssignmentRepository;

    /**
     * Work still in progress: missions the staff member is on that have actually started but are not
     * completed yet, counted from the real start until now. Planned resource locks are not work time;
     * finished missions are already in the verified records (recorded at completion).
     */
    private List<WorkTimeCalculator.Span> collectEstimatedLockSpans(
            String staffId,
            String excludeMissionId,
            List<ResourceUsageRecordEntity> verifiedRecords) {
        if (staffAssignmentRepository == null) return List.of();
        Instant now = Instant.now();
        Set<String> seenMissions = new HashSet<>();
        return staffAssignmentRepository.findRunningMissionAssignmentsForStaff(staffId).stream()
                .map(assignment -> assignment.getMission())
                .filter(mission -> excludeMissionId == null || !excludeMissionId.equals(mission.getId()))
                .filter(mission -> seenMissions.add(mission.getId()))
                .filter(mission -> verifiedRecords.stream().noneMatch(r -> mission.getId().equals(r.getMissionId())))
                .filter(mission -> mission.getActualStartAt().isBefore(now))
                .map(mission -> new WorkTimeCalculator.Span(mission.getActualStartAt(), now))
                .toList();
    }

    private WorkforceRuleEngine.PolicyLimits parseLimits(ResourcePolicyEntity policy) {
        if (policy == null) {
            return null;
        }
        Map<String, Object> rules = jsonHelper.toMap(policy.getRulesJson());
        Long maxDailySeconds = readDurationSeconds(rules, "maxDailySeconds", "maxDailyHours");
        Long maxWeeklySeconds = readDurationSeconds(rules, "maxWeeklySeconds", "maxWeeklyHours");
        Long maxMonthlySeconds = readDurationSeconds(rules, "maxMonthlySeconds", "maxMonthlyHours");
        Long minRestSeconds = readDurationSeconds(rules, "minRestSeconds", "minRestHours");
        int warningPercent = Optional.ofNullable(jsonHelper.readInt(rules, "warningPercent")).orElse(80);

        Set<String> requiredCertificates = new HashSet<>();
        Object rawCerts = rules.get("requiredCertificates");
        if (rawCerts instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !item.toString().isBlank()) {
                    requiredCertificates.add(item.toString().trim());
                }
            }
        }

        return new WorkforceRuleEngine.PolicyLimits(
                maxDailySeconds,
                maxWeeklySeconds,
                maxMonthlySeconds,
                minRestSeconds,
                warningPercent,
                Set.copyOf(requiredCertificates));
    }

    private Long readDurationSeconds(Map<String, Object> rules, String secondsKey, String hoursKey) {
        Long seconds = jsonHelper.readLong(rules, secondsKey);
        if (seconds != null) {
            return seconds;
        }
        Double hours = jsonHelper.readDouble(rules, hoursKey);
        return hours == null ? null : Math.round(hours * 3600.0);
    }
}
