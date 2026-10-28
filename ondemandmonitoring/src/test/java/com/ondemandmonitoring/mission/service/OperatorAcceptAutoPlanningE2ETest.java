package com.ondemandmonitoring.mission.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.mission.dto.request.AssignDeviceRequest;
import com.ondemandmonitoring.mission.dto.request.AssignStaffRequest;
import com.ondemandmonitoring.mission.dto.request.ChecklistAssignmentSelectionRequest;
import com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest;
import com.ondemandmonitoring.mission.dto.request.MissionCreateRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionPlanRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.repository.RoleRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@SpringBootTest
@Transactional
@ActiveProfiles("e2e")
@Import(OperatorAcceptAutoPlanningE2ETest.TestSchemaBootstrap.class)
@EnabledIfEnvironmentVariable(named = "ODMS_MISSION_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/mission_accept_verification")
class OperatorAcceptAutoPlanningE2ETest {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired private IMissionService missionService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private QuoteRepository quoteRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private OrderChecklistItemRepository orderChecklistItemRepository;
    @Autowired private IMissionChecklistAssignmentService checklistAssignmentService;
    @Autowired private IExecutionPlanService executionPlanService;
    @Autowired private MissionPlanRepository missionPlanRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.ondemandmonitoring.service.repository.ServiceRepository services;
    @Autowired private com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository preferredTimes;
    @MockitoBean(name = "cognitoAccessTokenDecoder") private JwtDecoder accessDecoder;
    @MockitoBean(name = "cognitoIdTokenDecoder") private JwtDecoder idDecoder;
    @MockitoBean private com.ondemandmonitoring.auth.port.out.IdentityProviderPort identityProvider;
    @MockitoBean private software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient cognito;
    @MockitoBean private software.amazon.awssdk.services.s3.S3Client s3;
    @MockitoBean private software.amazon.awssdk.services.s3.presigner.S3Presigner presigner;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("ODMS_MISSION_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "checklist_test");
        properties.add("spring.datasource.password", () -> "checklist_test_only");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        properties.add("spring.jpa.open-in-view", () -> "false");
        properties.add("spring.jpa.show-sql", () -> "false");
        properties.add("app.seed.demo-drones", () -> "false");
        properties.add("app.media.sqs.enabled", () -> "false");
        properties.add("app.media.reconciliation.enabled", () -> "false");
        properties.add("mission.replanning.enabled", () -> "false");
        properties.add("auth.outbox.fixed-delay-ms", () -> "3600000");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }


    @TestConfiguration(proxyBeanMethods = false)
    static class TestSchemaBootstrap {
        @Bean
        @org.springframework.core.annotation.Order(0)
        ApplicationRunner bootstrapAfterHibernate(DataSource dataSource) {
            return args -> {
                try {
                    String sql = new ClassPathResource("seeddata/migrations/bootstrap_after_hibernate.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
                    new JdbcTemplate(dataSource).execute(sql);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Current PostgreSQL bootstrap SQL is unavailable", exception);
                }
            };
        }
    }

    @Test
    void operatorAcceptDefersPlanningUntilFreshTelemetryPreflight() {
        Order order = new Order();
        order.setCustomer(createActor(RoleCode.CUSTOMER));
        order.setService(services.findAll().getFirst());
        order.setPreferredTime(preferredTimes.findAll().getFirst());
        order.setTitle("Operator acceptance integration fixture");
        order.setPoint(point(106.70, 10.77));
        order.setPreferredDateFrom(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2));
        order.setPreferredDateTo(order.getPreferredDateFrom());
        order.setOrderStatus(com.ondemandmonitoring.order.enums.OrderStatus.APPROVED);
        order.setChecklistSnapshotAt(Instant.now());
        order.setDataRetentionDays(90);
        order = orderRepository.saveAndFlush(order);
        createAcceptedInvoice(order);
        var model = new com.ondemandmonitoring.device.domain.DeviceModel();
        model.setCode("acceptance-model");
        model.setModelName("Acceptance model");
        entityManager.persist(model);
        Device device = new Device();
        device.setDeviceModel(model);
        device.setName("Acceptance device");
        device.setSerialNumber(java.util.UUID.randomUUID().toString());
        device.setStatus(DeviceStatus.AVAILABLE);
        deviceRepository.saveAndFlush(device);

        User manager = createActor(RoleCode.MANAGER);
        User staff = createActor(RoleCode.STAFF);
        String staffId = staff.getId();

        java.time.LocalDate targetDate = order.getPreferredDateFrom() != null ? order.getPreferredDateFrom() : java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.Instant startAt = targetDate.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC);
        java.time.Instant endAt = targetDate.atTime(11, 0).toInstant(java.time.ZoneOffset.UTC);

