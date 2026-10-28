package com.ondemandmonitoring.dashboard.repository;

import com.ondemandmonitoring.dashboard.dto.ManagerDashboardResponse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** PostgreSQL aggregation read model. Queries return only chart dimensions and measures. */
@Repository
@RequiredArgsConstructor
public class ManagerDashboardAnalyticsRepository {
    private final EntityManager entityManager;

    public long countOrders(Instant from, Instant to) {
        return number("select count(*) from orders where created_at >= :from and created_at < :to", from, to).longValue();
    }

    public BigDecimal totalCurrentQuoteValue(Instant from, Instant to) {
        return decimal("""
                select coalesce(sum(q.total_amount), 0)
                from quotes q
                where q.created_at >= :from and q.created_at < :to
                  and q.quote_version = (select max(q2.quote_version) from quotes q2 where q2.order_id = q.order_id)
                  and q.status in ('APPROVED','ACCEPTED_BY_CUSTOMER')
                """, from, to);
    }

    public List<ManagerDashboardResponse.DistributionPoint> orderStatuses(Instant from, Instant to) {
        return rows("select order_status, count(*) from orders where created_at >= :from and created_at < :to group by order_status order by count(*) desc",
                from, to, null).stream().map(r -> new ManagerDashboardResponse.DistributionPoint(
                String.valueOf(r[0]), ((Number) r[1]).longValue())).toList();
    }

    public List<ManagerDashboardResponse.ServiceDistributionPoint> services(Instant from, Instant to) {
        return rows("""
                select s.id, s.name, count(*) from orders o join services s on s.id=o.service_id
                where o.created_at >= :from and o.created_at < :to
                group by s.id, s.name order by count(*) desc, s.name asc limit 5
                """, from, to, null).stream().map(r -> new ManagerDashboardResponse.ServiceDistributionPoint(
                String.valueOf(r[0]), String.valueOf(r[1]), ((Number) r[2]).longValue())).toList();
    }

    public List<ManagerDashboardResponse.DistributionPoint> additionalServices(Instant from, Instant to) {
        return rows("""
                select pricing_status, count(*) from order_checklist_items
                where created_at >= :from and created_at < :to
                  and source_type in ('CUSTOMER_SELECTED','CUSTOMER_CUSTOM')
                group by pricing_status order by count(*) desc
                """, from, to, null).stream().map(r -> new ManagerDashboardResponse.DistributionPoint(
                String.valueOf(r[0]), ((Number) r[1]).longValue())).toList();
    }

    public ManagerDashboardResponse.PriceAdjustmentSummary priceAdjustments(Instant from, Instant to) {
        Object[] r = rows("""
                select count(*) filter (where pricing_status='APPROVED'),
                       count(*) filter (where pricing_status='ADJUSTED_AND_APPROVED')
                from order_checklist_items
                where created_at >= :from and created_at < :to
                  and source_type in ('CUSTOMER_SELECTED','CUSTOMER_CUSTOM')
                """, from, to, null).getFirst();
        return new ManagerDashboardResponse.PriceAdjustmentSummary(
                ((Number) r[0]).longValue(), ((Number) r[1]).longValue());
    }

    public List<ManagerDashboardResponse.DistributionPoint> missionStatuses() {
        @SuppressWarnings("unchecked")
        List<Object[]> values = entityManager.createNativeQuery(
                """
                select case
                    when status in ('CREATED','WAITING_DEPOSIT','RESOURCE_ASSIGNING',
                                    'WAITING_OPERATOR_ACCEPTANCE','PENDING_APPROVAL') then 'PLANNING'
                    when status = 'READY' and resource_prepare_status = 'READY' then 'READY'
                    when status = 'READY' then 'PLANNING'
                    when status = 'SCHEDULED' then 'SCHEDULED'
                    when status in ('CONNECTED','PREFLIGHT_CHECKING','READY_TO_FLY') then 'READY'
                    when status in ('IN_FLIGHT','IN_PROGRESS') then 'IN_FLIGHT'
                    when status in ('RETURNING','POSTFLIGHT_CHECKING','PENDING_REVIEW') then 'RETURNING'
                    when status = 'COMPLETED' then 'COMPLETED'
                    when status in ('FAILED','FAILED_PREFLIGHT') then 'FAILED'
                    else 'CANCELLED' end as stage,
                    count(*)
                from missions group by stage order by count(*) desc
                """).getResultList();
        return values.stream().map(r -> new ManagerDashboardResponse.DistributionPoint(
                String.valueOf(r[0]), ((Number) r[1]).longValue())).toList();
    }

    public List<ManagerDashboardResponse.MissionTrendPoint> missionTrend(Instant from, Instant to, String bucket) {
        return rows("""
                select date_trunc(:bucket, timezone('Asia/Ho_Chi_Minh', coalesce(completed_at, updated_at)))::date,
                       count(*) filter (where status='COMPLETED'),
                       count(*) filter (where status in ('FAILED','FAILED_PREFLIGHT'))
                from missions
                where status in ('COMPLETED','FAILED','FAILED_PREFLIGHT')
                  and coalesce(completed_at, updated_at) >= :from
                  and coalesce(completed_at, updated_at) < :to
                group by 1 order by 1
                """, from, to, bucket).stream().map(r -> new ManagerDashboardResponse.MissionTrendPoint(
                date(r[0]), ((Number) r[1]).longValue(), ((Number) r[2]).longValue())).toList();
    }

    private Number number(String sql, Instant from, Instant to) {
        return (Number) query(sql, from, to, null).getSingleResult();
    }

    private BigDecimal decimal(String sql, Instant from, Instant to) {
        return decimal(query(sql, from, to, null).getSingleResult());
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal amount ? amount : new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Instant from, Instant to, String bucket) {
        return query(sql, from, to, bucket).getResultList();
    }

    private jakarta.persistence.Query query(String sql, Instant from, Instant to, String bucket) {
        var query = entityManager.createNativeQuery(sql).setParameter("from", from).setParameter("to", to);
        if (bucket != null) query.setParameter("bucket", bucket);
        return query;
    }

    private LocalDate date(Object value) {
        if (value instanceof Date sqlDate) return sqlDate.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }
}
