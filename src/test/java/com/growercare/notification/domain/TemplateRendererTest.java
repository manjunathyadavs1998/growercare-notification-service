package com.growercare.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.common.error.BusinessRuleException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-NOT-01 D5, R1, R4 (NOT-E1/E2/E4): templates per language, rendered with the caller's parameters. */
class TemplateRendererTest {

    static final TemplateRenderer RENDERER = TemplateRenderer.fromClasspath();
    static final Map<String, String> PARAMS = Map.of("code", "482913", "minutes", "5");

    static Stream<Arguments> languages() {
        return Stream.of(
                argumentSet("NOT-E2 English", "en", "your verification code is 482913. It is valid for 5 minutes"),
                argumentSet("NOT-E2 Hindi", "hi", "सत्यापन कोड 482913 है। यह 5 मिनट"),
                argumentSet("NOT-E1 Marathi", "mr", "पडताळणी कोड 482913 आहे. तो 5 मिनिटांसाठी"),
                argumentSet("NOT-E2 Portuguese", "pt", "código de verificação é 482913. Ele é válido por 5 minutos"));
    }

    @ParameterizedTest
    @MethodSource("languages")
    void rendersTheOtpTemplateInEveryLanguage(String language, String expected) {
        var body = RENDERER.render("otp.activation", language, PARAMS);
        assertThat(body).startsWith("GrowerCare: ").contains(expected).doesNotContain("{", "}").doesNotEndWith("\n");
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                argumentSet("NOT-E4 unknown template", "otp.unknown", "mr", PARAMS, "notification.template-unknown"),
                argumentSet("NOT-E4 language without a text", "otp.activation", "ta", PARAMS, "notification.language-missing"),
                argumentSet("NOT-E4 placeholder without a parameter", "otp.activation", "mr", Map.of("minutes", "5"), "notification.param-missing"),
                argumentSet("NOT-E4 template name with path characters", "../secrets", "mr", PARAMS, "notification.template-unknown"));
    }

    @ParameterizedTest
    @MethodSource("refused")
    void unknownTemplatesLanguagesAndMissingParametersAreRefused(String template, String language, Map<String, String> params, String rule) {
        assertThatThrownBy(() -> RENDERER.render(template, language, params))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).properties()).containsEntry("rule", rule));
    }
}
