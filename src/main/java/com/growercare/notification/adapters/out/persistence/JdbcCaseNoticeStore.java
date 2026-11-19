package com.growercare.notification.adapters.out.persistence;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.notification.application.port.CaseNoticeStore;
import com.growercare.notification.domain.GrowerNotice;
import com.growercare.notification.domain.GrowerWindow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SPEC-NOT-02 §4 over JdbcClient and named SQL ({@code sql/postgres/notices.sql}). */
@Repository
class JdbcCaseNoticeStore implements CaseNoticeStore {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcCaseNoticeStore(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public void saveCaseRef(CaseRef ref) {
        jdbc.sql(sql.get("notices.saveCaseRef"))
                .param("caseId", ref.caseId())
                .param("growerId", ref.growerId())
                .param("countryCode", ref.countryCode())
                .update();
    }

    @Override
    public Optional<CaseRef> caseRef(long caseId) {
        return jdbc.sql(sql.get("notices.caseRef")).param("caseId", caseId)
                .query((rs, n) -> new CaseRef(rs.getLong("case_id"), rs.getLong("grower_id"), rs.getString("country_code")))
                .optional();
    }

    @Override
    public Map<String, Boolean> visibleStatuses() {
        var statuses = new LinkedHashMap<String, Boolean>();
        jdbc.sql(sql.get("notices.visibleStatuses")).query(rs -> {
            statuses.put(rs.getString("status_code"), rs.getBoolean("closes"));
        });
        return statuses;
    }

    @Override
    public Optional<String> statusLabel(String statusCode, String language) {
        return jdbc.sql(sql.get("notices.statusLabel")).param("status", statusCode).param("language", language)
                .query(String.class).optional();
    }

    @Override
    public ZoneId timeZone(String countryCode) {
        return jdbc.sql(sql.get("notices.timeZone")).param("countryCode", countryCode).query(String.class).optional()
                .map(ZoneId::of)
                .orElseThrow(() -> new IllegalStateException("no time zone for country " + countryCode));
    }

    @Override
    public Optional<GrowerWindow> lockWindow(long caseId) {
        return jdbc.sql(sql.get("notices.lockWindow")).param("caseId", caseId).query(JdbcCaseNoticeStore::window).optional();
    }

    @Override
    public void saveWindow(long caseId, GrowerWindow w) {
        jdbc.sql(sql.get("notices.saveWindow"))
                .param("caseId", caseId)
                .param("windowEndsAt", utc(w.windowEndsAt()))
                .param("pendingCount", w.pendingCount())
                .param("pendingTemplate", w.pending() == null ? null : w.pending().template())
                .param("pendingStatus", w.pending() == null ? null : w.pending().statusCode())
                .param("dueAt", utc(w.dueAt()))
                .param("batchSeq", w.batchSeq())
                .update();
    }

    @Override
    public List<Long> dueCases(Instant now, int limit) {
        return jdbc.sql(sql.get("notices.dueCases")).param("now", utc(now)).param("limit", limit).query(Long.class).list();
    }

    @Override
    public Optional<GrowerWindow> lockDueWindow(long caseId, Instant now) {
        return jdbc.sql(sql.get("notices.lockDueWindow")).param("caseId", caseId).param("now", utc(now))
                .query(JdbcCaseNoticeStore::window).optional();
    }

    private static GrowerWindow window(ResultSet rs, int row) throws SQLException {
        int pending = rs.getInt("pending_count");
        var template = rs.getString("pending_template");
        GrowerNotice notice = template == null ? null : new GrowerNotice(template, rs.getString("pending_status"), 1);
        return new GrowerWindow(instant(rs, "window_ends_at"), pending, notice, instant(rs, "due_at"), rs.getInt("batch_seq"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
