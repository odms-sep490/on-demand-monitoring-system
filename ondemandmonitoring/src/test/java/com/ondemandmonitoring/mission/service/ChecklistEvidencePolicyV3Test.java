package com.ondemandmonitoring.mission.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Evidence policy V3: the requested images AND the requested videos must each be met; pricing never decides acceptance. */
class ChecklistEvidencePolicyV3Test {
    IChecklistEvidenceService service = EvidenceTestFixture.emptyService();
    OrderChecklistItem item;
    MissionChecklistExecution execution;
    int sequence;

    @BeforeEach void setup() {
        item = new OrderChecklistItem();
        item.setId("i"); item.setContent("Check roof"); item.setEvidencePolicyVersion(3);
        item.setMinimumImageCount(2); item.setMinimumVideoCount(1); item.setMinimumEvidenceCount(3);
        item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_AND_VIDEO);
        execution = new MissionChecklistExecution();
        execution.setId("e"); execution.setMissionId("m"); execution.setOrderId("o"); execution.setOrderChecklistItem(item);
        execution.setExecutionStatus(ChecklistExecutionStatus.COMPLETED); execution.setVersion(0L);
    }

    MissionChecklistEvidence link(String type, MediaStatus status) {
        var asset = new MediaAsset();
        asset.setId("a" + (++sequence)); asset.setMissionId("m"); asset.setType(type); asset.setSourceType("DRONE_CAMERA");
        asset.setMediaStatus(status); asset.setValidatedAt(Instant.now());
        var link = new MissionChecklistEvidence();
        link.setMissionId("m"); link.setMissionChecklistExecution(execution); link.setMediaAsset(asset); link.setAttachedAt(Instant.now());
        return link;
    }
    List<MissionChecklistEvidence> links(String... types) {
        var list = new ArrayList<MissionChecklistEvidence>();
        for (String type : types) list.add(link(type, MediaStatus.AVAILABLE));
        return list;
    }

    @Test void bothThresholdsMustBeMet() {
        assertTrue(service.ready(execution, links("IMAGE", "IMAGE", "VIDEO"), true));
        assertFalse(service.ready(execution, links("IMAGE", "IMAGE", "IMAGE"), true), "enough files, but no video");
        assertFalse(service.ready(execution, links("VIDEO", "VIDEO", "VIDEO"), true), "enough files, but too few images");
        assertFalse(service.ready(execution, links("IMAGE", "VIDEO"), true));
    }

    @Test void theSameFileUploadedTwiceCountsOnce() {
        var list = links("IMAGE", "IMAGE", "VIDEO");
        list.get(0).getMediaAsset().setChecksumSha256("A".repeat(64));
        list.get(1).getMediaAsset().setChecksumSha256("a".repeat(64)); // same content, different media row
        assertFalse(service.ready(execution, list, true), "two identical images are one image");
        list.get(1).getMediaAsset().setChecksumSha256("b".repeat(64));
        assertTrue(service.ready(execution, list, true));
    }

    @Test void extraFilesOfEitherTypeDoNotHurtOnceThresholdsAreMet() {
        assertTrue(service.ready(execution, links("IMAGE", "IMAGE", "IMAGE", "VIDEO", "VIDEO"), true));
    }

    @Test void enoughFilesIsNotEnoughWhenStatusAssessmentOrMediaValidityIsMissing() {
        var pending = links("IMAGE", "IMAGE");
        pending.add(link("VIDEO", MediaStatus.PENDING_MANAGER_APPROVAL));
        assertTrue(service.ready(execution, pending, false), "pending approval is acceptable before final approval");
        assertFalse(service.ready(execution, pending, true), "but never at final approval");

        var rejected = links("IMAGE", "IMAGE");
        rejected.add(link("VIDEO", MediaStatus.REJECTED));
        assertFalse(service.ready(execution, rejected, false));

        var unvalidated = links("IMAGE", "IMAGE", "VIDEO");
        unvalidated.getLast().getMediaAsset().setValidatedAt(null);
        assertFalse(service.ready(execution, unvalidated, false));

        execution.setExecutionStatus(ChecklistExecutionStatus.IN_PROGRESS);
        assertFalse(service.ready(execution, links("IMAGE", "IMAGE", "VIDEO"), false), "execution not completed");
    }

    @Test void detachedAndForeignExecutionEvidenceNeverCounts() {
        var list = links("IMAGE", "IMAGE", "VIDEO");
        list.getLast().setDetachedAt(Instant.now());
        assertFalse(service.ready(execution, list, false));
        var other = links("IMAGE", "IMAGE", "VIDEO");
        var foreign = new MissionChecklistExecution(); foreign.setId("other");
        other.forEach(l -> l.setMissionChecklistExecution(foreign));
        assertFalse(service.ready(execution, other, false));
    }

    @Test void inconsistentSnapshotsAreNeverReady() {
        item.setMinimumEvidenceCount(5);
        assertFalse(service.ready(execution, links("IMAGE", "IMAGE", "VIDEO"), true));
        item.setMinimumEvidenceCount(0); item.setMinimumImageCount(0); item.setMinimumVideoCount(0);
        assertFalse(service.ready(execution, links(), true));
    }

    @Test void imageOnlyAndVideoOnlyRequirementsStillWork() {
        item.setMinimumImageCount(3); item.setMinimumVideoCount(0); item.setMinimumEvidenceCount(3); item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE);
        assertTrue(service.ready(execution, links("IMAGE", "IMAGE", "IMAGE"), true));
        assertFalse(service.ready(execution, links("IMAGE", "IMAGE"), true));
        item.setMinimumImageCount(0); item.setMinimumVideoCount(2); item.setMinimumEvidenceCount(2); item.setRequiredEvidenceType(RequiredEvidenceType.VIDEO);
        assertTrue(service.ready(execution, links("VIDEO", "VIDEO"), true));
    }

    @Test void readinessMessageNamesBothThresholds() {
        String message = service.readinessFailure(execution, links("IMAGE"), false);
        assertNotNull(message);
        assertTrue(message.contains("2 image(s) and 1 video(s)"), message);
        assertTrue(message.contains("INSUFFICIENT_EVIDENCE"), message);
    }

    @Test void enrichmentExposesPerTypeCounts() {
        var response = service.enrich(new ChecklistExecutionResponse(), execution, links("IMAGE", "VIDEO", "VIDEO"));
        assertEquals(2, response.getMinimumImageCount());
        assertEquals(1, response.getMinimumVideoCount());
        assertEquals(1, response.getEligibleImageCount());
        assertEquals(2, response.getEligibleVideoCount());
        assertFalse(response.isEvidenceRequirementSatisfied());
        assertFalse(response.isEvidenceReady());
        var met = service.enrich(new ChecklistExecutionResponse(), execution, links("IMAGE", "IMAGE", "VIDEO"));
        assertTrue(met.isEvidenceRequirementSatisfied());
        assertTrue(met.isEvidenceReady());
    }

    @Test void olderPolicyVersionsKeepTheirOriginalMeaning() {
        item.setEvidencePolicyVersion(2); item.setMinimumImageCount(0); item.setMinimumVideoCount(0);
        item.setMinimumEvidenceCount(2); item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_OR_VIDEO);
        assertTrue(service.ready(execution, links("VIDEO", "VIDEO"), true), "V2: any combination of the required total");
        item.setEvidencePolicyVersion(1); item.setMinimumEvidenceCount(1);
        assertTrue(service.ready(execution, links("IMAGE"), true));
        item.setEvidencePolicyVersion(0); item.setMinimumEvidenceCount(0);
        assertTrue(service.ready(execution, links(), true));
    }
    @Test void inspectorApprovalRequiresBothSelectedThresholdsAndCompliantCompletedReport() {
        var list = links("IMAGE", "IMAGE", "VIDEO");
        execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.COMPLIANT);
        assertTrue(service.ready(execution, list, true), "Legacy approval does not require selection");
        assertFalse(service.readyForInspectorApproval(execution, list));
        list.forEach(link -> link.setSelectedForCustomer(true));
        assertTrue(service.readyForInspectorApproval(execution, list));
        list.getLast().setSelectedForCustomer(false); assertFalse(service.readyForInspectorApproval(execution, list));
        list.getLast().setSelectedForCustomer(true);
        execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.NON_COMPLIANT);
        assertFalse(service.readyForInspectorApproval(execution, list));
        execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.COMPLIANT);
        execution.setExecutionStatus(ChecklistExecutionStatus.UNABLE_TO_VERIFY); execution.setUnableToVerifyReason("Weather");
        assertTrue(service.ready(execution, list, true)); assertFalse(service.readyForInspectorApproval(execution, list));
    }

}
