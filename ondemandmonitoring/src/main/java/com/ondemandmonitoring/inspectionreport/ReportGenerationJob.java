package com.ondemandmonitoring.inspectionreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component @RequiredArgsConstructor
public class ReportGenerationJob {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final MissionRepository missions;
    private final ReportEvidenceService evidence;
    private final InspectionReportService reports;
    private final TransactionTemplate tx;
    private final AtomicBoolean processing=new AtomicBoolean();
    private final ExecutorService reportWorker=Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("inspection-report-worker").daemon(true).factory());
    @Value("${DESCRIPTION_AI_URL:http://localhost:8002}") private String worker;
    @Value("${DESCRIPTION_AI_TOKEN:}") private String token;
    record Input(UUID id, UUID jobId, long revision, Map<String,Object> mission, ReportEvidenceService.Collection evidence) {}
    @Scheduled(fixedDelay=3000)
    public void run() {
        if(!processing.compareAndSet(false,true))return;
        try {
            reportWorker.execute(()->{try{processNext();}finally{processing.set(false);}});
        }catch(RejectedExecutionException closed){processing.set(false);}
    }
    @jakarta.annotation.PreDestroy
    void shutdown(){reportWorker.shutdownNow();}
    private void processNext() {
        if (!Boolean.TRUE.equals(db.queryForObject("select to_regclass('inspection_reports') is not null",Boolean.class))) return;
        tx.executeWithoutResult(s -> {
            var stale = db.queryForList("select id from inspection_reports where status='GENERATING' and job_status='RUNNING' and updated_at < now()-interval '10 minutes' for update skip locked");
            for (var row : stale) {
                UUID reportId=(UUID)row.get("id");
                db.update("update inspection_reports set status='FAILED',job_stage='FAILED',job_status='FAILED',error='Generation interrupted or timed out; retry',revision=revision+1,updated_at=now() where id=?",reportId);
                reports.audit(reportId,"GENERATION_INTERRUPTED","AI_WORKER");
            }
        });
        // One bounded job per scheduler invocation; DB claiming also protects multiple JVMs.
        var claim=tx.execute(s->{
            var rows=db.queryForList("select id,job_id from inspection_reports where status='GENERATING' and job_stage='QUEUED' order by created_at limit 1 for update skip locked");
            if(rows.isEmpty())return null;
            var row=rows.getFirst();db.update("update inspection_reports set job_stage='LOADING_MEDIA',job_status='RUNNING',updated_at=now() where id=?",row.get("id"));return row;
        });
        if(claim==null)return;
        UUID id=(UUID)claim.get("id"),job=(UUID)claim.get("job_id");
        try {
            Input input=tx.execute(s->{
                var row=db.queryForMap("select * from inspection_reports where id=?",id);
                var mission=missions.findById(row.get("mission_id").toString()).orElseThrow();
                var collection=evidence.collect(mission.getId());
                if(collection.images().stream().noneMatch(ReportEvidenceService.Evidence::isEligible))throw new IllegalStateException("No readable approved image is eligible. Review Mission media first.");
                Map<String,Object> metadata=new LinkedHashMap<>();
                metadata.put("missionId",mission.getId());metadata.put("missionCode",mission.getMissionCode());
                metadata.put("serviceName",mission.getOrder().getService().getName());
                metadata.put("scope",Objects.toString(mission.getScopeLabel(),""));
                metadata.put("objective",Objects.toString(mission.getOrder().getDescription(),""));
                metadata.put("actualStartAt",Objects.toString(mission.getActualStartAt(),""));
                return new Input(id,job,((Number)row.get("revision")).longValue(),metadata,collection);
            });
            stage(id,job,"SELECTING_EVIDENCE");
            var selected=input.evidence().images().stream().filter(ReportEvidenceService.Evidence::isEligible).filter(e->!"VIDEO_NOT_ANALYZED".equals(e.qualityStatus())).limit(12).toList();
            db.update("update inspection_reports set evidence=?::jsonb where id=? and job_id=?",reports.encode(input.evidence()),id,job);
            stage(id,job,"GENERATING_REPORT");
            var result=call(Map.of("mission",input.mission(),"images",selected,"findings",input.evidence().findings().stream().filter(f->f.mediaIds().stream().anyMatch(mediaId->selected.stream().anyMatch(image->image.mediaId().equals(mediaId)))).toList(),"action","generate"));
            stage(id,job,"VALIDATING_OUTPUT");
            var document=json.treeToValue(result.get("document"),ReportDocument.class);
            reports.validate(document,input.evidence());
            tx.executeWithoutResult(s->{
                var row=db.queryForMap("select * from inspection_reports where id=? for update",id);
                if(!job.equals(row.get("job_id"))||!"GENERATING".equals(row.get("status")))return;
                missions.findByIdForUpdate(input.mission().get("missionId").toString()).orElseThrow();
                reports.validateCurrent(input.mission().get("missionId").toString(),document,input.evidence());
                db.update("update inspection_reports set status='AI_DRAFT',job_stage='SAVING_DRAFT',job_status='SUCCEEDED',document=?::jsonb,evidence=?::jsonb,model=?,revision=revision+1,updated_at=now() where id=?",reports.encode(document),reports.encode(input.evidence()),result.path("model").asText(),id);
                reports.audit(id,"AI_GENERATED","AI_WORKER");
            });
        } catch(Exception e) {
            tx.executeWithoutResult(s->{
                int changed=db.update("update inspection_reports set status='FAILED',job_stage='FAILED',job_status='FAILED',error=?,revision=revision+1 where id=? and job_id=? and status='GENERATING'", "Generation failed: "+e.getClass().getSimpleName()+". Check worker availability, approved media and source evidence; retry.",id,job);
                if(changed>0)reports.audit(id,"GENERATION_FAILED","AI_WORKER");
            });
        }
    }
    void stage(UUID id,UUID job,String stage){if(db.update("update inspection_reports set job_stage=? where id=? and job_id=? and status='GENERATING'",stage,id,job)==0)throw new IllegalStateException("Generation cancelled");}
    com.fasterxml.jackson.databind.JsonNode call(Object body) throws Exception {
        if(token.isBlank())throw new IllegalStateException("Report worker token not configured");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
        var request=HttpRequest.newBuilder(URI.create(worker+"/inspection-reports/generate")).timeout(Duration.ofSeconds(270))
                .header("Content-Type","application/json").header("X-Internal-Token",token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<java.io.InputStream> response;
        try {
            response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                    "AI xử lý quá lâu. Bản báo cáo vẫn được giữ nguyên; vui lòng thử lại.", timeout);
        } catch (java.net.ConnectException unavailable) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa kết nối được dịch vụ AI. Hãy chạy Start AI Services rồi thử lại. Bản báo cáo vẫn được giữ nguyên.", unavailable);
        }
        try(var input=response.body()) {
            byte[] bytes=input.readNBytes(150_001);
            if(response.statusCode()!=200||bytes.length>150_000)throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    response.statusCode()==422
                            ? "AI chưa tạo được đề xuất hợp lệ cho nội dung này. Bản gốc vẫn được giữ nguyên; hãy thử tạo lại."
                            : "Dịch vụ AI chưa sẵn sàng hoặc trả dữ liệu không hợp lệ. Hãy kiểm tra Start AI Services rồi thử lại.");
            return json.readTree(bytes);
        }
    }
}
