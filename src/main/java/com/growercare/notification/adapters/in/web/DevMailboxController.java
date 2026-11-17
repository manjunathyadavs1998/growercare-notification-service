package com.growercare.notification.adapters.in.web;

import com.growercare.notification.application.port.DeliveryStore;
import com.growercare.notification.application.port.DeliveryStore.MailboxMessage;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * SPEC-NOT-01 D3, R8: LOCAL ONLY. Lists the messages the mailbox provider stored, so Playwright and people can read
 * one-time codes. Registered only with {@code GC_DEV_MAILBOX=true}; otherwise the routes do not exist (R9).
 */
@RestController
@ConditionalOnProperty(name = "gc.notification.dev-mailbox", havingValue = "true")
class DevMailboxController {

    static final int LIMIT = 50;

    record Mailbox(List<MailboxMessage> items) { }

    private final DeliveryStore store;

    DevMailboxController(DeliveryStore store) {
        this.store = store;
    }

    @GetMapping(path = "/dev/mailbox", produces = MediaType.APPLICATION_JSON_VALUE)
    Mailbox mailbox(@RequestParam String recipient) {
        return new Mailbox(store.mailbox(recipient, LIMIT));
    }

    @GetMapping(path = "/dev/mailbox/ui", produces = MediaType.TEXT_HTML_VALUE)
    String page(@RequestParam String recipient) {
        var rows = new StringBuilder();
        for (var m : store.mailbox(recipient, LIMIT)) {
            rows.append("<tr><td>").append(m.createdAt()).append("</td><td>").append(m.channel())
                    .append("</td><td>").append(HtmlUtils.htmlEscape(m.template())).append(" / ").append(HtmlUtils.htmlEscape(m.language()))
                    .append("</td><td>").append(HtmlUtils.htmlEscape(m.body())).append("</td></tr>\n");
        }
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>Dev mailbox</title>
                <style>body{font-family:sans-serif;margin:16px}td,th{border-bottom:1px solid #ccc;padding:4px 8px;text-align:left}</style>
                </head><body><h1>Dev mailbox: %s</h1><p>Local only. Messages are stored here instead of being sent.</p>
                <table><tr><th>Time</th><th>Channel</th><th>Template</th><th>Body</th></tr>
                %s</table></body></html>
                """.formatted(HtmlUtils.htmlEscape(recipient), rows);
    }
}
