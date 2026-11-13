package com.vacancytracker.web;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Browser caching of the packaged UI. Vite writes the bundle to {@code /assets/} with a hash in every file name, so a
 * file never changes under its name: it can be kept for a year and never re-checked ({@code immutable}). Everything
 * else (index.html, which names the current bundle, and the icons) is revalidated on each visit, so a new release is
 * picked up at once; the browser gets a cheap 304 when nothing changed. A missing file answers 404 without a cache
 * header, so a bad address is not remembered.
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    static final CacheControl HASHED_ASSETS = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(HASHED_ASSETS);
        // Replaces Spring Boot's default handler for the rest of the static files, with the same location.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
