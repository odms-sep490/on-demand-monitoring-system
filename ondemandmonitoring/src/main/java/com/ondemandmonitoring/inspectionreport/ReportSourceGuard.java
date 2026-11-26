package com.ondemandmonitoring.inspectionreport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ReportSourceGuard {
    private final JdbcTemplate db;
    public boolean referenced(String mediaId,boolean approvedOnly) {
        if(!Boolean.TRUE.equals(db.queryForObject("select to_regclass('inspection_reports') is not null and to_regclass('inspection_report_versions') is not null",Boolean.class)))return false;
        String query=approvedOnly
            ? "select exists(select 1 from inspection_reports where status in ('APPROVED','FINALIZED','DELIVERED') and jsonb_path_exists(document,'$.sections[*].images[*] ? (@.mediaId == $id)',jsonb_build_object('id',?::text)))"
            : "select exists(select 1 from inspection_report_versions where jsonb_path_exists(document,'$.sections[*].images[*] ? (@.mediaId == $id)',jsonb_build_object('id',?::text)))";
        return Boolean.TRUE.equals(db.queryForObject(query,Boolean.class,mediaId));
    }
}
