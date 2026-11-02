package com.vacancytracker.collection.adapter.html;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Sites are configured through ordinary properties, which in a deployment are environment variables. */
@SpringBootTest(properties = {
        "app.sources.html.sites[0].id=example",
        "app.sources.html.sites[0].enabled=true",
        "app.sources.html.sites[0].list-url=https://careers.example.test/jobs?page={page}",
        "app.sources.html.sites[0].max-pages=3",
        "app.sources.html.sites[0].terms-checked-on=2026-11-01",
        "app.sources.html.sites[0].terms-url=https://careers.example.test/terms",
        "app.sources.html.sites[0].item=li.job",
        "app.sources.html.sites[0].title=a.title",
        "app.sources.html.sites[0].company-name=Example Inc",
        "app.sources.html.sites[1].id=unchecked",
        "app.sources.html.sites[1].enabled=true",
        "app.sources.html.sites[1].list-url=https://other.example.test/jobs",
        "app.sources.html.sites[1].item=li",
        "app.sources.html.sites[1].title=a"})
class HtmlSourcesBindingTest extends AbstractIntegrationTest {

    @Autowired
    private HtmlSourcesProperties properties;

    @Test
    void sitesAreBoundWithDefaultsAndOnlyTheOneWithATermsCheckIsApproved() {
        assertThat(properties.sites()).hasSize(2);
        HtmlSite example = properties.sites().get(0);
        assertThat(example.id()).isEqualTo("example");
        assertThat(example.maxPages()).isEqualTo(3);
        assertThat(example.companyName()).isEqualTo("Example Inc");
        assertThat(example.link()).isEmpty();
        assertThat(example.approved()).isTrue();
        assertThat(example.pages()).isEqualTo(3);

        HtmlSite unchecked = properties.sites().get(1);
        assertThat(unchecked.maxPages()).isEqualTo(1);
        assertThat(unchecked.approved()).isFalse();
    }

    @Test
    void pagesAreCappedAndNeedAPlaceholder() {
        HtmlSite many = new HtmlSite("a", true, "https://x.test/{page}", 99, "2026-11-01", "https://x.test/t", "i", "t", "", "", "", "");
        HtmlSite noPlaceholder = new HtmlSite("b", true, "https://x.test/jobs", 4, "2026-11-01", "https://x.test/t", "i", "t", "", "", "", "");

        assertThat(many.pages()).isEqualTo(HtmlSite.MAX_PAGES_LIMIT);
        assertThat(noPlaceholder.pages()).isEqualTo(1);
    }
}
