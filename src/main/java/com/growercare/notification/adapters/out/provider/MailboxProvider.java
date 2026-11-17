package com.growercare.notification.adapters.out.provider;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.notification.application.port.MessageProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SPEC-NOT-01 D2/D3: LOCAL ONLY. Stores the rendered message in {@code notify.dev_mailbox} instead of sending it. Only
 * created when {@code GC_DEV_MAILBOX=true} (see {@code NotificationConfig}); never coexists with a real provider (R10).
 * Runs inside the dispatch transaction, so a failed dispatch leaves no mailbox row.
 */
public class MailboxProvider implements MessageProvider {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    public MailboxProvider(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public void send(Dispatch d) {
        jdbc.sql(sql.get("notify.insertMailbox"))
                .param("messageId", d.messageId())
                .param("channel", d.channel().name())
                .param("recipient", d.recipient())
                .param("template", d.template())
                .param("language", d.language())
                .param("body", d.body())
                .update();
    }
}
