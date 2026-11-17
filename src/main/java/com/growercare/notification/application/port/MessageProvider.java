package com.growercare.notification.application.port;

import com.growercare.notification.domain.MessageRequest.Channel;
import java.util.UUID;

/**
 * A channel provider (SPEC-NOT-01 D2): SMS gateway, WhatsApp BSP, email. Phase 1 ships only the mailbox adapter, which
 * stores the message for local use instead of sending it. Any exception means "not delivered" (R6).
 */
public interface MessageProvider {

    /** The rendered message. The body may contain a code: never log it. */
    record Dispatch(UUID messageId, Channel channel, String recipient, String template, String language, String body) {
        @Override
        public String toString() {
            return "Dispatch[messageId=%s, channel=%s, template=%s]".formatted(messageId, channel, template);
        }
    }

    void send(Dispatch dispatch);
}
