package com.growercare.notification.config;

import com.growercare.notification.adapters.out.contacts.HttpContactDirectory;
import com.growercare.notification.application.CaseNotifier;
import com.growercare.notification.application.port.ContactDirectory;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/** SPEC-NOT-02 wiring: contact lookups with the service token (D4), links (D7), clock and the release job (D5/D6). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CaseNoticesConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CaseNotifier.Links caseLinks(@Value("${gc.case-notices.grower-app-url}") String growerApp,
                                 @Value("${gc.case-notices.staff-app-url}") String staffApp) {
        return new CaseNotifier.Links(growerApp, staffApp);
    }

    @Bean
    ContactDirectory contactDirectory(@Value("${gc.grower.base-url}") String growerBaseUrl, @Value("${gc.iam.base-url}") String iamBaseUrl,
                                      OAuth2AuthorizedClientManager tokens) {
        return new HttpContactDirectory(rest(growerBaseUrl), rest(iamBaseUrl), tokens);
    }

    /** Client credentials for Keycloak client {@code notification-service} (registration of the same name). */
    @Bean
    OAuth2AuthorizedClientManager serviceTokenManager(ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    private static RestClient rest(String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
