package com.growercare.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.notification.support.NotificationApiTestBase;
import com.growercare.testsupport.OpenApiContract;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** SPEC-PLAT-01 R1/R3 (PLAT-E21/E22) for notification-service (the /dev mailbox is excluded, SPEC-NOT-01 §3); the export is committed to contracts/ (drift fails CI). */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class NotificationOpenApiContractIT extends NotificationApiTestBase {

    JsonNode api() throws Exception {
        return OpenApiContract.parse(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    static Stream<Arguments> operations() {
        return Stream.of(argumentSet("PLAT-E21 POST /internal/messages", "/internal/messages", "post"));
    }

    @ParameterizedTest
    @MethodSource("operations")
    void documentedOperation(String path, String method) throws Exception {
        assertThat(OpenApiContract.hasOperation(api(), path, method)).as("%s %s documented", method, path).isTrue();
    }

    @Test
    void platE22BearerSchemeAndContractExport() throws Exception {
        var api = api();
        assertThat(api.path("components").path("securitySchemes").path("bearer").path("scheme").asString()).isEqualTo("bearer");
        assertThat(api.path("security").toString()).contains("bearer");
        assertThat(api.path("paths").has("/dev/mailbox")).as("dev mailbox kept out of the contract").isFalse();
        assertThat(OpenApiContract.export(api, "notification-service")).exists();
    }
}
