package com.growercare.notification.architecture;

import com.growercare.testsupport.HexagonalRules;
import com.tngtech.archunit.lang.ArchRule;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-PLAT-01 R4–R9 for notification-service (rows = rules, named by spec ID). */
class ArchitectureTest {

    static Stream<Arguments> rules() {
        return HexagonalRules.rows();
    }

    @ParameterizedTest
    @MethodSource("rules")
    void architecture(ArchRule rule) {
        HexagonalRules.check(rule, "com.growercare.notification");
    }
}
