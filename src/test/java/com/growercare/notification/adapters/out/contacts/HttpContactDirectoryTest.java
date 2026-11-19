package com.growercare.notification.adapters.out.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.growercare.common.error.DependencyUnavailableException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/** SPEC-NOT-02 D4/R48: contacts from grower-service and IAM with the service token; 404 → empty, failures → 503. */
class HttpContactDirectoryTest {

    static final ClientRegistration CLIENT = ClientRegistration.withRegistrationId(HttpContactDirectory.REGISTRATION)
            .clientId("notification-service").clientSecret("s").tokenUri("https://kc.test/token")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).build();
    static final OAuth2AuthorizedClientManager TOKENS = request -> new OAuth2AuthorizedClient(CLIENT, "notification-service",
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "svc-token", Instant.now(), Instant.now().plusSeconds(300)));

    MockRestServiceServer growerServer;
    MockRestServiceServer iamServer;
    HttpContactDirectory directory;

    @BeforeEach
    void setUp() {
        var grower = RestClient.builder().baseUrl("http://grower.test");
        var iam = RestClient.builder().baseUrl("http://iam.test");
        growerServer = MockRestServiceServer.bindTo(grower).build();
        iamServer = MockRestServiceServer.bindTo(iam).build();
        directory = new HttpContactDirectory(grower.build(), iam.build(), TOKENS);
    }

    static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    /** Which service, the path it is asked, how it answers, and what the directory returns ("phone|language", empty or 503). */
    static Stream<Arguments> lookups() {
        Function<HttpContactDirectory, Optional<?>> grower = d -> d.grower(110165911145454593L);
        Function<HttpContactDirectory, Optional<?>> staff = d -> d.staff(102);
        return Stream.of(
                argumentSet("NOT-E40 grower contact", "grower", "/internal/growers/110165911145454593/contact", grower,
                        json("{\"phone\":\"+919876543210\",\"language\":\"mr\",\"status\":\"ACTIVE\"}"), "+919876543210|mr"),
                argumentSet("NOT-E43 staff contact", "iam", "/internal/users/by-id/102/contact", staff,
                        json("{\"userId\":\"102\",\"email\":\"agro.in@growercare.test\",\"displayName\":\"Vikram Agro\",\"language\":\"en\"}"),
                        "agro.in@growercare.test|en"),
                argumentSet("NOT-E47 grower contact 404", "grower", "/internal/growers/110165911145454593/contact", grower,
                        withResourceNotFound(), "empty"),
                argumentSet("NOT-E47 staff contact 404", "iam", "/internal/users/by-id/102/contact", staff, withResourceNotFound(), "empty"),
                argumentSet("NOT-E47 grower-service 5xx", "grower", "/internal/growers/110165911145454593/contact", grower,
                        withServerError(), "503"),
                argumentSet("NOT-E47 IAM 5xx", "iam", "/internal/users/by-id/102/contact", staff, withServerError(), "503"),
                argumentSet("NOT-R48 a contact without a phone", "grower", "/internal/growers/110165911145454593/contact", grower,
                        json("{\"language\":\"mr\"}"), "503"),
                argumentSet("NOT-R48 an unreadable body", "iam", "/internal/users/by-id/102/contact", staff, json("not json"), "503"));
    }

    @ParameterizedTest
    @MethodSource("lookups")
    void lookup(String service, String path, Function<HttpContactDirectory, Optional<?>> call, ResponseCreator answer, String expected) {
        var server = service.equals("grower") ? growerServer : iamServer;
        server.expect(requestTo((service.equals("grower") ? "http://grower.test" : "http://iam.test") + path))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(answer);
        if (expected.equals("503")) {
            assertThatThrownBy(() -> call.apply(directory)).isInstanceOf(DependencyUnavailableException.class);
        } else {
            var result = call.apply(directory).map(contact -> switch (contact) {
                case HttpContactDirectory.GrowerContact g -> g.phone() + "|" + g.language();
                case HttpContactDirectory.StaffContact s -> s.email() + "|" + s.language();
                default -> throw new IllegalStateException();
            });
            assertThat(result.orElse("empty")).isEqualTo(expected);
        }
        server.verify();
    }

    static Stream<Arguments> tokenFailures() {
        OAuth2AuthorizedClientManager refused = request -> {
            throw new ClientAuthorizationException(new OAuth2Error("invalid_client"), HttpContactDirectory.REGISTRATION);
        };
        OAuth2AuthorizedClientManager none = request -> null;
        return Stream.of(
                argumentSet("NOT-R48 Keycloak refuses the token", refused),
                argumentSet("NOT-R48 no token at all", none));
    }

    @ParameterizedTest
    @MethodSource("tokenFailures")
    void withoutATokenTheAnswerIsUnknown(OAuth2AuthorizedClientManager tokens) {
        var failing = new HttpContactDirectory(RestClient.create("http://grower.test"), RestClient.create("http://iam.test"), tokens);
        assertThatThrownBy(() -> failing.grower(1)).isInstanceOf(DependencyUnavailableException.class);
    }
}
