package com.ondemandmonitoring.imagequality;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicitly opt-in local database; own disposable schema, real HTTP quality worker. */
@EnabledIfEnvironmentVariable(named="QUALITY_TEST_JDBC_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1):.*")
class ImageQualityPostgresTest {
    @Test void durableQueueIdempotencyReviewAndBoundedRecovery() throws Exception {
        String url=System.getenv("QUALITY_TEST_JDBC_URL");
        String username=System.getenv("QUALITY_TEST_DB_USER"), password=System.getenv("QUALITY_TEST_DB_PASSWORD");
        var admin=new DriverManagerDataSource(url,username,password);
        var jdbc=new JdbcTemplate(admin);
        String schema="quality_test_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create schema "+schema);
        try {
            var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,username,password);
            var db=new JdbcTemplate(source);
            db.execute("create table drone_media(id varchar(255) primary key)");
            try(var connection=source.getConnection()) {
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("seeddata/migrations/add_image_quality.sql"));
            }
            db.update("insert into drone_media(id) values ('image')");
            var media=mock(MediaAssetRepository.class);var asset=mock(MediaAsset.class);
            when(media.findById("image")).thenReturn(Optional.of(asset));
            when(asset.getMissionId()).thenReturn("mission");when(asset.getType()).thenReturn("IMAGE");
            when(asset.getMediaStatus()).thenReturn(MediaStatus.AVAILABLE);when(asset.getStorageStatus()).thenReturn(MediaStorageStatus.AVAILABLE);
            when(asset.getValidatedAt()).thenReturn(Instant.now());when(asset.getContentType()).thenReturn("image/png");
            when(asset.getS3Bucket()).thenReturn("test-bucket");when(asset.getS3Key()).thenReturn("fixture.png");
            byte[] image=Files.readAllBytes(Path.of(System.getenv("QUALITY_TEST_IMAGE")));
            when(asset.getFileSize()).thenReturn((long)image.length);
            var storage=mock(IMediaObjectStorage.class);
            when(storage.open("test-bucket","fixture.png")).thenAnswer(i->new IMediaObjectStorage.StoredObjectStream(new ByteArrayInputStream(image),(long)image.length,"image/png"));
            var authorization=mock(IMissionAuthorizationService.class);when(authorization.canManageMissions()).thenReturn(true);
            var user=mock(AuthenticatedUserResolver.class,RETURNS_DEEP_STUBS);
            when(user.getCurrentUser().getId()).thenReturn("manager");when(user.getCurrentUser().getRole().getCode()).thenReturn(RoleCode.MANAGER);
            var service=new ImageQualityService(db,new TransactionTemplate(new DataSourceTransactionManager(source)),media,
                mock(ICustomerMediaService.class),storage,mock(IResultRetentionAccessService.class),authorization,user,new ObjectMapper());
            service.enabled=true;service.token=System.getenv("IMAGE_QUALITY_INTERNAL_TOKEN");service.worker="http://127.0.0.1:8000";service.maxBytes=8388608;service.batchLimit=20;
            UUID key=UUID.randomUUID();var job=service.request("image",key,"DETAIL",false);UUID id=(UUID)job.get("id");
            assertEquals(id,service.request("image",key,"DETAIL",false).get("id"));
            assertThrows(Exception.class,()->service.request("image",key,"MAPPING",false));
            service.runOne();var completed=service.read(id);assertEquals("COMPLETED",completed.get("status"));
            var result=(Map<?,?>)completed.get("result");assertNotNull(result.get("metrics"));
            assertEquals("NOT_CONFIGURED",((Map<?,?>)result.get("classifier")).get("status"));
            service.review(id,"REVIEW_REQUIRED","Cần nhân viên đối chiếu yêu cầu độ phân giải.");
            assertEquals(1,db.queryForObject("select count(*) from image_quality_review",Integer.class));
            assertTrue(db.queryForObject("select count(*) from image_quality_event",Integer.class)>=4);
            var failed=service.request("image",UUID.randomUUID(),"DETAIL",false);service.worker="http://127.0.0.1:1";
            service.runOne();service.runOne();service.runOne();assertEquals("FAILED",service.read((UUID)failed.get("id")).get("status"));
            assertEquals(3,db.queryForObject("select attempts from image_quality_check where id=?",Integer.class,failed.get("id")));
        } finally { jdbc.execute("drop schema "+schema+" cascade"); }
    }
}
