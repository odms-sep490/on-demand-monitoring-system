package com.ondemandmonitoring.inspectionreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.validation.Validator;
import java.util.*;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class InspectionReportService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Validator validator;
    private final MissionRepository missions;
    private final IMissionAuthorizationService authorization;
    private final AuthenticatedUserResolver users;
    private final ICustomerMediaService customerMedia;
    private final IDeliveryWorkflowService delivery;
    private final IMediaObjectStorage storage;
    private final ReportEvidenceService evidence;
    private final ReportPdf pdf;
    private final TransactionTemplate transactions;

    public record View(UUID id, String missionId, String status, long revision, ReportDocument document,
            ReportEvidenceService.Collection evidence, String jobStage, String jobStatus, String error, String model,
            String approvedBy, String approvedAt, boolean canEdit, boolean canApprove) {}
    private Map<String,Object> row(UUID id, boolean lock) {
        requireSchema();
        var rows=db.queryForList("select * from inspection_reports where id=?"+(lock?" for update":""),id);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Report not found");return rows.getFirst();
    }
    private void requireSchema() {
        if(!Boolean.TRUE.equals(db.queryForObject("select to_regclass('inspection_reports') is not null and to_regclass('inspection_report_versions') is not null",Boolean.class)))
            throw error(HttpStatus.SERVICE_UNAVAILABLE,"Báo cáo chưa được kích hoạt. Cần áp dụng migration add_inspection_reports.sql trước khi sử dụng.");
    }
    private String missionId(Map<String,Object> row) { return row.get("mission_id").toString(); }
    private boolean customer() { return users.getCurrentUser().getRole().getCode().name().equals("CUSTOMER"); }
    public Map<String,Boolean> capabilities(String missionId) {
        internal(missionId,false);
        return Map.of("canCreate",canAuthor(missionId));
    }
    private boolean canAuthor(String missionId) {
        return "STAFF".equals(users.getCurrentUser().getRole().getCode().name())
                && authorization.canSubmitMissionResult(missionId);
    }
    private void internal(String id, boolean edit) {
        if(customer() || !(edit ? canAuthor(id) : authorization.canViewMissionMedia(id)))
            throw error(HttpStatus.FORBIDDEN,"Report access denied");
    }
    private void manager() { if(customer() || !authorization.canManageMissions())throw error(HttpStatus.FORBIDDEN,"Report approval requires Manager/Admin"); }
    private void requireCustomerAccess(Map<String,Object> row) {
        var mission=missions.findById(missionId(row)).orElseThrow();
        if(!"DELIVERED".equals(row.get("status")) || !mission.getOrder().getCustomer().getId().equals(users.getCurrentUserId()))
            throw error(HttpStatus.NOT_FOUND,"Delivered report not found");
        var document=decode(row.get("document"),ReportDocument.class);
        // Reuse every original-media publication, payment, retention and ownership check.
        for(String id:imageIds(document))customerMedia.createOriginalDownload(id);
    }
    @Transactional(readOnly=true)
    public List<View> list(String missionId) {
        if(customer()) {
            var mission=missions.findById(missionId).orElseThrow();
            if(!mission.getOrder().getCustomer().getId().equals(users.getCurrentUserId()))throw error(HttpStatus.FORBIDDEN,"Mission access denied");
        } else internal(missionId,false);
        requireSchema();
        var rows=db.queryForList("select * from inspection_reports where mission_id=?"+(customer()?" and status='DELIVERED'":"")+" order by case when status='IN_REVIEW' then 0 when document is not null then 1 else 2 end, created_at desc limit 30",missionId);
        return rows.stream().map(r->{if(customer())requireCustomerAccess(r);return view(r);}).toList();
    }
    @Transactional(readOnly=true)
    public View get(UUID id) {
        var r=row(id,false);if(customer())requireCustomerAccess(r);else internal(missionId(r),false);return view(r);
    }
    private View view(Map<String,Object> r) {
        boolean isCustomer=customer(); String mid=missionId(r);
        var collection=decode(r.get("evidence"),ReportEvidenceService.Collection.class);
        return new View((UUID)r.get("id"),mid,r.get("status").toString(),((Number)r.get("revision")).longValue(),
                r.get("document")==null?null:decode(r.get("document"),ReportDocument.class),
                isCustomer?null:collection,
                isCustomer?null:(String)r.get("job_stage"),isCustomer?null:(String)r.get("job_status"),isCustomer?null:(String)r.get("error"),
                isCustomer?null:(String)r.get("model"),(String)r.get("approved_by"),Objects.toString(r.get("approved_at"),null),
                !isCustomer&&canAuthor(mid),
                !isCustomer&&authorization.canManageMissions());
    }
    @Transactional
    public View create(String missionId) {
        internal(missionId,true);
        requireSchema();
        missions.findByIdForUpdate(missionId).orElseThrow();
        if(db.queryForObject("select count(*) from inspection_reports where mission_id=?",Integer.class,missionId)>=30)
            throw error(HttpStatus.CONFLICT,"Mission report limit reached");
        UUID id=UUID.randomUUID();
        db.update("insert into inspection_reports(id,mission_id,status,created_by,evidence) values(?,?,'DRAFT',?,?::jsonb)",id,missionId,users.getCurrentUserId(),encode(new ReportEvidenceService.Collection(List.of(),List.of(),List.of())));
        audit(id,"CREATE",users.getCurrentUserId());return get(id);
    }
    @Transactional
    public View generate(UUID id,long revision) {
        var r=row(id,true);internal(missionId(r),true);expected(r,revision);
        editable(r);
        if(r.get("document")!=null)throw error(HttpStatus.CONFLICT,"Create a new report to regenerate; staff edits are preserved");
        db.execute("select pg_advisory_xact_lock(hashtext('inspection-report-generation-queue'))");
        if(db.queryForObject("select count(*) from inspection_reports where status='GENERATING'",Integer.class)>=4)
            throw error(HttpStatus.TOO_MANY_REQUESTS,"Report queue is full; retry later");
        db.update("update inspection_reports set status='GENERATING',job_id=?,job_stage='QUEUED',job_status='QUEUED',error=null,revision=revision+1,updated_at=now() where id=?",UUID.randomUUID(),id);
        audit(id,"GENERATE",users.getCurrentUserId());return get(id);
    }
    @Transactional
    public View save(UUID id,long revision,ReportDocument document) {
        var r=row(id,true);internal(missionId(r),true);expected(r,revision);editable(r);
        missions.findByIdForUpdate(missionId(r)).orElseThrow();
        validate(document,decode(r.get("evidence"),ReportEvidenceService.Collection.class));
        validateCurrent(missionId(r),document,decode(r.get("evidence"),ReportEvidenceService.Collection.class));
        db.update("update inspection_reports set document=?::jsonb,status='AI_DRAFT',revision=revision+1,updated_at=now() where id=?",encode(document),id);
        audit(id,"EDIT",users.getCurrentUserId());return get(id);
    }
    @Transactional
    public View action(UUID id,long revision,String action) throws IOException {
        var r=row(id,true);
        boolean reviewAction=Set.of("request-revision","approve","finalize","deliver").contains(action);
        internal(missionId(r),!reviewAction);
        if(reviewAction)manager();
        expected(r,revision);
        String status=r.get("status").toString();String target;
        switch(action) {
            case "submit-review" -> {if(!Set.of("AI_DRAFT","REVISION_REQUESTED").contains(status))throw conflict();target="IN_REVIEW";}
            case "request-revision" -> {manager();if(!status.equals("IN_REVIEW"))throw conflict();target="REVISION_REQUESTED";}
            case "approve" -> {manager();if(!status.equals("IN_REVIEW"))throw conflict();target="APPROVED";}
            case "finalize" -> {manager();if(!status.equals("APPROVED"))throw conflict();target="FINALIZED";}
            case "deliver" -> {manager();if(!status.equals("FINALIZED"))throw conflict();target="DELIVERED";}
            case "cancel-generation" -> {if(!status.equals("GENERATING"))throw conflict();target="DRAFT";}
            default -> throw error(HttpStatus.BAD_REQUEST,"Unknown report action");
        }
        missions.findByIdForUpdate(missionId(r)).orElseThrow();
        ReportDocument document=r.get("document")==null?null:decode(r.get("document"),ReportDocument.class);
        if(!action.equals("cancel-generation")) {
            var collection=decode(r.get("evidence"),ReportEvidenceService.Collection.class);
            validate(document,collection);validateCurrent(missionId(r),document,collection);
        }
        if(action.equals("approve"))db.update("update inspection_reports set approved_by=?,approved_at=now() where id=?",users.getCurrentUserId(),id);
        if(action.equals("finalize")) {
            var mission=missions.findById(missionId(r)).orElseThrow();
            var collection=decode(r.get("evidence"),ReportEvidenceService.Collection.class);
            var context=new ReportRenderContext(
                    missionId(r),
                    mission.getMissionCode(),
                    mission.getOrder().getService().getName(),
                    revision+1,
                    (String)r.get("approved_by"),
                    Objects.toString(r.get("approved_at"),null),
                    mission.getOrder().getOrderCode(),
                    mission.getScopeLabel(),
                    mission.getOrder().getAddress(),
                    document,
                    collection,
                    List.of(),
                    ReportDesignTheme.CINEMATIC_AERIAL_EDITORIAL,
                    ReportRenderContext.CURRENT_RENDERER_VERSION);
            byte[] bytes=pdf.render(context);
            if(bytes.length>50*1024*1024)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"PDF exceeds 50 MiB; create a report with fewer images");
            String key=storage.prefix()+"/inspection-reports/"+id+"/"+(revision+1)+".pdf";
            var stored=storage.put(key,"application/pdf",bytes.length,new ByteArrayInputStream(bytes),"inspection-report");
            db.update("update inspection_reports set pdf_bucket=?,pdf_key=? where id=?",stored.bucket(),stored.key(),id);
        }
        if(action.equals("deliver")) {
            var mission=missions.findById(missionId(r)).orElseThrow();
            if(!delivery.originalAccessibleMissionIds(List.of(missionId(r))).contains(missionId(r)))
                throw error(HttpStatus.CONFLICT,"Complete the existing Mission delivery/payment workflow first");
            var published=delivery.publishedMediaAssetIds(List.of(missionId(r)));
            if(!published.containsAll(imageIds(document)))throw error(HttpStatus.CONFLICT,"Every report image must belong to the published delivery package");
            for(String mediaId:imageIds(document))delivery.requireOriginalAccessForMedia(mission.getOrder().getId(),mediaId);
            db.update("update inspection_reports set delivered_at=now() where id=?",id);
        }
        db.update("update inspection_reports set status=?,revision=revision+1,updated_at=now(),job_id=null,job_status=case when ?='DRAFT' then 'CANCELLED' else job_status end where id=?",target,target,id);
        audit(id,action,users.getCurrentUserId());return get(id);
    }
    @Transactional(readOnly=true)
    public byte[] artifact(UUID id,boolean preview) throws IOException {
        var r=row(id,false);if(customer())requireCustomerAccess(r);else internal(missionId(r),false);
        if(r.get("pdf_key")!=null) {
            var object=storage.open((String)r.get("pdf_bucket"),(String)r.get("pdf_key"));
            try(var input=object.inputStream()){
                byte[] bytes=input.readNBytes(50*1024*1024+1);
                if(bytes.length>50*1024*1024)throw new IOException("Stored PDF exceeds the artifact size limit");
                return bytes;
            }
        }
        if(customer()||!preview)throw conflict();
        var mission=missions.findById(missionId(r)).orElseThrow();
        var doc=decode(r.get("document"),ReportDocument.class);
        var collection=decode(r.get("evidence"),ReportEvidenceService.Collection.class);
        validate(doc,collection);
        var context=new ReportRenderContext(
                missionId(r),
                mission.getMissionCode(),
                mission.getOrder().getService().getName(),
                ((Number)r.get("revision")).longValue(),
                (String)r.get("approved_by"),
                Objects.toString(r.get("approved_at"),null),
                mission.getOrder().getOrderCode(),
                mission.getScopeLabel(),
                mission.getOrder().getAddress(),
                doc,
                collection,
                List.of(),
                ReportDesignTheme.CINEMATIC_AERIAL_EDITORIAL,
                ReportRenderContext.CURRENT_RENDERER_VERSION);
        return pdf.render(context);
    }
    @Transactional(readOnly=true)
    public byte[] image(UUID id,String mediaId) throws IOException {
        var r=row(id,false);if(customer())requireCustomerAccess(r);else internal(missionId(r),false);
        var collection=decode(r.get("evidence"),ReportEvidenceService.Collection.class);
        if(customer() ? !imageIds(decode(r.get("document"),ReportDocument.class)).contains(mediaId)
                : collection.images().stream().noneMatch(e->e.mediaId().equals(mediaId)))throw error(HttpStatus.NOT_FOUND,"Report image not found");
        var asset=evidence.requireMedia(missionId(r),mediaId);
        if(!"IMAGE".equals(asset.getType()))throw error(HttpStatus.NOT_FOUND,"Source is a video");
        var bitmap=evidence.readImage(asset);
        double scale=Math.min(1,1280d/Math.max(bitmap.getWidth(),bitmap.getHeight()));
        var small=new java.awt.image.BufferedImage((int)(bitmap.getWidth()*scale),(int)(bitmap.getHeight()*scale),java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=small.createGraphics();graphics.drawImage(bitmap,0,0,small.getWidth(),small.getHeight(),null);graphics.dispose();
        var output=new ByteArrayOutputStream();javax.imageio.ImageIO.write(small,"jpg",output);return output.toByteArray();
    }
    @Transactional(readOnly=true)
    public Map<String,String> video(UUID id,String mediaId) {
        var r=row(id,false);
        if(customer())requireCustomerAccess(r);else internal(missionId(r),false);
        var collection=decode(r.get("evidence"),ReportEvidenceService.Collection.class);
        if(customer() ? !imageIds(decode(r.get("document"),ReportDocument.class)).contains(mediaId)
                : collection.images().stream().noneMatch(e->e.mediaId().equals(mediaId)))
            throw error(HttpStatus.NOT_FOUND,"Report video not found");
        var asset=evidence.requireMedia(missionId(r),mediaId);
        if(!"VIDEO".equals(asset.getType()))throw error(HttpStatus.NOT_FOUND,"Report video not found");
        return Map.of("url",storage.createPresignedGetUrl(asset.getS3Bucket(),asset.getS3Key()));
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> history(UUID id) {var r=row(id,false);internal(missionId(r),false);return db.queryForList("select revision,action,actor,created_at,document from inspection_report_versions where report_id=? order by revision desc limit 100",id);}
    @Transactional(readOnly=true)
    public Map<String,Object> rewriteInput(UUID id,long revision,String instruction) {
        var r=row(id,false);internal(missionId(r),true);expected(r,revision);editable(r);
        if(!Set.of("professional","shorten","simplify","summarize","caption").contains(instruction))throw error(HttpStatus.BAD_REQUEST,"Unsupported rewrite instruction");
        var collection=decode(r.get("evidence"),ReportEvidenceService.Collection.class);
        var doc=decode(r.get("document"),ReportDocument.class);
        var mission=missions.findById(missionId(r)).orElseThrow();
        Map<String,Object> metadata=new LinkedHashMap<>();
        metadata.put("missionId",mission.getId());
        metadata.put("missionCode",Objects.toString(mission.getMissionCode(),""));
        metadata.put("serviceName",mission.getOrder().getService().getName());
        metadata.put("scope",Objects.toString(mission.getScopeLabel(),""));
        metadata.put("objective",Objects.toString(mission.getOrder().getDescription(),""));
        metadata.put("actualStartAt",Objects.toString(mission.getActualStartAt(),""));
        return Map.of("mission",metadata,
                "images",collection.images().stream().filter(e->imageIds(doc).contains(e.mediaId())).limit(40).toList(),
                "findings",collection.findings(),"document",doc,"action","rewrite","instruction",instruction);
    }
    void validateCurrent(String missionId,ReportDocument doc,ReportEvidenceService.Collection collection) {
        Set<String> cited=new HashSet<>();doc.sections().forEach(section->cited.addAll(section.sourceFindingIds()));
        if(!cited.isEmpty()) {
            var current=evidence.reviewedFindings(missionId);
            for(String id:cited) {
                var frozen=collection.findings().stream().filter(f->f.id().equals(id)).findFirst().orElseThrow();
                if(!current.contains(frozen))throw error(HttpStatus.CONFLICT,"Reviewed finding changed; create a new report");
            }
        }
        for(String id:imageIds(doc)) {
            var asset=evidence.requireMedia(missionId,id);
            var frozen=collection.images().stream().filter(e->e.mediaId().equals(id)).findFirst().orElseThrow();
            if(!Objects.equals(frozen.checksum(),asset.getChecksumSha256()))throw error(HttpStatus.CONFLICT,"Source image changed; create a new report");
        }
    }
    public void validate(ReportDocument doc,ReportEvidenceService.Collection collection) {
        if(doc==null||!validator.validate(doc).isEmpty())throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid report document");
        var ids=new HashSet<>(collection.images().stream().filter(ReportEvidenceService.Evidence::isEligible).map(ReportEvidenceService.Evidence::mediaId).toList());
        var findings=new HashSet<>(collection.findings().stream().map(ReportEvidenceService.Finding::id).toList());
        Set<String> sections=new HashSet<>();
        if(imageIds(doc).isEmpty()||imageIds(doc).size()>40)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Report requires 1–40 real images");
        for(var section:doc.sections()) {
            if(section.richText()!=null) ReportRichText.validate(section.richText(),section.content());
            if(!sections.add(section.sectionId())||!ids.containsAll(section.sourceMediaIds())||!findings.containsAll(section.sourceFindingIds()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Unknown or duplicate evidence reference");
            for(var image:section.images())if(!ids.contains(image.mediaId())||!section.sourceMediaIds().contains(image.mediaId()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Image must reference authorized section evidence");
            for(String fid:section.sourceFindingIds()) {
                var finding=collection.findings().stream().filter(f->f.id().equals(fid)).findFirst().orElseThrow();
                if(Collections.disjoint(finding.mediaIds(),section.images().stream().map(ReportDocument.Image::mediaId).toList()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Finding has no linked section evidence");
            }
        }
        String text=encode(doc);
        if(text.contains("<script")||text.contains("http://")||text.contains("https://")||text.length()>100_000)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Unsafe report content");
    }
    static Set<String> imageIds(ReportDocument doc) {var ids=new LinkedHashSet<String>();if(doc!=null)doc.sections().forEach(s->s.images().forEach(i->ids.add(i.mediaId())));return ids;}
    @Transactional(readOnly = true)
    public void validateProposal(UUID id, long revision, ReportDocument document) {
        var r = row(id, false);
        internal(missionId(r), true);
        expected(r, revision);
        editable(r);
        var collection = decode(r.get("evidence"), ReportEvidenceService.Collection.class);
        validate(document, collection);
        validateCurrent(missionId(r), document, collection);
    }

    @SuppressWarnings("unchecked")
    public com.ondemandmonitoring.inspectionreport.dto.ReportRewriteResponse rewriteProposal(
            UUID id,
            long revision,
            String instruction,
            ReportGenerationJob generation) {
        try {
            var payload = transactions.execute(status -> rewriteInput(id, revision, instruction));
            var proposal = generation.call(payload);
            var document = decode(proposal.get("document"), ReportDocument.class);
            transactions.executeWithoutResult(status -> validateProposal(id, revision, document));

            List<String> warnings = proposal.path("warnings").isArray()
                    ? decode(proposal.get("warnings"), List.class)
                    : List.of();

            return new com.ondemandmonitoring.inspectionreport.dto.ReportRewriteResponse(
                    document,
                    proposal.path("model").asText(),
                    warnings,
                    proposal.path("changed").asBoolean(),
                    proposal.path("skill").asText(),
                    proposal.path("latencyMs").asLong()
            );
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể tạo đề xuất AI lúc này. Vui lòng thử lại sau.",
                    error
            );
        }
    }
    private void expected(Map<String,Object> r,long revision){if(((Number)r.get("revision")).longValue()!=revision)throw error(HttpStatus.CONFLICT,"Report changed; reload before saving");}
    private void editable(Map<String,Object> r){if(!Set.of("DRAFT","AI_DRAFT","REVISION_REQUESTED","FAILED").contains(r.get("status")))throw conflict();}
    void audit(UUID id,String action,String actor){db.update("insert into inspection_report_versions(report_id,revision,action,actor,document) select id,revision,?,?,document from inspection_reports where id=?",action,actor,id);}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    <T>T decode(Object value,Class<T> type){try{return json.readValue(value.toString(),type);}catch(Exception e){throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid stored report content");}}
    static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    static ResponseStatusException conflict(){return error(HttpStatus.CONFLICT,"Report transition is not allowed");}
}
