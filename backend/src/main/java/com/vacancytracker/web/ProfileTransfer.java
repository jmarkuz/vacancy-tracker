package com.vacancytracker.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.vacancytracker.matching.CompanyListService;
import com.vacancytracker.matching.ProfileInput;
import com.vacancytracker.matching.ProfileValidationException;
import com.vacancytracker.matching.SearchProfile;
import com.vacancytracker.matching.SearchProfileService;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile import and export (T-057, docs/search-profiles.md section 6). The file is a wrapper with
 * schemaVersion and the profile. YAML is primary; JSON is read by the same parser because it is valid YAML.
 * Import is strict: unknown keys and invalid criteria are reported by path, and nothing is stored on error.
 */
@Component
public class ProfileTransfer {

    static final int SCHEMA_VERSION = 1;

    public record ImportResult(SearchProfile profile, boolean created) {
    }

    private final ObjectMapper json;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private final ProfileInputParser parser;
    private final SearchProfileService profiles;
    private final CompanyListService lists;

    public ProfileTransfer(ObjectMapper json, ProfileInputParser parser, SearchProfileService profiles,
                           CompanyListService lists) {
        this.json = json;
        this.parser = parser;
        this.profiles = profiles;
        this.lists = lists;
    }

    public String export(SearchProfile profile, boolean asYaml) {
        ObjectNode profileNode = json.valueToTree(new ProfileInput(profile.name(), profile.enabled(), profile.sources(),
                profile.scheduleOverride(), profile.thresholds(), profile.criteria()));
        profileNode.set("schedule", profileNode.remove("scheduleOverride"));
        ObjectNode file = json.createObjectNode();
        Map<String, java.util.List<String>> own = lists.forProfile(profile.id());
        if (!own.get("allowlist").isEmpty() || !own.get("blocklist").isEmpty()) {
            profileNode.set("companyLists", json.valueToTree(new java.util.TreeMap<>(own)));
        }
        file.put("schemaVersion", SCHEMA_VERSION);
        file.put("version", profile.version());
        file.set("profile", profileNode);
        try {
            return asYaml
                    ? yaml.writeValueAsString(file)
                    : json.writerWithDefaultPrettyPrinter().writeValueAsString(file) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the profile", e);
        }
    }

    /**
     * Creates a new profile, or a new version of the profile with the same name (case-insensitive). When the file
     * has companyLists they replace the profile's own lists; the whole import is one transaction.
     */
    @Transactional
    public ImportResult importProfile(String text) {
        ObjectNode node = read(text);
        JsonNode listsNode = node.remove("companyLists");
        java.util.List<String> allow = names(listsNode, "allowlist");
        java.util.List<String> block = names(listsNode, "blocklist");
        ProfileInput input = parser.parse(node);
        Optional<SearchProfile> existing = profiles.list().stream()
                .filter(p -> p.name().equalsIgnoreCase(input.name() == null ? "" : input.name().trim()))
                .findFirst();
        ImportResult result = existing
                .map(p -> new ImportResult(profiles.update(p.id(), input), false))
                .orElseGet(() -> new ImportResult(profiles.create(input), true));
        if (listsNode != null) {
            lists.replaceForProfile(result.profile().id(), allow, block);
        }
        return result;
    }

    /** The company names of one list in the file; a wrong shape is an error with its path. */
    private static java.util.List<String> names(JsonNode listsNode, String key) {
        if (listsNode == null) {
            return java.util.List.of();
        }
        if (!listsNode.isObject()) {
            throw new ProfileValidationException(Map.of("companyLists", "Expected allowlist and blocklist"));
        }
        for (Iterator<String> it = listsNode.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!name.equals("allowlist") && !name.equals("blocklist")) {
                throw new ProfileValidationException(Map.of("companyLists." + name, "Unknown property"));
            }
        }
        JsonNode list = listsNode.path(key);
        if (list.isMissingNode() || list.isNull()) {
            return java.util.List.of();
        }
        if (!list.isArray()) {
            throw new ProfileValidationException(Map.of("companyLists." + key, "Expected a list of company names"));
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isTextual()) {
                throw new ProfileValidationException(Map.of("companyLists." + key + "[" + i + "]", "Expected a company name"));
            }
            out.add(list.get(i).asText());
        }
        return out;
    }

    private ObjectNode read(String text) {
        JsonNode root;
        try {
            root = yaml.readTree(text == null ? "" : text);
        } catch (JsonProcessingException e) {
            throw new ProfileValidationException(Map.of("file", "Not valid YAML or JSON"));
        }
        if (root == null || !root.isObject()) {
            throw new ProfileValidationException(Map.of("file", "Expected an object with schemaVersion and profile"));
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!Map.of("schemaVersion", 1, "version", 1, "profile", 1).containsKey(name)) {
                throw new ProfileValidationException(Map.of(name, "Unknown property"));
            }
        }
        if (!root.path("schemaVersion").isInt() || root.get("schemaVersion").intValue() != SCHEMA_VERSION) {
            throw new ProfileValidationException(Map.of("schemaVersion", "Unsupported schema version, expected " + SCHEMA_VERSION));
        }
        if (!root.path("profile").isObject()) {
            throw new ProfileValidationException(Map.of("profile", "Missing profile"));
        }
        ObjectNode profile = ((ObjectNode) root.get("profile")).deepCopy();
        if (profile.has("schedule")) {
            if (profile.has("scheduleOverride")) {
                throw new ProfileValidationException(Map.of("scheduleOverride", "Unknown property"));
            }
            profile.set("scheduleOverride", profile.remove("schedule"));
        }
        return profile;
    }
}
