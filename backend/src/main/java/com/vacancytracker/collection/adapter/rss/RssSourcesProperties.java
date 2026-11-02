package com.vacancytracker.collection.adapter.rss;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.sources.rss.feeds: the feeds to read. Empty by default; the repository ships none. */
@ConfigurationProperties(prefix = "app.sources.rss")
public record RssSourcesProperties(@DefaultValue List<RssFeed> feeds) {
}
