package com.filetransfer.notification.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    @DisplayName("null template returns null")
    void nullTemplate_returnsNull() {
        assertThat(renderer.render(null, Map.of("name", "World"))).isNull();
    }

    @Test
    @DisplayName("null variables returns template as-is")
    void nullVariables_returnsTemplateAsIs() {
        String template = "Hello ${name}";
        assertThat(renderer.render(template, null)).isEqualTo(template);
    }

    @Test
    @DisplayName("empty variables returns template as-is")
    void emptyVariables_returnsTemplateAsIs() {
        String template = "Hello ${name}";
        assertThat(renderer.render(template, Collections.emptyMap())).isEqualTo(template);
    }

    @Test
    @DisplayName("single variable substitution")
    void singleVariable() {
        String result = renderer.render("Hello ${name}", Map.of("name", "World"));
        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("multiple variable substitution")
    void multipleVariables() {
        String result = renderer.render(
                "${greeting} ${name}!",
                Map.of("greeting", "Hello", "name", "World")
        );
        assertThat(result).isEqualTo("Hello World!");
    }

    @Test
    @DisplayName("missing variable replaced with empty string")
    void missingVariable_replacedWithEmpty() {
        String result = renderer.render("Hello ${unknown}", Map.of("name", "World"));
        assertThat(result).isEqualTo("Hello ");
    }

    @Test
    @DisplayName("no placeholders returns template unchanged")
    void noPlaceholders_returnsUnchanged() {
        String template = "No placeholders here";
        assertThat(renderer.render(template, Map.of("name", "World"))).isEqualTo(template);
    }

    @Test
    @DisplayName("special characters in values are properly escaped")
    void specialCharactersEscaped() {
        // Dollar signs and backslashes are special in regex replacement
        String result = renderer.render(
                "Amount: ${amount}",
                Map.of("amount", "$100.00")
        );
        assertThat(result).isEqualTo("Amount: $100.00");
    }

    @Test
    @DisplayName("regex metacharacters in values are properly escaped")
    void regexMetacharsEscaped() {
        String result = renderer.render(
                "Pattern: ${pattern}",
                Map.of("pattern", "file(1).txt [backup]")
        );
        assertThat(result).isEqualTo("Pattern: file(1).txt [backup]");
    }
}
