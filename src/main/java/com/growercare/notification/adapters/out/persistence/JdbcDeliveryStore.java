package com.growercare.notification.adapters.out.persistence;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.notification.application.port.DeliveryStore;
import com.growercare.notification.domain.MessageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SPEC-NOT-01 §4 over JdbcClient and named SQL ({@code sql/postgres/notify.sql}). */
@Repository
class JdbcDeliveryStore implements DeliveryStore {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcDeliveryStore(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public void log(UUID messageId, MessageRequest request, Status status) {
        jdbc.sql(sql.get("notify.log"))
                .param("messageId", messageId)
                .param("caller", request.caller())
                .param("channel", request.channel().name())
                .param("template", request.template())
                .param("language", request.language())
                .param("category", request.category().name())
                .param("recipientMasked", request.recipientMasked())
                .param("status", status.name())
                .update();
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(UUID key) {
        return jdbc.sql(sql.get("notify.findIdempotency")).param("key", key)
                .query((rs, n) -> new IdempotencyRecord(rs.getObject("idempotency_key", UUID.class), rs.getString("caller"),
                        rs.getBytes("request_hash"), rs.getObject("message_id", UUID.class)))
                .optional();
    }

    @Override
    public boolean saveIdempotency(UUID key, String caller, byte[] requestHash, UUID messageId) {
        return jdbc.sql(sql.get("notify.insertIdempotency"))
                .param("key", key).param("caller", caller).param("requestHash", requestHash).param("messageId", messageId)
                .update() == 1;
    }

    @Override
    public List<MailboxMessage> mailbox(String recipient, int limit) {
        return jdbc.sql(sql.get("notify.mailbox")).param("recipient", recipient).param("limit", limit)
                .query((rs, n) -> new MailboxMessage(rs.getObject("message_id", UUID.class), rs.getString("channel"),
                        rs.getString("recipient"), rs.getString("template"), rs.getString("language"), rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
