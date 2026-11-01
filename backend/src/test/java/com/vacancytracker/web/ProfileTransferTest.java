package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vacancytracker.matching.ProfileInput;
import com.vacancytracker.matching.ProfileValidationException;
import com.vacancytracker.matching.SearchProfile;
import com.vacancytracker.matching.SearchProfileService;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProfileTransferTest extends AbstractIntegrationTest {

    private static final Path EXAMPLE = Path.of("../docs/examples/search-profile.example.yml");

    @Autowired
    private ProfileTransfer transfer;

    @Autowired
    private SearchProfileService profiles;

    private static ProfileInput content(SearchProfile p) {
        return new ProfileInput(p.name(), p.enabled(), p.sources(), p.scheduleOverride(), p.thresholds(), p.criteria());
    }

    private SearchProfile importExample() throws Exception {
        return transfer.importProfile(Files.readString(EXAMPLE)).profile();
    }

    @Test
    void nothingIsLoadedFromTheExampleUntilTheUserImportsIt() throws Exception {
        assertThat(profiles.list()).isEmpty();

        ProfileTransfer.ImportResult result = transfer.importProfile(Files.readString(EXAMPLE));

        assertThat(result.created()).isTrue();
        assertThat(result.profile().name()).isEqualTo("Example profile");
        assertThat(result.profile().criteria()).hasSize(7);
        assertThat(profiles.list()).hasSize(1);
    }

    @Test
    void yamlRoundTripKeepsEverythingIncludingOrder() throws Exception {
        SearchProfile original = importExample();
        String yaml = transfer.export(original, true);

        String renamed = yaml.replace("name: Example profile", "name: Copy");
        SearchProfile copy = transfer.importProfile(renamed).profile();

        assertThat(copy.id()).isNotEqualTo(original.id());
        assertThat(content(copy)).isEqualTo(new ProfileInput("Copy", original.enabled(), original.sources(),
                original.scheduleOverride(), original.thresholds(), original.criteria()));
        assertThat(yaml).contains("schemaVersion: 1").contains("schedule:").doesNotContain("scheduleOverride");
    }

    @Test
    void jsonRoundTripKeepsEverything() throws Exception {
        SearchProfile original = importExample();
        String json = transfer.export(original, false);

        String renamed = json.replace("\"name\" : \"Example profile\"", "\"name\" : \"Json copy\"");
        SearchProfile copy = transfer.importProfile(renamed).profile();

        assertThat(json).startsWith("{");
        assertThat(copy.name()).isEqualTo("Json copy");
        assertThat(copy.criteria()).isEqualTo(original.criteria());
        assertThat(copy.thresholds()).isEqualTo(original.thresholds());
    }

    @Test
    void importingAnExistingNameCreatesANewVersionNotAnotherProfile() throws Exception {
        SearchProfile original = importExample();
        String edited = transfer.export(original, true).replace("inboxMinScore: 40", "inboxMinScore: 55");

        ProfileTransfer.ImportResult result = transfer.importProfile(edited.replace("Example profile", "EXAMPLE PROFILE"));

        assertThat(result.created()).isFalse();
        assertThat(profiles.list()).hasSize(1);
        assertThat(result.profile().id()).isEqualTo(original.id());
        assertThat(result.profile().version()).isEqualTo(2);
        assertThat(result.profile().thresholds().inboxMinScore()).isEqualTo(55);
        assertThat(profiles.version(original.id(), 1).thresholds().inboxMinScore()).isEqualTo(40);
    }

    @Test
    void problemsAreReportedByPathAndNothingIsStored() throws Exception {
        String yaml = Files.readString(EXAMPLE);

        assertThatThrownBy(() -> transfer.importProfile(yaml.replace("operator: contains_any", "operator: regex").replace("field: title", "field: salary")))
                .isInstanceOfSatisfying(ProfileValidationException.class,
                        e -> assertThat(e.errors().keySet()).anyMatch(k -> k.startsWith("criteria[")));
        assertThatThrownBy(() -> transfer.importProfile(yaml.replace("weight: 8", "weigth: 8")))
                .isInstanceOfSatisfying(ProfileValidationException.class,
                        e -> assertThat(e.errors()).containsEntry("criteria[1].weigth", "Unknown property"));
        assertThatThrownBy(() -> transfer.importProfile(yaml.replace("schemaVersion: 1", "schemaVersion: 2")))
                .isInstanceOfSatisfying(ProfileValidationException.class, e -> assertThat(e.errors()).containsKey("schemaVersion"));
        assertThatThrownBy(() -> transfer.importProfile(yaml.replace("profile:", "profil:")))
                .isInstanceOfSatisfying(ProfileValidationException.class, e -> assertThat(e.errors()).containsKey("profil"));
        assertThatThrownBy(() -> transfer.importProfile("a: [unclosed"))
                .isInstanceOfSatisfying(ProfileValidationException.class, e -> assertThat(e.errors()).containsKey("file"));
        assertThatThrownBy(() -> transfer.importProfile("- just\n- a list"))
                .isInstanceOf(ProfileValidationException.class);
        assertThatThrownBy(() -> transfer.importProfile(""))
                .isInstanceOf(ProfileValidationException.class);

        assertThat(profiles.list()).isEmpty();
    }

    @Test
    void yamlTagsCannotInstantiateObjects() {
        String evil = "schemaVersion: 1\nprofile: !!java.lang.ProcessBuilder [[\"touch\", \"/tmp/x\"]]\n";

        assertThatThrownBy(() -> transfer.importProfile(evil)).isInstanceOf(ProfileValidationException.class);
    }
}
