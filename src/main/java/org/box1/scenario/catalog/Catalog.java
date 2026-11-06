package org.box1.scenario.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;

/** The declarative scenario catalog, loaded once from the classpath and validated. A bad catalog stops startup. */
@ApplicationScoped
public class Catalog {
    static final String RESOURCE = "catalog/scenarios.yaml";
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,48}$");
    private static final int MAX_SECONDS = 3600;

    private final Map<String, Scenario> scenarios;

    public Catalog() {
        this.scenarios = load();
    }

    record File(List<Scenario> scenarios) {}

    static Map<String, Scenario> load() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        try (InputStream in = Catalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Scenario catalog is missing");
            }
            Map<String, Scenario> byName = new LinkedHashMap<>();
            for (Scenario s : yaml.readValue(in, File.class).scenarios()) {
                validate(s);
                if (byName.putIfAbsent(s.name(), s) != null) {
                    throw new IllegalStateException("Duplicate scenario " + s.name());
                }
            }
            return Collections.unmodifiableMap(byName);
        } catch (IOException error) {
            throw new IllegalStateException("Scenario catalog is not valid", error);
        }
    }

    static void validate(Scenario s) {
        if (s.name() == null || !NAME.matcher(s.name()).matches()) {
            throw new IllegalStateException("Scenario name is not valid");
        }
        if (s.title() == null || s.title().isBlank() || s.mechanism() == null || s.availability() == null) {
            throw new IllegalStateException("Scenario " + s.name() + " lacks a title, mechanism or availability");
        }
        if (s.defaultDurationSeconds() < 1 || s.maxDurationSeconds() > MAX_SECONDS
                || s.defaultDurationSeconds() > s.maxDurationSeconds()) {
            throw new IllegalStateException("Scenario " + s.name() + " has durations outside 1 to 3600 seconds");
        }
        if (!s.available() && (s.notPossibleReason() == null || s.notPossibleReason().isBlank())) {
            throw new IllegalStateException("Scenario " + s.name() + " is not possible but gives no reason");
        }
    }

    public Optional<Scenario> find(String name) {
        return Optional.ofNullable(scenarios.get(name));
    }

    /** Entries in the catalog file's order. */
    public List<Scenario> all() {
        return List.copyOf(scenarios.values());
    }
}
