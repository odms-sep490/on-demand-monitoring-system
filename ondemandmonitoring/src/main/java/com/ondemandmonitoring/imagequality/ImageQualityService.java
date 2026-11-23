package com.ondemandmonitoring.imagequality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class ImageQualityService {
    static final Set<String> PROFILES = Set.of("PANORAMA","DETAIL","INFRASTRUCTURE","MAPPING","CHANGE_COMPARISON");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MediaAssetRepository media;
    private final ICustomerMediaService customer;
    private final IMediaObjectStorage storage;
    private final IResultRetentionAccessService retention;
    private final IMissionAuthorizationService authorization;
    private final AuthenticatedUserResolver user;
    private final ObjectMapper json;
    @Value("${IMAGE_QUALITY_ENABLED:false}") boolean enabled;
    @Value("${IMAGE_QUALITY_WORKER_URL:http://127.0.0.1:8000}") String worker;
    @Value("${IMAGE_QUALITY_INTERNAL_TOKEN:}") String token;
    @Value("${IMAGE_QUALITY_MAX_BATCH_SIZE:20}") int batchLimit;
    @Value("${IMAGE_QUALITY_MAX_IMAGE_SIZE:8388608}") int maxBytes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final java.util.concurrent.atomic.AtomicBoolean processing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService workerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    @jakarta.annotation.PreDestroy
    public void shutdown() { workerExecutor.shutdownNow(); }
    static final String FIELDS = "id,media_id,mission_id,profile,profile_version,status,decision,result::text AS result,created_at,started_at,completed_at,error_code";

    void available() {
        if (!enabled || token.isBlank()) throw fail(HttpStatus.SERVICE_UNAVAILABLE,"QUALITY_NOT_CONFIGURED");
        if (!Boolean.TRUE.equals(db.queryForObject("select to_regclass('image_quality_check') is not null", Boolean.class)))
            throw fail(HttpStatus.SERVICE_UNAVAILABLE,"QUALITY_SCHEMA_REQUIRED");
    }
    String actor() { return user.getCurrentUser().getId(); }
    boolean isCustomer() { return "CUSTOMER".equals(user.getCurrentUser().getRole().getCode().name()); }

    MediaAsset authorize(String id, boolean customerRequest) {
        if (customerRequest) {
            customer.getAvailable(id);
            customer.createOriginalDownload(id); // existing owner, delivery and original entitlement
        }
        MediaAsset asset = media.findById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND,"MEDIA_NOT_FOUND"));
        if (!customerRequest) requireReviewer(asset.getMissionId());
        retention.requireAccessible(asset);
        if (!"IMAGE".equals(asset.getType()) || asset.getStorageStatus() != MediaStorageStatus.AVAILABLE
                || asset.getMediaStatus() == null || !Set.of(MediaStatus.AVAILABLE,MediaStatus.PENDING_MANAGER_APPROVAL,MediaStatus.REJECTED).contains(asset.getMediaStatus())
                || asset.getValidatedAt() == null)
            throw fail(HttpStatus.CONFLICT,"MEDIA_UNAVAILABLE");
        if (asset.getFileSize() == null || asset.getFileSize() > maxBytes) throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"IMAGE_SIZE_LIMIT");
        return asset;
    }
    void requireReviewer(String missionId) {
        if (!authorization.canManageMissions() && !authorization.canReviewMissionMedia(missionId))
            throw fail(HttpStatus.FORBIDDEN,"QUALITY_REVIEW_FORBIDDEN");
    }
    public Map<String,Object> request(String mediaId, UUID key, String profile, boolean customerRequest) {
        available();
        if (key == null || profile == null || !PROFILES.contains(profile)) throw fail(HttpStatus.BAD_REQUEST,"INVALID_QUALITY_REQUEST");
        MediaAsset asset = authorize(mediaId,customerRequest);
        String actor = actor();
        return tx.execute(status -> {
            db.query("select pg_advisory_xact_lock(hashtext(?))", rs -> null, actor);
            var existing = db.queryForList("select id,profile from image_quality_check where requested_by=? and request_key=? and media_id=?",actor,key,mediaId);
            if (!existing.isEmpty()) {
                if (!profile.equals(existing.getFirst().get("profile"))) throw fail(HttpStatus.CONFLICT,"REQUEST_KEY_CONFLICT");
                return read((UUID)existing.getFirst().get("id"));
            }
            db.query("select pg_advisory_xact_lock(910284::bigint)", rs -> null);
            int queued = db.queryForObject("select count(*) from image_quality_check where status in ('PENDING','PROCESSING')",Integer.class);
            int recent = db.queryForObject("select count(*) from image_quality_check where requested_by=? and created_at>now()-interval '1 hour'",Integer.class,actor);
            if (queued >= 100 || recent >= 60) throw fail(HttpStatus.TOO_MANY_REQUESTS,"QUALITY_RATE_LIMIT");
            UUID id = UUID.randomUUID();
            db.update("insert into image_quality_check(id,media_id,mission_id,requested_by,request_key,customer_request,profile) values(?,?,?,?,?,?,?)",
                    id,mediaId,asset.getMissionId(),actor,key,customerRequest,profile);
            event(id,actor,"REQUESTED");
            return read(id);
        });
    }
    public List<Map<String,Object>> history(String mediaId, boolean customerRequest) {
        available(); authorize(mediaId,customerRequest);
        var rows = db.queryForList("select "+FIELDS+" from image_quality_check where media_id=?"+
                (customerRequest ? " and requested_by=?" : "")+" order by created_at desc limit 30",
                customerRequest ? new Object[]{mediaId,actor()} : new Object[]{mediaId});
        rows.forEach(this::decode); return rows;
    }
    public Map<String,Object> read(UUID id) {
        available();
        var rows = db.queryForList("select "+FIELDS+",requested_by from image_quality_check where id=?",id);
        if (rows.isEmpty()) throw fail(HttpStatus.NOT_FOUND,"QUALITY_NOT_FOUND");
        var row=rows.getFirst();
        if (isCustomer() && !actor().equals(row.get("requested_by"))) throw fail(HttpStatus.NOT_FOUND,"QUALITY_NOT_FOUND");
        authorize((String)row.get("media_id"),isCustomer());
        row.remove("requested_by"); decode(row);
        row.put("reviews",db.queryForList("select decision,note,reviewed_at from image_quality_review where check_id=? order by reviewed_at desc",id));
        return row;
    }
    void decode(Map<String,Object> row) {
        if (row.get("result") instanceof String text) try { row.put("result",json.readValue(text,Map.class)); }
        catch(Exception e) { throw fail(HttpStatus.INTERNAL_SERVER_ERROR,"QUALITY_RESULT_INVALID"); }
    }
    public Map<String,Object> review(UUID id,String decision,String note) {
        available();
        if (decision == null || !Set.of("PASS","REVIEW_REQUIRED","FAIL").contains(decision) || note == null || note.length()>2000)
            throw fail(HttpStatus.BAD_REQUEST,"INVALID_REVIEW");
        var row=read(id); requireReviewer((String)row.get("mission_id"));
        if (!"COMPLETED".equals(row.get("status"))) throw fail(HttpStatus.CONFLICT,"QUALITY_NOT_COMPLETED");
        tx.executeWithoutResult(s -> {
            db.update("insert into image_quality_review(id,check_id,reviewer_id,decision,note) values(?,?,?,?,?)",UUID.randomUUID(),id,actor(),decision,note.strip());
            event(id,actor(),"REVIEW_"+decision);
        });
        return read(id);
    }
    public Map<String,Object> cancel(UUID id) {
        var row=read(id);
        tx.executeWithoutResult(s -> {
            int changed=db.update("update image_quality_check set status='CANCELLED',lease=null,completed_at=now() where id=? and status in ('PENDING','PROCESSING')",id);
            if(changed==1) event(id,actor(),"CANCELLED");
        });
        return read(id);
    }
    public List<Map<String,Object>> mission(String missionId) {
        available(); requireReviewer(missionId);
        return db.queryForList("""
            select m.id as media_id,m.original_file_name as file_name,q.id,q.status,q.decision,
              r.decision as human_decision
            from drone_media m left join lateral (select id,status,decision from image_quality_check
              where media_id=m.id order by created_at desc limit 1) q on true
            left join lateral (select decision from image_quality_review where check_id=q.id order by reviewed_at desc limit 1) r on true
            where m.mission_id=? and m.media_type='IMAGE' and m.storage_status='AVAILABLE'
              and m.media_status in ('AVAILABLE','PENDING_MANAGER_APPROVAL','REJECTED')
            order by m.created_at desc limit 500
            """,missionId);
    }
    public List<Map<String,Object>> batch(String missionId,UUID key,String profile) {
        var rows=mission(missionId);
        if (rows.size()>Math.min(100,Math.max(1,batchLimit))) throw fail(HttpStatus.BAD_REQUEST,"QUALITY_BATCH_LIMIT");
        return tx.execute(s -> rows.stream().map(row -> request((String)row.get("media_id"),key,profile,false)).toList());
    }
    void event(UUID id,String actor,String action) {
        db.update("insert into image_quality_event(check_id,actor,action) values(?,?,?)",id,actor,action);
    }
    @Scheduled(fixedDelay=3000)
    public void dispatch() {
        if (!enabled || token.isBlank() || !processing.compareAndSet(false,true)) return;
        try {
            workerExecutor.submit(() -> {
                try { runOne(); } catch(Exception e) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Quality dispatcher unavailable: {}",e.getClass().getSimpleName());
                } finally { processing.set(false); }
            });
        } catch(java.util.concurrent.RejectedExecutionException e) { processing.set(false); }
    }
    void runOne() throws Exception {
        UUID lease=UUID.randomUUID();
        var jobs=tx.execute(s -> {
            db.update("update image_quality_check set status=case when attempts>=3 then 'FAILED' else 'PENDING' end,error_code='WORKER_TIMEOUT',lease=null,completed_at=case when attempts>=3 then now() else null end where status='PROCESSING' and lease_at<now()-interval '3 minutes'");
            return db.queryForList("""
                update image_quality_check set status='PROCESSING',lease=?,lease_at=now(),started_at=now(),attempts=attempts+1
                where id=(select id from image_quality_check where status='PENDING' order by created_at for update skip locked limit 1)
                returning id,media_id,mission_id,profile,attempts
                """,lease);
        });
        if (jobs.isEmpty()) return;
        var job=jobs.getFirst(); UUID id=(UUID)job.get("id"); event(id,"worker","PROCESSING");
        try {
            var asset=media.findById((String)job.get("media_id")).orElseThrow();
            retention.requireAccessible(asset);
            if (asset.getStorageStatus()!=MediaStorageStatus.AVAILABLE || asset.getMediaStatus()==MediaStatus.REMOVED)
                throw new IllegalStateException("MEDIA_UNAVAILABLE");
            var object=storage.open(asset.getS3Bucket(),asset.getS3Key());
            byte[] bytes;
            try(var input=object.inputStream()) { bytes=input.readNBytes(maxBytes+1); }
            if(bytes.length>maxBytes) throw new IllegalArgumentException("SIZE_LIMIT");
            var request=HttpRequest.newBuilder(URI.create(worker+"/quality/analyze?profile="+job.get("profile")))
                .timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+token)
                .header("Content-Type","application/octet-stream").header("X-Image-Mime",asset.getContentType())
                .header("X-Image-Checksum",Objects.toString(asset.getChecksumSha256(),""))
                .header("X-Correlation-Id",id.toString().replace("-",""))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (var responseStream=response.body()) {
                if(response.statusCode()!=200) throw new java.io.IOException("WORKER_UNAVAILABLE");
                byte[] responseBytes;
                try(var reader=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var reading=reader.submit(() -> responseStream.readNBytes(65537));
                    try { responseBytes=reading.get(10,java.util.concurrent.TimeUnit.SECONDS); }
                    catch(Exception timeout) {
                        responseStream.close(); reading.cancel(true);
                        throw new java.io.IOException("WORKER_BODY_TIMEOUT",timeout);
                    }
                }
                if(responseBytes.length>65536) throw new IllegalArgumentException("RESULT_SIZE_LIMIT");
                responseBody=new String(responseBytes,java.nio.charset.StandardCharsets.UTF_8);
            }
            Map<String,Object> result=json.readValue(responseBody,Map.class);
            if (!Set.of("PASS","FAIL","REVIEW_REQUIRED","INDETERMINATE").contains(result.get("decision"))) throw new IllegalArgumentException("INVALID_RESULT");
            addDuplicates(result,(String)job.get("mission_id"),(String)job.get("media_id"));
            String body=json.writeValueAsString(result);
            tx.executeWithoutResult(s -> {
                int changed=db.update("update image_quality_check set status='COMPLETED',decision=?,result=?::jsonb,completed_at=now(),error_code=null where id=? and lease=? and status='PROCESSING'",result.get("decision"),body,id,lease);
                if(changed==1) event(id,"worker","COMPLETED");
            });
        } catch(Exception e) {
            boolean retry=((Number)job.get("attempts")).intValue()<3 && (e instanceof java.io.IOException || e instanceof InterruptedException);
            tx.executeWithoutResult(s -> {
                int changed=db.update("update image_quality_check set status=?,error_code=?,completed_at=case when ? then null else now() end where id=? and lease=? and status='PROCESSING'",retry?"PENDING":"FAILED",retry?"WORKER_RETRY":"QUALITY_CHECK_FAILED",retry,id,lease);
                if(changed==1) event(id,"worker",retry?"RETRY":"FAILED");
            });
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    @SuppressWarnings("unchecked")
    void addDuplicates(Map<String,Object> result,String missionId,String mediaId) {
        var metrics=(Map<String,Object>)result.get("metrics");
        Long hash=ImagePerceptualHash.parse((String)metrics.get("perceptualHash")); if(hash==null) return;
        var candidates=db.queryForList("select distinct on(media_id) media_id,result->'metrics'->>'perceptualHash' as hash from image_quality_check where mission_id=? and media_id<>? and status='COMPLETED' order by media_id,created_at desc limit 500",missionId,mediaId);
        List<String> near=new ArrayList<>();
        for(var row:candidates) if(ImagePerceptualHash.similar(hash,ImagePerceptualHash.parse((String)row.get("hash"))))
            near.add((String)row.get("media_id"));
        metrics.put("similarMediaIds",near);
        metrics.put("duplicateComparedImages",candidates.size());
        ((List<Map<String,Object>>)result.get("criteria")).add(Map.of("code","DUPLICATE",
            "status",candidates.isEmpty()?"INDETERMINATE":near.isEmpty()?"PASS":"REVIEW_REQUIRED",
            "message",candidates.isEmpty()?"Chưa có ảnh khác đã kiểm tra trong nhiệm vụ để đối chiếu.":near.isEmpty()?"Chưa thấy ảnh tương tự trong các ảnh đã kiểm tra.":"Có ảnh tương tự trong nhiệm vụ; chụp lặp lại có thể là chủ ý."));
        if(!near.isEmpty()) ((List<String>)result.get("warnings")).add("Có ảnh tương tự trong cùng nhiệm vụ; ảnh chụp lặp lại có thể là chủ ý.");
    }
    static ResponseStatusException fail(HttpStatus status,String code) { return new ResponseStatusException(status,code); }
}