        MissionCreateRequest createRequest = new MissionCreateRequest();
        createRequest.setScopeLabel("E2E acceptance verification");
        createRequest.setScheduledStartAt(startAt);
        createRequest.setScheduledEndAt(endAt);
        authenticate(manager);
        MissionResponse created = missionService.createMission(order.getId(), createRequest);

        OrderChecklistItem checklistItem = new OrderChecklistItem();
        checklistItem.setOrder(order);
        checklistItem.setContent("E2E monitoring requirement");
        checklistItem.setDisplayOrder(0);
        checklistItem.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        checklistItem.setReviewStatus(ChecklistReviewStatus.INCLUDED);
        checklistItem.setEvidencePolicyVersion(2);
        checklistItem.setMinimumEvidenceCount(1);
        checklistItem.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_OR_VIDEO);
        checklistItem = orderChecklistItemRepository.saveAndFlush(checklistItem);

        checklistAssignmentService.replace(created.getId(), new ChecklistAssignmentSelectionRequest(
                order.getExecutionPlanRevision(),
                java.util.List.of(new ChecklistAssignmentSelectionRequest.Item(checklistItem.getId()))));
        executionPlanService.finalizePlan(order.getId(), new ExecutionPlanFinalizeRequest(
                order.getExecutionPlanRevision(), true));

        AssignDeviceRequest deviceRequest = new AssignDeviceRequest();
        deviceRequest.setDeviceId(device.getId());
        MissionResponse deviceAssigned = missionService.assignDevice(created.getId(), deviceRequest);

        MissionResponse staffAssigned = deviceAssigned;
        for (var role : com.ondemandmonitoring.mission.enums.MissionStaffRole.values()) {
            AssignStaffRequest staffRequest = new AssignStaffRequest();
            staffRequest.setStaffId(staffId);
            staffRequest.setAssignedRole(role.name());
            staffAssigned = missionService.assignStaff(staffAssigned.getId(), staffRequest);
        }

        assertThat(staffAssigned.getStatus()).isEqualTo(MissionStatus.READY);

        authenticate(staff);
        MissionResponse accepted = missionService.acceptMission(staffAssigned.getId(), staffId);
        entityManager.flush();
        entityManager.clear();

        // Acceptance records crew consent only; planning belongs to the later preflight flow.
        assertThat(accepted.getStatus()).isEqualTo(MissionStatus.READY);
        assertThat(accepted.getPlan()).isNull();
        assertThat(missionPlanRepository.findByMissionId(accepted.getId())).isEmpty();
        assertThat(countPlans(accepted.getId())).isZero();
        assertThat(countWaypoints(accepted.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from mission_staff_assignments where mission_id=? and response_status='ACCEPTED'",
                Long.class, accepted.getId())).isEqualTo((long) com.ondemandmonitoring.mission.enums.MissionStaffRole.values().length);
    }

    private User createActor(RoleCode roleCode) {
        User user = new User();
        user.setFullName("E2E " + roleCode);
        user.setEmail("operator-accept-" + roleCode.name().toLowerCase() + "-"
                + java.util.UUID.randomUUID() + "@example.com");
        user.setRole(roleRepository.findByCodeAndActiveTrue(roleCode).orElseThrow());
        user.setIsActive(true);
        return userRepository.saveAndFlush(user);
    }

    private void createAcceptedInvoice(Order order) {
        Instant now = Instant.now();
        BigDecimal total = BigDecimal.valueOf(1_000_000);
        BigDecimal deposit = BigDecimal.valueOf(200_000);

        Quote quote = new Quote();
        quote.setOrder(order);
        quote.setQuoteVersion(1);
        quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        quote.setPackagePrice(total);
        quote.setAdditionalAmount(BigDecimal.ZERO);
        quote.setDiscountAmount(BigDecimal.ZERO);
        quote.setAdjustmentAmount(BigDecimal.ZERO);
        quote.setTotalAmount(total);
        quote.setAcceptedAt(now);
        quote = quoteRepository.saveAndFlush(quote);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("E2E-" + java.util.UUID.randomUUID());
        invoice.setOrder(order);
        invoice.setQuote(quote);
        invoice.setTotalAmount(total);
        invoice.setDepositAmount(deposit);
        invoice.setPaidAmount(deposit);
        invoice.setRemainingAmount(total.subtract(deposit));
        invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        invoice.setIssuedAt(now);
        invoiceRepository.saveAndFlush(invoice);
    }

    private void authenticate(User user) {
        String role = user.getRole().getCode().name();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getId(), null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Point point(double x, double y) {
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(x, y));
        point.setSRID(4326);
        return point;
    }

    private long countPlans(String missionId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from mission_plans where mission_id = ?", Long.class, missionId);
    }

    private long countWaypoints(String missionId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from plan_waypoints pw
                join mission_plans mp on mp.id = pw.mission_plan_id
                where mp.mission_id = ?
                """, Long.class, missionId);
    }
}
