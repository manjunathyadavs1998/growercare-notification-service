package com.growercare.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SPEC-NOT-01 D3 / R10 (NOT-E10): the dev mailbox and real providers never coexist. Phase 1 has no real provider, so
 * "mailbox" is the only accepted name, and only together with the dev mailbox.
 */
class ProviderConfigurationTest {

    @ParameterizedTest(name = "mailbox={0} sms={1} whatsapp={2} email={3} → starts: {4}")
    @CsvSource(nullValues = "NULL", textBlock = """
            # mailbox, sms,     whatsapp, email,   starts, message fragment
            true,      mailbox, mailbox,  mailbox, true,   NULL
            false,     NULL,    NULL,     NULL,    true,   NULL
            true,      twilio,  mailbox,  mailbox, false,  'GC_DEV_MAILBOX=true cannot be combined with GC_SMS_PROVIDER=twilio'
            true,      NULL,    mailbox,  mailbox, false,  'GC_SMS_PROVIDER must be mailbox'
            false,     mailbox, NULL,     NULL,    false,  'GC_SMS_PROVIDER=mailbox needs GC_DEV_MAILBOX=true'
            false,     twilio,  NULL,     NULL,    false,  'GC_SMS_PROVIDER=twilio is not an implemented provider'
            """)
    void providersAndTheDevMailboxAreValidatedAtStartup(boolean mailbox, String sms, String whatsapp, String email,
                                                        boolean starts, String message) {
        var props = new NotificationProperties(mailbox, sms, whatsapp, email);
        if (starts) {
            props.validate();
            assertThat(props.mailboxEnabled()).isEqualTo(mailbox);
        } else {
            assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
        }
    }
}
