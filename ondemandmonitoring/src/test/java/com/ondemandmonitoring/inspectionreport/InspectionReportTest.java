package com.ondemandmonitoring.inspectionreport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import jakarta.validation.Validation;
import java.util.*;
import java.time.Instant;
import java.nio.file.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class InspectionReportTest {
    JdbcTemplate db=mock(JdbcTemplate.class);
    ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    MissionRepository missions=mock(MissionRepository.class);
    IMissionAuthorizationService auth=mock(IMissionAuthorizationService.class);
    AuthenticatedUserResolver users=mock(AuthenticatedUserResolver.class);
    ICustomerMediaService customer=mock(ICustomerMediaService.class);
    IDeliveryWorkflowService delivery=mock(IDeliveryWorkflowService.class);
    IMediaObjectStorage storage=mock(IMediaObjectStorage.class);
    ReportEvidenceService sources=mock(ReportEvidenceService.class);
    ReportPdf pdf=new ReportPdf(sources);
    InspectionReportService reports=new InspectionReportService(db,json,Validation.buildDefaultValidatorFactory().getValidator(),missions,auth,users,customer,delivery,storage,sources,pdf,mock(TransactionTemplate.class));
    UUID id=UUID.randomUUID();
    ReportEvidenceService.Collection collection=new ReportEvidenceService.Collection(List.of(new ReportEvidenceService.Evidence("image-1","ảnh.jpg",Instant.parse("2026-11-01T00:00:00Z"),1200,800,"PASS",50,true,"Resolution",List.of("finding-1"),false,"sha")),List.of(new ReportEvidenceService.Finding("finding-1","Ghi nhận hiện trạng theo ảnh nguồn.","COMPLIANT",List.of("image-1"))),List.of());
    ReportDocument document(String image,String finding){return new ReportDocument("Báo cáo giám sát nhà xưởng","Tổng hợp hồ sơ giám sát.","Phạm vi theo nhiệm vụ.","Ảnh drone đã được duyệt.",List.of(new ReportDocument.Section("overview","Bằng chứng hiện trường","Ghi nhận hiện trạng theo ảnh nguồn.",List.of(new ReportDocument.Image(image,"OVERVIEW","Ảnh nguồn đã được kiểm tra.")),List.of(finding),List.of(image),null)),List.of("Không thay thế kiểm định chuyên môn."),"Cần đối chiếu với bằng chứng.",List.of("Rà soát hồ sơ."));}
    @BeforeEach void actor(){when(db.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true);var role=new Role();role.setCode(RoleCode.MANAGER);var user=User.builder().role(role).isActive(true).build();user.setId("reviewer");when(users.getCurrentUser()).thenReturn(user);when(users.getCurrentUserId()).thenReturn("reviewer");when(auth.canManageMissions()).thenReturn(true);when(auth.canViewMissionMedia("mission-1")).thenReturn(true);}
    Map<String,Object> row(String status)throws Exception{var r=new HashMap<String,Object>();r.put("id",id);r.put("mission_id","mission-1");r.put("status",status);r.put("revision",2L);r.put("evidence",json.writeValueAsString(collection));r.put("document",json.writeValueAsString(document("image-1","finding-1")));return r;}
    @Test void acceptsAuthorizedEvidence(){assertDoesNotThrow(()->reports.validate(document("image-1","finding-1"),collection));}
    @Test void rejectsUnknownMedia(){assertThrows(ResponseStatusException.class,()->reports.validate(document("another-mission","finding-1"),collection));}
    @Test void rejectsUnknownFinding(){assertThrows(ResponseStatusException.class,()->reports.validate(document("image-1","invented"),collection));}
    @Test void rejectsNoEvidence(){assertThrows(ResponseStatusException.class,()->reports.validate(document("image-1","finding-1"),new ReportEvidenceService.Collection(List.of(),List.of(),List.of())));}
    @Test void rejectsUnauthorizedMission(){when(auth.canManageMissions()).thenReturn(false);when(auth.canSubmitMissionResult("mission-1")).thenReturn(false);assertThrows(ResponseStatusException.class,()->reports.create("mission-1"));verifyNoInteractions(db);}
    @Test void finalizedVersionCannotBeEdited()throws Exception{when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(row("FINALIZED")));assertThrows(ResponseStatusException.class,()->reports.save(id,2,document("image-1","finding-1")));verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void staleRevisionIsRejected()throws Exception{when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(row("AI_DRAFT")));assertThrows(ResponseStatusException.class,()->reports.save(id,1,document("image-1","finding-1")));}
    @Test void draftCannotBeApproved()throws Exception{when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(row("AI_DRAFT")));assertThrows(ResponseStatusException.class,()->reports.action(id,2,"approve"));}
    @Test void approvalRequiresManager()throws Exception{when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(row("IN_REVIEW")));when(auth.canManageMissions()).thenReturn(false);when(auth.canSubmitMissionResult("mission-1")).thenReturn(true);assertThrows(ResponseStatusException.class,()->reports.action(id,2,"approve"));}
    @Test void rejectsEditorTextDifferentFromPdf() {
        assertThrows(ResponseStatusException.class,()->ReportRichText.validate(Map.of("type","doc","content",List.of(Map.of("type","paragraph","content",List.of(Map.of("type","text","text","Nội dung khác"))))),"Nội dung PDF"));
    }
    @Test void rejectsEditorLinksAndInvalidNodes() {
        assertThrows(ResponseStatusException.class,()->ReportRichText.validate(Map.of("type","doc","content",List.of(Map.of("type","image","attrs",Map.of("src","https://external")))),""));
        assertThrows(ResponseStatusException.class,()->ReportRichText.validate(Map.of(),""));
    }
    @Test void acceptsFormattedTextWithSamePlainText() {
        assertDoesNotThrow(()->ReportRichText.validate(Map.of("type","doc","content",List.of(Map.of("type","paragraph","content",List.of(Map.of("type","text","text","Đánh giá đã xác nhận","marks",List.of(Map.of("type","bold"))))))),"Đánh giá đã xác nhận"));
    }
    @Test void rejectsChangedReviewedFindings() {
        when(sources.reviewedFindings("mission-1")).thenReturn(List.of());
        assertThrows(ResponseStatusException.class,()->reports.validateCurrent("mission-1",document("image-1","finding-1"),collection));
    }
    @Test void controllerPreservesConflictStatusUnderGenericAdvice() throws Exception {
        var controller=new InspectionReportController(reports,mock(ReportGenerationJob.class));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.ondemandmonitoring.common.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/inspection-reports/"+UUID.randomUUID()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    @Test void pdfEmbedsRealImageAndVietnamese()throws Exception{
        var asset=new MediaAsset();asset.setId("image-1");asset.setCapturedAt(Instant.parse("2026-11-01T00:00:00Z"));
        when(sources.requireMedia("mission-1","image-1")).thenReturn(asset);
        var image=new java.awt.image.BufferedImage(1200,800,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(java.awt.Color.LIGHT_GRAY);graphics.fillRect(0,0,1200,800);graphics.setColor(java.awt.Color.BLUE);graphics.fillRect(100,100,900,450);graphics.dispose();
        when(sources.readImage(asset)).thenReturn(image);
        byte[] bytes=pdf.render("mission-1","QA-MISSION","Kiểm tra nhà xưởng",4,"reviewer","2026-11-24",document("image-1","finding-1"));
        var folder=Path.of("../.ai-runtime/qa-artifacts/inspection-report");Files.createDirectories(folder);Files.write(folder.resolve("report.pdf"),bytes);
        try(var result=Loader.loadPDF(bytes)){
            String text=new PDFTextStripper().getText(result);assertTrue(text.contains("Báo cáo giám sát nhà xưởng"));assertTrue(text.contains("image-1"));assertTrue(result.getNumberOfPages()>=2);
            int images=0;for(var page:result.getPages())for(var name:page.getResources().getXObjectNames())if(page.getResources().isImageXObject(name))images++;
            assertTrue(images>=1);
            var renderer=new PDFRenderer(result);for(int i=0;i<result.getNumberOfPages();i++)javax.imageio.ImageIO.write(renderer.renderImageWithDPI(i,100),"png",folder.resolve("page-"+(i+1)+".png").toFile());
        }
    }
    @Test void storedFinalizedPdfBytesAreStreamedWithoutRerendering()throws Exception{
        byte[] legacyBytes="%PDF-1.4\nLEGACY-IMMUTABLE-BYTES".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var finalizedRow=row("DELIVERED");
        finalizedRow.put("pdf_bucket","private-bucket");
        finalizedRow.put("pdf_key","prefix/inspection-reports/"+id+"/5.pdf");
        when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(finalizedRow));
        when(storage.open("private-bucket","prefix/inspection-reports/"+id+"/5.pdf"))
                .thenReturn(new IMediaObjectStorage.StoredObjectStream(new java.io.ByteArrayInputStream(legacyBytes),(long)legacyBytes.length,"application/pdf"));
        byte[] returned=reports.artifact(id,false);
        assertArrayEquals(legacyBytes,returned);
        verifyNoInteractions(sources);
    }
}
