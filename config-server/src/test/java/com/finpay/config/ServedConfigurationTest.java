package com.finpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the served configuration files themselves.
 *
 * <p>These files are handed to every service in the platform and are committed to Git, so a
 * hard-coded credential here would leak platform-wide. This test fails the build if a sensitive-
 * looking key ever gains a literal value instead of an environment placeholder, and if any file
 * stops being parseable.
 */
class ServedConfigurationTest {

    private static final Path CONFIG_DIRECTORY = Path.of("src/main/resources/config");

    /** Keys whose values must never be committed literally. */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            ".*(password|secret|token|credential|private-key|passphrase|api-key).*", Pattern.CASE_INSENSITIVE);

    /** A value that defers to the environment, e.g. ${REDIS_PASSWORD:} or ${DB_PASSWORD:-}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^}]+}$");

    static Stream<Path> configurationFiles() throws IOException {
        try (Stream<Path> files = Files.walk(CONFIG_DIRECTORY)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yml"))
                    .toList()
                    .stream();
        }
    }

    @Test
    @DisplayName("the served configuration directory exists and is not empty")
    void configurationDirectoryIsPopulated() throws IOException {
        assertThat(CONFIG_DIRECTORY).isDirectory();
        assertThat(configurationFiles()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0} is valid YAML")
    @MethodSource("configurationFiles")
    void parsesAsYaml(Path file) throws IOException {
        Object parsed = new Yaml().load(Files.readString(file));

        assertThat(parsed).as("%s should contain a YAML mapping", file).isInstanceOf(Map.class);
    }

    @ParameterizedTest(name = "{0} contains no literal secrets")
    @MethodSource("configurationFiles")
    void containsNoLiteralSecrets(Path file) throws IOException {
        Map<String, Object> flattened = flatten(new Yaml().load(Files.readString(file)));

        List<String> violations = new ArrayList<>();
        flattened.forEach((key, value) -> {
            if (!SENSITIVE_KEY.matcher(key).matches()) {
                return;
            }
            String rendered = String.valueOf(value);
            if (!PLACEHOLDER.matcher(rendered).matches()) {
                violations.add("%s = %s".formatted(key, rendered));
            }
        });

        assertThat(violations)
                .as("%s must reference secrets as ${ENV_VAR} placeholders rather than literal values", file)
                .isEmpty();
    }

    private static Map<String, Object> flatten(Object root) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattenInto("", root, flattened);
        return flattened;
    }

    private static void flattenInto(String prefix, Object node, Map<String, Object> target) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
                flattenInto(path, value, target);
            });
        } else if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                flattenInto(prefix + "[" + index + "]", list.get(index), target);
            }
        } else {
            target.put(prefix, node);
        }
    }
}
