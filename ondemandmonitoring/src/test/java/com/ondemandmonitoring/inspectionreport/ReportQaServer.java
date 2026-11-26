package com.ondemandmonitoring.inspectionreport;

import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

/** Isolated module QA only. No production component scan, credentials or external notifications. */
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
@Profile("inspection-report-isolated-qa")
@EnableAutoConfiguration @EnableScheduling @EnableMethodSecurity
@Import({InspectionReportController.class,InspectionReportService.class,ReportEvidenceService.class,ReportPdf.class,ReportGenerationJob.class,ReportSourceGuard.class,com.ondemandmonitoring.common.exception.GlobalExceptionHandler.class,ReportQaServer.Fixtures.class})
public class ReportQaServer {
    static final Path ROOT=Path.of("../.ai-runtime/inspection-report-qa/storage").toAbsolutePath().normalize();
    static final AtomicBoolean RELEASED=new AtomicBoolean(false);
    static final AtomicBoolean FINDINGS=new AtomicBoolean(false);
    static final java.util.concurrent.atomic.AtomicInteger SCHEDULER_TICKS=new java.util.concurrent.atomic.AtomicInteger();
    static String actor(){var a=SecurityContextHolder.getContext().getAuthentication();return a==null?"":a.getName();}
    public static void main(String[] args){
        var app=new SpringApplication(ReportQaServer.class);
        app.setAdditionalProfiles("inspection-report-isolated-qa");
        app.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent>)event->{
            var env=event.getEnvironment();
            if(!"jdbc:postgresql://localhost:5549/postgres".equals(env.getProperty("spring.datasource.url"))
                    ||!"reportqa".equals(env.getProperty("spring.datasource.username"))
                    ||!"none".equals(env.getProperty("spring.jpa.hibernate.ddl-auto"))
                    ||!"never".equals(env.getProperty("spring.sql.init.mode")))
                throw new IllegalStateException("Refusing non-isolated report QA configuration before opening a database connection");
        });
        app.setDefaultProperties(Map.ofEntries(
            Map.entry("server.port","8086"),Map.entry("server.address","localhost"),
            Map.entry("spring.config.location","optional:file:../.ai-runtime/inspection-report-qa/empty.properties"),
            Map.entry("spring.datasource.url","jdbc:postgresql://localhost:5549/postgres"),
            Map.entry("spring.datasource.username","reportqa"),Map.entry("spring.datasource.password",""),
            Map.entry("spring.jpa.hibernate.ddl-auto","none"),Map.entry("spring.sql.init.mode","never"),
            Map.entry("DESCRIPTION_AI_URL","http://localhost:8012"),Map.entry("DESCRIPTION_AI_TOKEN","inspection-report-isolated-qa")
        ));app.run(args);
    }
    @Bean ObjectMapper mapper(){return new ObjectMapper().registerModule(new JavaTimeModule());}
    @Bean SecurityFilterChain security(HttpSecurity http)throws Exception {
        return http.cors(c->c.configurationSource(request->{var cors=new org.springframework.web.cors.CorsConfiguration();cors.setAllowedOrigins(List.of("http://localhost:5175"));cors.setAllowedMethods(List.of("GET","POST","PATCH","OPTIONS"));cors.setAllowedHeaders(List.of("*"));return cors;})).csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().authenticated())
            .addFilterBefore(new OncePerRequestFilter(){
                protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)throws jakarta.servlet.ServletException,IOException {
                    String who=request.getHeader("X-QA-Actor");
                    if(Set.of("qa-manager","qa-inspector","qa-customer","qa-other").contains(Objects.toString(who,""))) {
                        String role=who.equals("qa-manager")?"MANAGER":who.equals("qa-inspector")?"STAFF":"CUSTOMER";
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(who,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
                    }
                    chain.doFilter(request,response);
                }
            },UsernamePasswordAuthenticationFilter.class).build();
    }
    @Bean AuthenticatedUserResolver users(){
        var users=mock(AuthenticatedUserResolver.class);
        when(users.getCurrentUser()).thenAnswer(i->{var role=new Role();role.setCode(actor().equals("qa-manager")?RoleCode.MANAGER:actor().equals("qa-inspector")?RoleCode.STAFF:RoleCode.CUSTOMER);var u=User.builder().role(role).isActive(true).build();u.setId(actor());return u;});
        when(users.getCurrentUserId()).thenAnswer(i->actor());return users;
    }
    @Bean IMissionAuthorizationService authorization(){
        var a=mock(IMissionAuthorizationService.class);
        when(a.canManageMissions()).thenAnswer(i->actor().equals("qa-manager"));
        when(a.canViewMissionMedia(anyString())).thenAnswer(i->Set.of("qa-manager","qa-inspector").contains(actor()));
        when(a.canSubmitMissionResult(anyString())).thenAnswer(i->actor().equals("qa-inspector"));return a;
    }
    @Bean MissionRepository missions(){var repo=mock(MissionRepository.class);when(repo.findById(anyString())).thenAnswer(i->Optional.of(mission(i.getArgument(0))));when(repo.findByIdForUpdate(anyString())).thenAnswer(i->Optional.of(mission(i.getArgument(0))));return repo;}
    static Mission mission(String id){var m=new Mission();m.setId(id);m.setMissionCode("QA-REPORT");m.setScopeLabel("Kiểm tra hồ sơ ảnh Mission QA");m.setActualStartAt(Instant.parse("2026-11-24T00:00:00Z"));var customer=User.builder().build();customer.setId("qa-customer");var order=Order.builder().customer(customer).description("Ghi nhận hiện trạng theo ảnh đã được duyệt.").build();order.setId("qa-order");var service=new com.ondemandmonitoring.service.domain.Service();service.setName("Giám sát công trình");order.setService(service);m.setOrder(order);return m;}
    @Bean IMediaObjectStorage storage()throws IOException {
        Files.createDirectories(ROOT);var storage=mock(IMediaObjectStorage.class);
        when(storage.prefix()).thenReturn("qa");
        when(storage.open(anyString(),anyString())).thenAnswer(i->{Path p=ROOT.resolve((String)i.getArgument(1)).normalize();if(!p.startsWith(ROOT))throw new IOException("Invalid QA path");return new IMediaObjectStorage.StoredObjectStream(Files.newInputStream(p),Files.size(p),p.toString().endsWith("pdf")?"application/pdf":"image/png");});
        when(storage.put(anyString(),anyString(),anyLong(),any(InputStream.class),anyString())).thenAnswer(i->{String key=i.getArgument(0);Path p=ROOT.resolve(key).normalize();if(!p.startsWith(ROOT))throw new IOException();Files.createDirectories(p.getParent());try(InputStream input=i.getArgument(3)){Files.copy(input,p,StandardCopyOption.REPLACE_EXISTING);}return new IMediaObjectStorage.StoredObject("qa",key,"");});return storage;
    }
    static MediaAsset asset(String id,int number)throws IOException {
        Path file=ROOT.resolve(id+".png");
        if(!Files.exists(file)){var image=new java.awt.image.BufferedImage(1200,800,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(new java.awt.Color(200-number*15,220,230));g.fillRect(0,0,1200,800);g.setColor(new java.awt.Color(40,80+number*20,120));g.fillRect(100+number*40,100,700,450);g.setColor(java.awt.Color.WHITE);g.drawString("ISOLATED QA IMAGE "+number,150,250);g.dispose();ImageIO.write(image,"png",file.toFile());}
        var a=new MediaAsset();a.setId(id);a.setMissionId("qa-mission");a.setType("IMAGE");a.setMediaStatus(MediaStatus.AVAILABLE);a.setContentType("image/png");a.setOriginalFileName("Ảnh QA "+number+".png");a.setFileSize(Files.size(file));a.setS3Bucket("qa");a.setS3Key(id+".png");a.setCapturedAt(Instant.parse("2026-11-24T00:00:00Z").plusSeconds(number));a.setChecksumSha256("fixture-sha-"+number);return a;
    }
    @Bean MediaAssetRepository media(IMediaObjectStorage storage)throws IOException {
        var repo=mock(MediaAssetRepository.class);var assets=List.of(asset("qa-image-1",1),asset("qa-image-2",2),asset("qa-image-3",3));
        when(repo.findMissionCandidates(anyString(),any(Pageable.class))).thenAnswer(i->new PageImpl<>(i.getArgument(0).equals("qa-empty")?List.of():assets));
        when(repo.findById(anyString())).thenAnswer(i->assets.stream().filter(a->a.getId().equals(i.getArgument(0))).findFirst());return repo;
    }
    @Bean MissionChecklistEvidenceRepository links(){var repo=mock(MissionChecklistEvidenceRepository.class);when(repo.findActiveByMissionId(anyString())).thenAnswer(i->{
        if(!FINDINGS.get()||!"qa-mission".equals(i.getArgument(0)))return List.of();
        var execution=new MissionChecklistExecution();execution.setId("qa-finding-1");execution.setAssessedBy("qa-inspector");execution.setAssessedAt(Instant.parse("2026-11-24T01:00:00Z"));execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.COMPLIANT);execution.setAssessmentNote("Đã ghi nhận hồ sơ ảnh theo phạm vi nhiệm vụ; cần đối chiếu khi nghiệm thu.");
        var link=new MissionChecklistEvidence();link.setMissionChecklistExecution(execution);link.setMediaAsset(asset("qa-image-1",1));link.setSelectedForCustomer(true);return List.of(link);
    });return repo;}
    @Bean DeliveryImageQualityReader quality(){var reader=mock(DeliveryImageQualityReader.class);when(reader.read(anyString(),anyList())).thenReturn(Map.of());return reader;}
    @Bean IDeliveryWorkflowService delivery(){var d=mock(IDeliveryWorkflowService.class);when(d.originalAccessibleMissionIds(anyList())).thenAnswer(i->RELEASED.get()?List.of("qa-mission"):List.of());when(d.publishedMediaAssetIds(anyList())).thenReturn(Set.of("qa-image-1","qa-image-2","qa-image-3"));return d;}
    @Bean ICustomerMediaService customerMedia(){var m=mock(ICustomerMediaService.class);when(m.createOriginalDownload(anyString())).thenAnswer(i->{if(!RELEASED.get())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);return null;});return m;}
    @Bean ApplicationRunner initialize(JdbcTemplate db){return args->{
        try(var connection=db.getDataSource().getConnection()) {
            if(!connection.getMetaData().getURL().contains(":5549/"))throw new IllegalStateException("QA database guard");
        }
        db.execute("create table if not exists missions(id varchar(255) primary key)");db.update("insert into missions(id) values('qa-mission'),('qa-empty') on conflict do nothing");
        try(var input=new org.springframework.core.io.ClassPathResource("seeddata/migrations/add_inspection_reports.sql").getInputStream()){db.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    };}
    @org.springframework.boot.test.context.TestComponent
    @Profile("inspection-report-isolated-qa")
    @RestController static class Fixtures {
        private final ReportSourceGuard guard;
        Fixtures(ReportSourceGuard guard){this.guard=guard;}
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
        void schedulerHeartbeat(){SCHEDULER_TICKS.incrementAndGet();}
        @GetMapping("/qa/scheduler-ticks") Object schedulerTicks(){if(!actor().equals("qa-manager"))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);return Map.of("ticks",SCHEDULER_TICKS.get());}
        @GetMapping("/qa/source-guard/{id}") Object sourceGuard(@PathVariable String id){if(!actor().equals("qa-manager"))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);return Map.of("referenced",guard.referenced(id,false),"approved",guard.referenced(id,true));}
        @PostMapping("/qa/findings/{enabled}") Object findings(@PathVariable boolean enabled){if(!actor().equals("qa-manager"))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);FINDINGS.set(enabled);return Map.of("enabled",enabled);}
        @PostMapping("/qa/release") Object release(){if(!actor().equals("qa-manager"))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);RELEASED.set(true);return Map.of("released",true);}
    }
}
