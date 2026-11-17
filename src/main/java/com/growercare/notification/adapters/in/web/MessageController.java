package com.growercare.notification.adapters.in.web;

import com.growercare.common.error.ValidationException;
import com.growercare.notification.application.MessageDispatch;
import com.growercare.notification.domain.MessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-NOT-01 R1–R7: {@code POST /internal/messages} for allow-listed service clients (gc-security, /internal/**). */
@RestController
class MessageController {

    /** The request body. Strings, so that unknown enum values become a 400 naming the field (R5). */
    record SendMessage(String channel, String recipient, String template, String language, String category,
                       Map<String, String> params) {
        @Override
        public String toString() {
            return "SendMessage[channel=%s, template=%s, language=%s]".formatted(channel, template, language);
        }
    }

    record Accepted(String messageId, String status) { }

    private final MessageDispatch dispatch;

    MessageController(MessageDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @Operation(summary = "Send a message (SPEC-NOT-01 R1)")
    @PostMapping("/internal/messages")
    ResponseEntity<Accepted> send(Authentication caller, @RequestBody SendMessage body,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        var request = MessageRequest.of(String.valueOf(caller.getPrincipal()), body.channel(), body.recipient(), body.template(), body.language(),
                body.category(), body.params(), parseKey(key));
        var result = dispatch.send(request);
        var response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(new Accepted(result.messageId().toString(), result.status().name()));
    }

    private static UUID parseKey(String key) {
        if (key == null || key.isBlank()) {
            throw ValidationException.of("Idempotency-Key", "is required");
        }
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw ValidationException.of("Idempotency-Key", "must be a UUID");
        }
    }
}
