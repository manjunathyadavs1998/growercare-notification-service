package com.growercare.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.notification.application.port.ContactDirectory.GrowerContact;
import com.growercare.notification.application.port.ContactDirectory.StaffContact;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-NOT-02 R49: contact objects never print the phone or the email (logs, exceptions, debug output). */
class ContactHygieneTest {

    static Stream<Arguments> contacts() {
        return Stream.of(
                argumentSet("NOT-R49 grower contact", new GrowerContact("+919876543210", "mr"), "9876543210"),
                argumentSet("NOT-R49 staff contact", new StaffContact("agro.in@growercare.test", "en"), "agro.in"));
    }

    @ParameterizedTest
    @MethodSource("contacts")
    void contactsHideTheirAddress(Object contact, String secret) {
        assertThat(contact.toString()).doesNotContain(secret).contains("***");
    }
}
