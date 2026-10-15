package io.kestra.plugin.firecrawl;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.firecrawl.client.FirecrawlClient;
import com.firecrawl.models.Document;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Scrape a web page with Firecrawl",
    description = "Scrapes a single URL through the Firecrawl API and returns clean, LLM-ready content such as markdown. The full document is stored as an ION file in the internal storage; the markdown is also returned inline when it is small enough."
)
@Plugin(
    examples = {
        @Example(
            title = "Scrape a page to markdown and log it.",
            full = true,
            code = """
                id: firecrawl_scrape
                namespace: company.team

                tasks:
                  - id: scrape
                    type: io.kestra.plugin.firecrawl.Scrape
                    apiKey: "{{ secret('FIRECRAWL_API_KEY') }}"
                    url: https://example.com

                  - id: log_markdown
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.scrape.markdown }}"
                """
        ),
        @Example(
            title = "Scrape only the main content of a page as markdown and links.",
            full = true,
            code = """
                id: firecrawl_scrape_main_content
                namespace: company.team

                tasks:
                  - id: scrape
                    type: io.kestra.plugin.firecrawl.Scrape
                    apiKey: "{{ secret('FIRECRAWL_API_KEY') }}"
                    url: https://example.com/blog
                    formats:
                      - markdown
                      - links
                    onlyMainContent: true
                    waitFor: PT2S
                """
        )
    }
)
public class Scrape extends AbstractFirecrawl implements RunnableTask<Scrape.Output> {

    /**
     * Inline markdown is capped so large pages do not bloat the execution outputs; the full content is always in {@code uri}.
     */
    static final int MAX_INLINE_MARKDOWN_LENGTH = 100_000;

    @Schema(
        title = "URL to scrape",
        description = "Absolute URL of the page to scrape."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> url;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String renderedUrl = runContext.render(this.url).as(String.class).orElseThrow();

        FirecrawlClient client = this.client(runContext);
        Document document = client.scrape(renderedUrl, this.scrapeOptions(runContext));

        // Some SDK versions do not raise an error when the API answers `success: false`, they return an empty document instead.
        if (isEmpty(document)) {
            throw new IllegalStateException("Firecrawl returned no content for " + renderedUrl);
        }

        runContext.logger().info("Scraped {}", renderedUrl);

        Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : document.getMetadata();
        String markdown = document.getMarkdown();

        return Output.builder()
            .uri(this.store(runContext, List.of(document)))
            .markdown(markdown != null && markdown.length() <= MAX_INLINE_MARKDOWN_LENGTH ? markdown : null)
            .title(metadata.get("title") instanceof String title ? title : null)
            .statusCode(metadata.get("statusCode") instanceof Number code ? code.intValue() : null)
            .build();
    }

    private static boolean isEmpty(Document document) {
        return document == null
            || (document.getMetadata() == null
                && document.getMarkdown() == null
                && document.getHtml() == null
                && document.getRawHtml() == null
                && document.getLinks() == null
                && document.getSummary() == null);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Scraped document URI",
            description = "ION file in the internal storage containing one row with every requested format and the page metadata."
        )
        private final URI uri;

        @Schema(
            title = "Markdown content",
            description = "Markdown of the page, only returned when `markdown` was requested and the content is under 100,000 characters; otherwise read it from `uri`."
        )
        private final String markdown;

        @Schema(title = "Page title")
        private final String title;

        @Schema(title = "HTTP status code of the scraped page")
        private final Integer statusCode;
    }
}
