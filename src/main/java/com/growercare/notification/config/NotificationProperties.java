package com.growercare.notification.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code gc.notification.*} (SPEC-NOT-01 D2, D3, R10). Phase 1 implements one provider, {@code mailbox}, which is only
 * allowed together with the dev mailbox; the dev mailbox in turn needs every channel on {@code mailbox}. With the
 * mailbox off and no provider configured the service starts, and every send fails closed with 503.
 */
@ConfigurationProperties("gc.notification")
public record NotificationProperties(boolean devMailbox, String smsProvider, String whatsappProvider, String emailProvider) {

    public static final String MAILBOX = "mailbox";

    public NotificationProperties {
        smsProvider = blankToNull(smsProvider);
        whatsappProvider = blankToNull(whatsappProvider);
        emailProvider = blankToNull(emailProvider);
    }

    public boolean mailboxEnabled() {
        return devMailbox;
    }

    /** @throws IllegalStateException naming every conflicting setting */
    public void validate() {
        var providers = new LinkedHashMap<String, String>();
        providers.put("GC_SMS_PROVIDER", smsProvider);
        providers.put("GC_WHATSAPP_PROVIDER", whatsappProvider);
        providers.put("GC_EMAIL_PROVIDER", emailProvider);
        var problems = new ArrayList<String>();
        providers.forEach((name, value) -> {
            if (devMailbox) {
                if (value == null) {
                    problems.add(name + " must be " + MAILBOX + " while GC_DEV_MAILBOX=true");
                } else if (!MAILBOX.equals(value)) {
                    problems.add("GC_DEV_MAILBOX=true cannot be combined with " + name + "=" + value + " (a real provider)");
                }
            } else if (MAILBOX.equals(value)) {
                problems.add(name + "=" + MAILBOX + " needs GC_DEV_MAILBOX=true");
            } else if (value != null) {
                problems.add(name + "=" + value + " is not an implemented provider");
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid notification provider configuration: " + String.join("; ", problems));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
