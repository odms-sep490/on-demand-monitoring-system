package com.ondemandmonitoring.mission.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.order.service.*;
import com.ondemandmonitoring.order.service.impl.OrderExecutionCompletionPolicy;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReworkRecoveryTest {
  Order order;
  Mission original, failed, replacement;
  List<OrderChecklistItem> items;
  List<MissionChecklistAssignment> links;
  MissionChecklistAssignmentRepository assignments =
      mock(MissionChecklistAssignmentRepository.class);
  OrderChecklistItemRepository itemRepo = mock(OrderChecklistItemRepository.class);
  OrderRepository orders = mock(OrderRepository.class);
  MissionResultRepository results = mock(MissionResultRepository.class);
  IMissionChecklistExecutionService evidence = mock(IMissionChecklistExecutionService.class);
  OrderExecutionCompletionPolicy policy =
      new OrderExecutionCompletionPolicy(
          orders,
          mock(MissionRepository.class),
          results,
          evidence,
          itemRepo,
          assignments,
          new ExecutionPlanCoverageService());

  @BeforeEach
  void setup() {
    order = new Order();
    order.setId("order");
    order.setOrderStatus(OrderStatus.IN_PROGRESS);
    order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
    original = mission("original", MissionStatus.COMPLETED, MissionPurpose.ORIGINAL);
    failed = mission("failed", MissionStatus.FAILED, MissionPurpose.REWORK);
    replacement = mission("replacement", MissionStatus.COMPLETED, MissionPurpose.REWORK);
    failed.setResultRevisionCaseId("case");
    replacement.setResultRevisionCaseId("case");
    failed.setCreatedAt(java.time.Instant.parse("2026-11-01T00:00:00Z"));
    replacement.setCreatedAt(java.time.Instant.parse("2026-11-02T00:00:00Z"));
    items = new ArrayList<>();
    for (String id : List.of("A", "B")) {
      var item = new OrderChecklistItem();
      item.setId(id);
      item.setOrder(order);
      items.add(item);
    }
    links = new ArrayList<>();
    for (var m : List.of(original, failed, replacement))
      for (var item : items) links.add(link(m, item));
    when(itemRepo.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("order")))
        .thenReturn(items);
    when(assignments.findActiveByOrderId("order"))
        .thenAnswer(c -> links.stream().filter(a -> a.getRemovedAt() == null).toList());
    when(results.findByMissionIds(any()))
        .thenAnswer(
            c -> {
              Collection<String> ids = c.getArgument(0);
              return List.of(original, failed, replacement).stream()
                  .filter(m -> ids.contains(m.getId()))
                  .map(
                      m -> {
                        var r = new MissionResult();
                        r.setMission(m);
                        r.setStatus(MissionResultStatus.COMPLETED);
                        r.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
                        r.setInspectorConfirmed(true);
                        return r;
                      })
                  .toList();
            });
    when(evidence.isReadyForInspectorApproval(any())).thenReturn(true);
  }

  Mission mission(String id, MissionStatus status, MissionPurpose purpose) {
    var m = new Mission();
    m.setId(id);
    m.setOrder(order);
    m.setStatus(status);
    m.setPurpose(purpose);
    return m;
  }

  MissionChecklistAssignment link(Mission m, OrderChecklistItem item) {
    var a = new MissionChecklistAssignment();
    a.setId(m.getId() + item.getId());
    a.setMission(m);
    a.setMissionId(m.getId());
    a.setOrderId(order.getId());
    a.setOrderChecklistItem(item);
    a.setOrderChecklistItemId(item.getId());
    return a;
  }

  boolean complete() {
    return policy.completeIfReady(
        new IOrderExecutionCompletionPolicy.LockedExecution(
            order, List.of(original, failed, replacement)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void completedReplacementFromSameOriginUnblocksOrderButKeepsFailedHistory(boolean internal) {
    if (internal) {
      var c = new InspectionCorrection();
      c.setId("correction");
      failed.setInspectionCorrection(c);
      replacement.setInspectionCorrection(c);
      failed.setResultRevisionCaseId(null);
      replacement.setResultRevisionCaseId(null);
    }
    assertThat(complete()).isTrue();
    assertThat(failed.getStatus()).isEqualTo(MissionStatus.FAILED);
    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
  }

  @Test
  void olderCompletedWorkDoesNotHideANewerFailedAttempt() {
    replacement.setCreatedAt(failed.getCreatedAt().minusSeconds(1));
    assertThat(complete()).isFalse();
  }

  @Test
  void partialReplacementCannotHideFailedScope() {
    links.removeIf(
        a -> a.getMissionId().equals("replacement") && a.getOrderChecklistItemId().equals("B"));
    assertThat(complete()).isFalse();
  }

  @Test
  void differentCaseCannotReplaceFailedAttempt() {
    replacement.setResultRevisionCaseId("other");
    assertThat(complete()).isFalse();
  }

  @Test
  void pendingReplacementStillBlocksCompletion() {
    replacement.setStatus(MissionStatus.IN_PROGRESS);
    assertThat(complete()).isFalse();
  }

  @Test
  void unapprovedReplacementStillBlocksCompletion() {
    doReturn(List.of()).when(results).findByMissionIds(any());
    assertThat(complete()).isFalse();
  }

  @Test
  void failedOriginalStillBlocksEvenWithSuccessfulRework() {
    original.setStatus(MissionStatus.FAILED);
    assertThat(complete()).isFalse();
  }

  @Test
  void invalidReplacementEvidenceStillBlocksCompletion() {
    when(evidence.isReadyForInspectorApproval(replacement)).thenReturn(false);
    assertThat(complete()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "IN_PROGRESS"})
  void cancelledSupplementDoesNotDowngradeOriginalChecklist(String status) {
    original.setStatus(MissionStatus.valueOf(status));
    failed.setStatus(MissionStatus.CANCELLED);
    when(assignments.findByMissionId("failed"))
        .thenReturn(links.stream().filter(a -> a.getMissionId().equals("failed")).toList());
    links.removeIf(a -> a.getMissionId().equals("replacement"));
    new ChecklistAssignmentStatusSync(assignments, itemRepo).syncForMission(failed);
    assertThat(items)
        .allSatisfy(
            i ->
                assertThat(i.getAssignmentStatus())
                    .isEqualTo(ChecklistAssignmentStatus.valueOf(status)));
  }

  @Test
  void failedSupplementDoesNotDowngradeCompletedOriginal() {
    when(assignments.findByMissionId("failed"))
        .thenReturn(links.stream().filter(a -> a.getMissionId().equals("failed")).toList());
    new ChecklistAssignmentStatusSync(assignments, itemRepo).syncForMission(failed);
    assertThat(items)
        .allSatisfy(
            i ->
                assertThat(i.getAssignmentStatus()).isEqualTo(ChecklistAssignmentStatus.COMPLETED));
  }

  @Test
  void cancellingLastOwnerReleasesTheChecklist() {
    failed.setStatus(MissionStatus.CANCELLED);
    when(assignments.findByMissionId("failed"))
        .thenReturn(links.stream().filter(a -> a.getMissionId().equals("failed")).toList());
    links.removeIf(a -> !a.getMissionId().equals("failed"));
    new ChecklistAssignmentStatusSync(assignments, itemRepo).syncForMission(failed);
    assertThat(items)
        .allSatisfy(
            i ->
                assertThat(i.getAssignmentStatus())
                    .isEqualTo(ChecklistAssignmentStatus.UNASSIGNED));
  }
}
