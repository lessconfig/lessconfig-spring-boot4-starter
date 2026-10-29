package com.lessconfig.spring.boot;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses remote documents using Spring Boot loaders and the application's accepted profiles.
 */
@NullMarked
final class LessConfigDocumentParser {
    /**
     * Profile set determined by Boot, including default profiles and expanded groups.
     */
    private final List<String> profiles;

    /**
     * Creates a parser whose profile selection remains stable during refresh.
     *
     * @param profiles profiles accepted by Spring Boot at startup
     */
    LessConfigDocumentParser(List<String> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    /**
     * Parses all documents before merging matching documents in source order.
     * Later matching documents override earlier documents. Profile selectors are consumed here.
     *
     * @param resource remote file identity
     * @param content  decoded configuration text
     * @return complete flattened property map
     * @throws IOException      if a document cannot be read
     * @throws RuntimeException if syntax or a profile expression is invalid
     */
    Map<String, Object> parse(LessConfigConfigDataResource resource, String content) throws IOException {
        boolean properties = resource.getKey().endsWith(".properties");
        PropertySourceLoader loader = properties ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
        byte[] bytes = (properties ? escapeUnicode(content) : content).getBytes(StandardCharsets.UTF_8);
        List<PropertySource<?>> documents = loader.load(resource.toString(), new ByteArrayResource(bytes, resource.toString()));
        Map<String, Object> result = new LinkedHashMap<>();
        for (PropertySource<?> document : documents) {
            Binder binder = new Binder(ConfigurationPropertySources.from(List.of(document)));
            String[] expressions = binder.bind("spring.config.activate.on-profile", String[].class).orElse(null);
            if (expressions != null && !Profiles.of(expressions).matches(profiles::contains)) {
                continue;
            }
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) document;
            for (String name : enumerable.getPropertyNames()) {
                if (!name.equals("spring.config.activate.on-profile")
                        && !name.startsWith("spring.config.activate.on-profile[")) {
                    Object value = document.getProperty(name);
                    if (value != null) {
                        result.put(name, value);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Encodes Unicode text for Boot's ISO-8859-1 properties reader without losing characters.
     * Existing escapes remain intact; a backslash escaping a Unicode character is normalized.
     *
     * @param text decoded properties text supplied by the SDK
     * @return equivalent ASCII properties text
     */
    private static String escapeUnicode(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int backslashes = 0;
        for (char character : text.toCharArray()) {
            if (character > 127) {
                if ((backslashes & 1) != 0) {
                    result.setLength(result.length() - 1);
                }
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
            backslashes = character == '\\' ? backslashes + 1 : 0;
        }
        return result.toString();
    }
}
