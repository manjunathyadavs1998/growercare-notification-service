package com.growercare.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SPEC-PLAT-06 R77/R78 (PLAT-E90): configuration comes from the environment only. The main application*.yml files
 * hold no hosts, URLs or credentials, environment-specific placeholders have no defaults, there is no local profile
 * with credentials, and .env.example documents every variable the service reads.
 */
class ConfigurationHygieneTest {

    static final Path RESOURCES = Path.of("src", "main", "resources");
    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");
    /** Variables that are environment-specific: never a default in application.yml. */
    static final Pattern ENVIRONMENT_SPECIFIC = Pattern.compile("^(DB_URL|DB_APP_USER|DB_APP_PASSWORD|GC_CDC_ROLE|OIDC_.*|"
            + "GC_INTERNAL_CLIENTS|IAM_.*|REDIS_HOST|REDIS_PASSWORD|KAFKA_BOOTSTRAP_SERVERS|GROWER_BASE_URL|SERVICE_CLIENT_.*|"
            + "GC_GROWER_APP_URL|GC_STAFF_APP_URL)$");
    /** Empty in AWS (regional endpoint + IRSA): may default to empty, never to a value. */
    static final Pattern AWS_OPTIONAL = Pattern.compile("^S3_(ENDPOINT|PUBLIC_ENDPOINT|ACCESS_KEY|SECRET_KEY)$");

    static String mainConfig() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES)) {
            var all = new StringBuilder();
            for (var file : files.filter(f -> f.getFileName().toString().matches("application.*\\.ya?ml")).toList()) {
                all.append(Files.readString(file)).append('\n');
            }
            return all.toString();
        }
    }

    @ParameterizedTest(name = "PLAT-E90 no {0} in application*.yml")
    @CsvSource(textBlock = """
            # what,               regex
            localhost,            localhost
            an http(s) URL,       https?://
            a JDBC URL,           jdbc:
            a literal password,   (?m)^\\s*password:\\s*(?!\\$\\{)\\S
            a literal secret,     (?m)^\\s*(client-)?secret:\\s*(?!\\$\\{)\\S
            """)
    void noEnvironmentValuesInConfig(String what, String regex) throws IOException {
        assertThat(Pattern.compile(regex).matcher(mainConfig()).find()).as(what).isFalse();
    }

    @Test
    void environmentSpecificPlaceholdersHaveNoDefault() throws IOException {
        var withDefault = new TreeSet<String>();
        var matcher = PLACEHOLDER.matcher(mainConfig());
        while (matcher.find()) {
            if (ENVIRONMENT_SPECIFIC.matcher(matcher.group(1)).matches() && matcher.group(2) != null) {
                withDefault.add(matcher.group(1));
            }
            if (AWS_OPTIONAL.matcher(matcher.group(1)).matches() && matcher.group(2) != null && !matcher.group(2).equals(":")) {
                withDefault.add(matcher.group(1) + " (only an empty default is allowed)");
            }
        }
        assertThat(withDefault).isEmpty();
    }

    @Test
    void noLocalProfileWithCredentials() {
        assertThat(RESOURCES.resolve("application-local.yml")).doesNotExist();
    }

    @Test
    void envExampleDocumentsEveryVariable() throws IOException {
        Set<String> used = new TreeSet<>();
        var matcher = PLACEHOLDER.matcher(mainConfig());
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        var example = Files.readString(Path.of(".env.example"));
        assertThat(used).allSatisfy(variable -> assertThat(example).contains(variable + "="));
    }
}
