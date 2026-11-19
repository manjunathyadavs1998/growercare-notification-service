package com.growercare.notification.adapters.out.contacts;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.notification.application.port.ContactDirectory;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * SPEC-NOT-02 D4: reads contacts at send time with notification-service's client-credentials token (Keycloak client
 * {@code notification-service}, audience {@code gc-internal}): grower-service {@code GET /internal/growers/{id}/contact}
 * (SPEC-GRW-07) and IAM {@code GET /internal/users/by-id/{id}/contact} (SPEC-IAM-05). 404 → empty; anything else that
 * prevents an answer → {@link DependencyUnavailableException} (R48: retried, then dead-lettered).
 */
public final class HttpContactDirectory implements ContactDirectory {

    public static final String REGISTRATION = "notification-service";

    private final RestClient grower;
    private final RestClient iam;
    private final OAuth2AuthorizedClientManager tokens;

    public HttpContactDirectory(RestClient grower, RestClient iam, OAuth2AuthorizedClientManager tokens) {
        this.grower = grower;
        this.iam = iam;
        this.tokens = tokens;
    }

    @Override
    public Optional<GrowerContact> grower(long growerId) {
        return read(grower, "grower-service", "/internal/growers/{id}/contact", growerId,
                body -> new GrowerContact(required(body, "phone"), required(body, "language")));
    }

    @Override
    public Optional<StaffContact> staff(long userId) {
        return read(iam, "identity-access-service", "/internal/users/by-id/{id}/contact", userId,
                body -> new StaffContact(required(body, "email"), required(body, "language")));
    }

    private <T> Optional<T> read(RestClient rest, String service, String path, long id, Function<JsonNode, T> mapper) {
        try {
            var body = rest.get().uri(path, id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new IllegalStateException("empty contact body");
            }
            return Optional.of(mapper.apply(body));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw new DependencyUnavailableException(service, e);
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException e) {   // network, token, unreadable body
            throw new DependencyUnavailableException(service, e);
        }
    }

    private static String required(JsonNode body, String field) {
        var node = body.get(field);
        if (node == null || node.isNull() || node.asString().isBlank()) {
            throw new IllegalStateException("contact without " + field);
        }
        return node.asString();
    }

    private String accessToken() {
        var request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION).principal(REGISTRATION).build();
        var client = tokens.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("No client-credentials token for " + REGISTRATION);
        }
        return client.getAccessToken().getTokenValue();
    }
}
