package com.vacancytracker.collection.adapter.html;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.sources.html.sites: the career sites to read. Empty by default; the repository ships none. */
@ConfigurationProperties(prefix = "app.sources.html")
public record HtmlSourcesProperties(@DefaultValue List<HtmlSite> sites) {
}
