package io.kestra.plugin.firecrawl;

import java.time.Duration;
import java.util.List;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Common properties shared by all Firecrawl tasks.
 */
public interface FirecrawlInterface {

    @Schema(
        title = "Firecrawl API key",
        description = "API key sent as a bearer token on every request; store it as a Kestra secret."
    )
    @NotNull
    @PluginProperty(secret = true, group = "main")
    Property<String> getApiKey();

    @Schema(
        title = "Firecrawl API base URL",
        description = "Base URL of the Firecrawl API without the version suffix. Defaults to `https://api.firecrawl.dev`; set it to target a self-hosted Firecrawl instance."
    )
    @PluginProperty(group = "connection")
    Property<String> getBaseUrl();

    @Schema(
        title = "Output formats",
        description = "Formats requested for each page, for example `markdown`, `html`, `rawHtml`, `links` or `summary`. Defaults to `markdown`."
    )
    @PluginProperty(group = "processing")
    Property<List<String>> getFormats();

    @Schema(
        title = "Only main content",
        description = "When `true`, Firecrawl strips headers, navigation and footers and keeps only the main content of each page."
    )
    @PluginProperty(group = "processing")
    Property<Boolean> getOnlyMainContent();

    @Schema(
        title = "HTML tags to include",
        description = "CSS selectors or tags that must be kept in the scraped content."
    )
    @PluginProperty(group = "processing")
    Property<List<String>> getIncludeTags();

    @Schema(
        title = "HTML tags to exclude",
        description = "CSS selectors or tags that must be removed from the scraped content."
    )
    @PluginProperty(group = "processing")
    Property<List<String>> getExcludeTags();

    @Schema(
        title = "Wait before scraping",
        description = "Time to wait for dynamic content to load before each page is scraped."
    )
    @PluginProperty(group = "advanced")
    Property<Duration> getWaitFor();

    @Schema(
        title = "Per-page request timeout",
        description = "Maximum time Firecrawl spends scraping a single page. This is distinct from the task-level `timeout`."
    )
    @PluginProperty(group = "advanced")
    Property<Duration> getRequestTimeout();
}
