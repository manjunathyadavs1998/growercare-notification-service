package com.growercare.notification.application.port;

import java.util.Optional;

/**
 * SPEC-NOT-02 D4: contacts are read at send time, never stored. Empty when the person has no contact (grower-service or
 * IAM answers 404: inactive, deleted, no email); {@code DependencyUnavailableException} when the answer is unknown.
 */
public interface ContactDirectory {

    /** A grower's phone (E.164) and language (SPEC-GRW-07). The phone never appears in {@link #toString()} (R49). */
    record GrowerContact(String phone, String language) {
        @Override
        public String toString() {
            return "GrowerContact[phone=***, language=" + language + "]";
        }
    }

    /** A staff or partner user's email and language (SPEC-IAM-05). The email never appears in {@link #toString()}. */
    record StaffContact(String email, String language) {
        @Override
        public String toString() {
            return "StaffContact[email=***, language=" + language + "]";
        }
    }

    Optional<GrowerContact> grower(long growerId);

    Optional<StaffContact> staff(long userId);
}
