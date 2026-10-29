package com.lessconfig.spring.boot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies format handling and document selection without network or application context state.
 */
class LessConfigDocumentParserTest {
    /**
     * YAML retains nested values, lists, Unicode text and last-document precedence.
     */
    @Test
    void parsesNestedYamlAndMultipleDocuments() throws Exception {
        Map<String, Object> values = parse("app.yaml", """
                app:
                  name: 测试
                  servers: [one, two]
                  port: 8080
                ---
                app:
                  port: 9090
                """, "default");
        assertThat(values).containsEntry("app.name", "测试")
                .containsEntry("app.servers[0]", "one")
                .containsEntry("app.servers[1]", "two")
                .containsEntry("app.port", 9090);
    }

    /**
     * Unicode properties preserve escaped characters, continuations and explicit Unicode escapes.
     */
    @Test
    void preservesPropertiesTextAndEscapes() throws Exception {
        Map<String, Object> values = parse("app.properties",
                "name=中文😀\nescaped=\\u4e2d\\u6587\ncontinued=one\\\n two\n"
                        + "literal=\\中\nslash=\\\\中\nkey\\ with\\ spaces=value\n", "default");
        assertThat(values).containsEntry("name", "中文😀")
                .containsEntry("escaped", "中文").containsEntry("continued", "onetwo")
                .containsEntry("literal", "中").containsEntry("slash", "\\中")
                .containsEntry("key with spaces", "value");
    }

    /**
     * Profile expressions select whole documents and later base documents still take precedence.
     */
    @Test
    void selectsYamlDocumentsUsingSpringProfileExpressions() throws Exception {
        Map<String, Object> values = parse("app.yml", """
                value: base
                ---
                spring.config.activate.on-profile: "prod & !test"
                value: production
                production-only: yes
                ---
                spring.config.activate.on-profile: "test | dev"
                value: development
                development-only: yes
                ---
                value: last
                """, "prod");
        assertThat(values).containsEntry("value", "last").containsKey("production-only")
                .doesNotContainKeys("development-only", "spring.config.activate.on-profile");
    }

    /**
     * A YAML selector list has OR semantics, matching Spring's profile expression API.
     */
    @Test
    void supportsSelectorLists() throws Exception {
        assertThat(parse("app.yml", """
                spring:
                  config:
                    activate:
                      on-profile: [dev, prod]
                enabled: true
                """, "prod")).containsEntry("enabled", true)
                .doesNotContainKey("spring.config.activate.on-profile[0]");
    }

    /**
     * Properties document separators use the same profile selection as YAML.
     */
    @Test
    void supportsPropertiesDocumentsAndDefaultProfile() throws Exception {
        assertThat(parse("app.properties", "value=base\n#---\n"
                + "spring.config.activate.on-profile=default\nvalue=default-value\n", "default"))
                .containsEntry("value", "default-value");
    }

    /**
     * An empty document is a valid empty configuration.
     */
    @Test
    void acceptsEmptyFiles() throws Exception {
        assertThat(parse("empty.yml", "", "default")).isEmpty();
        assertThat(parse("empty.properties", "", "default")).isEmpty();
    }

    /**
     * Invalid content must fail before any property snapshot can be replaced.
     */
    @Test
    void rejectsMalformedYamlPropertiesAndProfileExpressions() {
        assertThatThrownBy(() -> parse("app.yml", "items: [", "default")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parse("app.yml", "key: one\nkey: two\n", "default")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parse("app.properties", "key=\\uZZZZ", "default"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parse("app.yml", "spring.config.activate.on-profile: 'prod & !'", "prod"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Parses one resource under a specified profile set.
     *
     * @param key      configuration file name
     * @param content  remote text
     * @param profiles accepted startup profiles
     * @return flattened selected properties
     * @throws Exception if content cannot be parsed
     */
    private Map<String, Object> parse(String key, String content, String... profiles) throws Exception {
        return new LessConfigDocumentParser(List.of(profiles))
                .parse(new LessConfigConfigDataResource("payment/prod/" + key, false), content);
    }
}
