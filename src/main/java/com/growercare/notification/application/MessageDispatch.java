package com.growercare.notification.application;

import com.growercare.common.error.ConflictException;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.common.error.ErrorCode;
import com.growercare.notification.application.port.DeliveryStore;
import com.growercare.notification.application.port.DeliveryStore.Status;
import com.growercare.notification.application.port.MessageProvider;
import com.growercare.notification.domain.MessageRequest;
import com.growercare.notification.domain.TemplateRenderer;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** SPEC-NOT-01 R1–R6: render, dispatch through the channel's provider, log without content, idempotently. */
@Service
public class MessageDispatch {

    /** The message and whether it is an idempotent replay (R2). */
    public record Result(UUID messageId, Status status, boolean replayed) { }

    /** Raised when the provider fails, so the outer handler can log FAILED outside the rolled-back transaction. */
    private static final class ProviderFailure extends RuntimeException {
        ProviderFailure(Throwable cause) {
            super(cause);
        }
    }

    private final TemplateRenderer templates;
    private final ProviderRegistry providers;
    private final DeliveryStore store;
    private final TransactionTemplate tx;

    public MessageDispatch(TemplateRenderer templates, ProviderRegistry providers, DeliveryStore store, TransactionTemplate tx) {
        this.templates = templates;
        this.providers = providers;
        this.store = store;
        this.tx = tx;
    }

    public Result send(MessageRequest request) {
        var hash = request.requestHash();
        var previous = store.findIdempotency(request.idempotencyKey());
        if (previous.isPresent()) {
            return replay(previous.get(), request, hash);
        }
        var body = templates.render(request.template(), request.language(), request.params());   // R4 (422) before any write
        var provider = providers.forChannel(request.channel())
                .orElseThrow(() -> new DependencyUnavailableException("no " + request.channel() + " provider configured", null));
        var messageId = UUID.randomUUID();
        try {
            Boolean saved = tx.execute(status -> {
                store.log(messageId, request, Status.DISPATCHED);
                try {
                    provider.send(new MessageProvider.Dispatch(messageId, request.channel(), request.recipient(), request.template(),
                            request.language(), body));
                } catch (RuntimeException e) {
                    throw new ProviderFailure(e);
                }
                if (!store.saveIdempotency(request.idempotencyKey(), request.caller(), hash, messageId)) {
                    status.setRollbackOnly();      // a concurrent request with the same key won
                    return false;
                }
                return true;
            });
            if (Boolean.FALSE.equals(saved)) {
                return replay(store.findIdempotency(request.idempotencyKey()).orElseThrow(), request, hash);
            }
            return new Result(messageId, Status.DISPATCHED, false);
        } catch (ProviderFailure e) {
            tx.executeWithoutResult(status -> store.log(messageId, request, Status.FAILED));     // R6: logged, not delivered
            throw new DependencyUnavailableException(request.channel() + " provider", e.getCause());
        }
    }

    private static Result replay(DeliveryStore.IdempotencyRecord previous, MessageRequest request, byte[] hash) {
        if (!previous.caller().equals(request.caller()) || !Arrays.equals(previous.requestHash(), hash)) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was already used for a different request");
        }
        return new Result(previous.messageId(), Status.DISPATCHED, true);
    }

    /** Which provider serves which channel (SPEC-NOT-01 D2/D3); empty when none is configured. */
    public interface ProviderRegistry {
        Optional<MessageProvider> forChannel(MessageRequest.Channel channel);
    }
}
