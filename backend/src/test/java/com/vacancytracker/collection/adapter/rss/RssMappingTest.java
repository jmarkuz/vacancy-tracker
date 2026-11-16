package com.vacancytracker.collection.adapter.rss;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.adapter.RawPosting;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Field mapping of an RSS feed, without a network or a database. */
class RssMappingTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Jobs</title><link>https://feeds.example.test/</link><description>d</description>
                <item>
                  <title>Role One</title><link>https://feeds.example.test/jobs/1</link><guid>1</guid>
                  <author>jobs@acme.test (Acme Labs)</author>
                </item>
                <item>
                  <title>Role Two</title><link>https://feeds.example.test/jobs/2</link><guid>2</guid>
                  <author>hr@acme.test</author>
                </item>
                <item>
                  <title>Role Three</title><link>https://feeds.example.test/jobs/3</link><guid>3</guid>
                  <author>Contoso Ltd</author>
                  <description>Short teaser.</description>
                  <content:encoded><![CDATA[<p>The full text of the posting with all the details.</p>]]></content:encoded>
                </item>
              </channel>
            </rss>
            """;

    private final RssAdapter adapter = new RssAdapter(null, new RssSourcesProperties(List.of()));
    private final RssFeed feed = new RssFeed("example", true, "https://feeds.example.test/rss", "2026-11-02",
            "https://feeds.example.test/terms", "");

    @Test
    void theCompanyIsTheNameInAnAuthorAddressNeverTheAddressItself() {
        List<RawPosting> postings = adapter.parse(feed, FEED);

        assertThat(postings.get(0).company()).isEqualTo("Acme Labs");
        assertThat(postings.get(1).company()).isEqualTo("example");
        assertThat(postings.get(2).company()).isEqualTo("Contoso Ltd");
    }

    @Test
    void theFullContentWinsOverATeaserDescription() {
        List<RawPosting> postings = adapter.parse(feed, FEED);

        assertThat(postings.get(2).description()).isEqualTo("The full text of the posting with all the details.");
    }
}
