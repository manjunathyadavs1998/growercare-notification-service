package com.growercare.notification.domain;

import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ValidationException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A message to send (SPEC-NOT-01 R1, R4, R5). The recipient and the parameters (an OTP code) are secrets:
 * {@link #toString()} never prints them.
 */
public record MessageRequest(String caller, Channel channel, String recipient, String template, String language,
                             Category category, Map<String, String> params, UUID idempotencyKey) {

    public enum Channel { SMS, WHATSAPP, EMAIL }

    public enum Category { OTP, TRANSACTIONAL, MARKETING }

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    public MessageRequest {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** R5 shape checks (400) and R4 category rule (422, Phase 1 sends OTPs only). */
    public static MessageRequest of(String caller, String channel, String recipient, String template, String language,
                                    String category, Map<String, String> params, UUID key) {
        var ch = parseEnum(Channel.class, channel, "channel");
        if (recipient == null || !(ch == Channel.EMAIL ? EMAIL : E164).matcher(recipient).matches()) {
            throw ValidationException.of("recipient", ch == Channel.EMAIL ? "must be an email address" : "must be an E.164 number, e.g. +919876543210");
        }
        if (template == null || template.isBlank()) {
            throw ValidationException.of("template", "is required");
        }
        if (language == null || language.isBlank()) {
            throw ValidationException.of("language", "is required");
        }
        var cat = parseEnum(Category.class, category, "category");
        if (cat != Category.OTP) {
            throw new BusinessRuleException("notification.category-unsupported", "Phase 1 sends OTP messages only");
        }
        return new MessageRequest(caller, ch, recipient, template, language, cat, params, key);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw ValidationException.of(field, "is not one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    /** D4: what the delivery log may show — the last 4 digits of a number, the first letter and domain of an email. */
    public String recipientMasked() {
        if (channel == Channel.EMAIL) {
            int at = recipient.indexOf('@');
            return recipient.charAt(0) + "***" + recipient.substring(at);
        }
        return "******" + recipient.substring(recipient.length() - 4);
    }

    /** D6: SHA-256 over every field (length-prefixed, parameters sorted), so a replay must be the same request. */
    public byte[] requestHash() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var fields = new java.util.ArrayList<>(java.util.List.of(caller, channel.name(), recipient, template, language, category.name()));
            new TreeMap<>(params).forEach((k, v) -> {
                fields.add(k);
                fields.add(v);
            });
            for (var field : fields) {
                var bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "MessageRequest[caller=%s, channel=%s, template=%s, language=%s, category=%s, key=%s]"
                .formatted(caller, channel, template, language, category, idempotencyKey);
    }
}
