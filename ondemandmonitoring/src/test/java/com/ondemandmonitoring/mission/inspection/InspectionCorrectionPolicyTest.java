package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionItem;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.order.domain.Order;
import java.util.*;
import org.junit.jupiter.api.*;

class InspectionCorrectionPolicyTest {
  InspectionCorrectionRepository corrections = mock(InspectionCorrectionRepository.class);
  InspectionCorrectionItemRepository items = mock(InspectionCorrectionItemRepository.class);
  MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
  MissionResultRepository results = mock(MissionResultRepository.class);
  IChecklistEvidenceService evidence = mock(IChecklistEvidenceService.class);
  IMissionChecklistScopeService scope = mock(IMissionChecklistScopeService.class);
  InspectionCorrectionPolicy policy =
      new InspectionCorrectionPolicy(corrections, items, executions, results, evidence, scope);
  InspectionCorrection correction;
  Mission parent, child;
  MissionChecklistExecution original, replacement;
  MissionResult result;

  @BeforeEach
  void setup() {
    var order = new Order();
    order.setId("order");
    parent = new Mission();
    parent.setId("parent");
    parent.setOrder(order);
    child = new Mission();
    child.setId("child");
    child.setOrder(order);
    child.setPurpose(MissionPurpose.REWORK);
    child.setStatus(MissionStatus.COMPLETED);
    correction = new InspectionCorrection();
    correction.setId("c");
    correction.setMission(parent);
    correction.setSupplementalMission(child);
    correction.setStatus(InspectionCorrectionStatus.RESOLVED);
    child.setInspectionCorrection(correction);
    original = new MissionChecklistExecution();
    original.setId("original");
    original.setMissionId("parent");
    original.setOrderChecklistItemId("item");
    replacement = new MissionChecklistExecution();
    replacement.setId("replacement");
    replacement.setMissionId("child");
    replacement.setMission(child);
    replacement.setOrderChecklistItemId("item");
    var item = new InspectionCorrectionItem();
    item.setCorrection(correction);
    item.setExecution(original);
    when(items.findItems("c")).thenReturn(List.of(item));
    when(items.findResolvedDelegations("parent")).thenReturn(List.of(item));
    when(executions.findOrderedByMissionId("child")).thenReturn(List.of(replacement));
    result = new MissionResult();
    result.setMission(child);
    result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
    result.setInspectorConfirmed(true);
    result.setStatus(MissionResultStatus.COMPLETED);
    when(results.findByMissionId("child")).thenReturn(Optional.of(result));
    when(evidence.readyForInspectorApproval(eq(replacement), anyList())).thenReturn(true);
  }

  @Test
  void approvedSupplementDelegatesOnlyTheExplicitOriginalExecution() {
    assertThat(policy.delegatedExecutions("parent"))
        .containsOnlyKeys("original")
        .containsEntry("original", replacement);
  }

  @Test
  void siblingMissionCannotProvideEvidenceWithoutLink() {
    child.setInspectionCorrection(null);
    assertThatThrownBy(() -> policy.delegatedExecutions("parent")).isInstanceOf(ApiException.class);
  }

  @Test
  void crossOrderSupplementIsRejected() {
    var other = new Order();
    other.setId("other");
    child.setOrder(other);
    assertThatThrownBy(() -> policy.acceptedSupplement(correction))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void unapprovedSupplementBlocksClosure() {
    result.setApprovalStatus(MissionResultApprovalStatus.DRAFT);
    assertThatThrownBy(() -> policy.acceptedSupplement(correction))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void legacyManagerApprovalDoesNotStandInForInspectorAcceptance() {
    result.setInspectorConfirmed(false);
    assertThatThrownBy(() -> policy.acceptedSupplement(correction))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void mismatchedChecklistScopeIsRejected() {
    replacement.setOrderChecklistItemId("sibling-item");
    assertThatThrownBy(() -> policy.acceptedSupplement(correction))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void nonReadyEvidenceIsNeverDelegated() {
    when(evidence.readyForInspectorApproval(eq(replacement), anyList())).thenReturn(false);
    assertThatThrownBy(() -> policy.acceptedSupplement(correction))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void cancelledOrFailedSupplementBlocks() {
    child.setStatus(MissionStatus.FAILED);
    assertThatThrownBy(() -> policy.acceptedSupplement(correction))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void customerReworkCannotBeReusedAsInternalSupplement() {
    child.setResultRevisionCaseId("customer-case");
    assertThatThrownBy(() -> policy.acceptedSupplement(correction))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void onlySelectedExactSupplementEvidenceIsPublished() {
    var valid = new MissionChecklistEvidence();
    valid.setMissionChecklistExecution(replacement);
    valid.setSelectedForCustomer(true);
    var reserve = new MissionChecklistEvidence();
    reserve.setMissionChecklistExecution(replacement);
    reserve.setSelectedForCustomer(false);
    var foreign = new MissionChecklistExecution();
    foreign.setId("other");
    var unrelated = new MissionChecklistEvidence();
    unrelated.setMissionChecklistExecution(foreign);
    unrelated.setSelectedForCustomer(true);
    when(evidence.active("child")).thenReturn(List.of(valid, reserve, unrelated));
    assertThat(policy.selectedSupplementalEvidence("parent")).containsExactly(valid);
  }
}
