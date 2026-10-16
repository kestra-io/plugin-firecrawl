package io.kestra.plugin.firecrawl;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.firecrawl.client.FirecrawlClient;
import com.firecrawl.errors.RateLimitException;
import com.firecrawl.models.CrawlJob;
import com.firecrawl.models.CrawlOptions;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Crawl a website with Firecrawl",
    description = "Starts a Firecrawl crawl from a URL, waits for it to finish and stores every crawled page as one row of an ION file in the internal storage. Firecrawl keeps crawl results for a limited time, so they are downloaded as soon as the crawl completes. If the task is killed, times out or fails while waiting, the crawl is cancelled on the Firecrawl side so it stops consuming credits."
)
@Plugin(
    examples = {
        @Example(
            title = "Crawl the documentation pages of a site to markdown.",
            full = true,
            code = """
                id: firecrawl_crawl_docs
                namespace: company.team

                tasks:
                  - id: crawl_docs
                    type: io.kestra.plugin.firecrawl.Crawl
                    apiKey: "{{ secret('FIRECRAWL_API_KEY') }}"
                    url: https://example.com/docs
                    limit: 50
                    includePaths:
                      - /docs/.*

                  - id: log_count
                    type: io.kestra.plugin.core.log.Log
                    message: "Crawled {{ outputs.crawl_docs.pagesCount }} pages into {{ outputs.crawl_docs.uri }}"
                """
        ),
        @Example(
            title = "Crawl a site with a slower polling frequency and a maximum duration.",
            full = true,
            code = """
                id: firecrawl_crawl_with_timeout
                namespace: company.team

                tasks:
                  - id: crawl_site
                    type: io.kestra.plugin.firecrawl.Crawl
                    apiKey: "{{ secret('FIRECRAWL_API_KEY') }}"
                    url: https://example.com
                    limit: 200
                    maxDiscoveryDepth: 3
                    onlyMainContent: true
                    pollFrequency: PT10S
                    maxDuration: PT30M
                """
        )
    },
    metrics = {
        @Metric(
            name = "pages",
            type = Counter.TYPE,
            unit = "page",
            description = "Number of pages crawled and stored"
        ),
        @Metric(
            name = "credits",
            type = Counter.TYPE,
            unit = "credit",
            description = "Firecrawl credits used by the crawl, when reported by the API"
        )
    }
)
public class Crawl extends AbstractFirecrawl implements RunnableTask<Crawl.Output> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Crawl.class);
    private static final int DEFAULT_LIMIT = 100;
    private static final Duration MIN_POLL_FREQUENCY = Duration.ofSeconds(1);

    @Schema(
        title = "Start URL",
        description = "Absolute URL the crawl starts from."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> url;

    @Schema(
        title = "Page limit",
        description = "Maximum number of pages to crawl, at least 1. Defaults to 100."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> limit = Property.ofValue(DEFAULT_LIMIT);

    @Schema(
        title = "Paths to include",
        description = "Regular expressions matched against URL paths; only matching pages are crawled."
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> includePaths;

    @Schema(
        title = "Paths to exclude",
        description = "Regular expressions matched against URL paths; matching pages are skipped."
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> excludePaths;

    @Schema(
        title = "Maximum discovery depth",
        description = "Maximum number of link hops from the start URL, at least 0."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> maxDiscoveryDepth;

    @Schema(
        title = "Crawl the entire domain",
        description = "When `true`, the crawl can follow links to sibling and parent paths instead of only deeper paths."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> crawlEntireDomain;

    @Schema(
        title = "Allow external links",
        description = "When `true`, the crawl follows links to other domains."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> allowExternalLinks;

    @Schema(
        title = "Allow subdomains",
        description = "When `true`, the crawl follows links to subdomains of the start URL."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> allowSubdomains;

    @Schema(
        title = "Maximum wait duration",
        description = "Maximum total time to wait for the crawl to finish, must be positive. When it is exceeded the crawl is cancelled on the Firecrawl side and the task fails. Defaults to 60 minutes."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(
        title = "Poll frequency",
        description = "Interval between two status checks of the crawl, at least 1 second. Each status check returns the pages crawled so far, so keep it moderate for large crawls. Defaults to 5 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(5));

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final transient AtomicBoolean cancelRequested = new AtomicBoolean(false);

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile FirecrawlClient activeClient;

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile String activeCrawlId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var renderedUrl = runContext.render(this.url).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("url is required"));
        var renderedLimit = runContext.render(this.limit).as(Integer.class).orElse(DEFAULT_LIMIT);
        var renderedMaxDepth = runContext.render(this.maxDiscoveryDepth).as(Integer.class);
        var renderedPollFrequency = runContext.render(this.pollFrequency).as(Duration.class).orElse(Duration.ofSeconds(5));
        var renderedMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElse(Duration.ofMinutes(60));

        validate(renderedLimit >= 1, "limit must be greater than or equal to 1");
        validate(renderedMaxDepth.isEmpty() || renderedMaxDepth.get() >= 0, "maxDiscoveryDepth must be greater than or equal to 0");
        validate(renderedPollFrequency.compareTo(MIN_POLL_FREQUENCY) >= 0, "pollFrequency must be at least 1 second");
        validate(!renderedMaxDuration.isZero() && !renderedMaxDuration.isNegative(), "maxDuration must be positive");

        var client = this.client(runContext);
        var crawlId = client.startCrawl(renderedUrl, this.crawlOptions(runContext, renderedLimit, renderedMaxDepth)).getId();

        this.activeClient = client;
        this.activeCrawlId = crawlId;
        runContext.logger().info("Started Firecrawl crawl {} for {}", crawlId, renderedUrl);

        try {
            var job = this.waitForCompletion(client, crawlId, renderedPollFrequency, renderedMaxDuration);

            if (!"completed".equals(job.getStatus())) {
                throw new IllegalStateException("Firecrawl crawl " + crawlId + " ended with status '" + job.getStatus() + "'");
            }

            try (var result = ResultFile.create(runContext)) {
                this.writePages(client, crawlId, job, renderedLimit, result);
                var uri = result.upload();

                runContext.logger().info("Crawl {} completed with {} pages", crawlId, result.count());
                runContext.metric(Counter.of("pages", result.count()));
                if (job.getCreditsUsed() != null) {
                    runContext.metric(Counter.of("credits", job.getCreditsUsed()));
                }

                return Output.builder()
                    .crawlId(crawlId)
                    .status(job.getStatus())
                    .pagesCount(result.count())
                    .creditsUsed(job.getCreditsUsed())
                    .uri(uri)
                    .build();
            }
        } finally {
            this.activeClient = null;
            this.activeCrawlId = null;
        }
    }

    @Override
    public void kill() {
        this.cancelCrawl();
    }

    private void cancelCrawl() {
        var client = this.activeClient;
        var crawlId = this.activeCrawlId;

        if (client == null || crawlId == null || !this.cancelRequested.compareAndSet(false, true)) {
            return;
        }

        try {
            client.cancelCrawl(crawlId);
            LOGGER.info("Cancelled Firecrawl crawl {}", crawlId);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to cancel Firecrawl crawl {}: {}", crawlId, e.getMessage());
        }
    }

    private CrawlJob waitForCompletion(FirecrawlClient client, String crawlId, Duration pollFrequency, Duration maxDuration) throws Exception {
        try {
            return Await.until(
                throwSupplier(() ->
                {
                    try {
                        var current = client.getCrawlStatus(crawlId);
                        return current.isDone() ? current : null;
                    } catch (RateLimitException e) {
                        LOGGER.warn("Firecrawl rate limited the status check of crawl {}, retrying at the next poll", crawlId);
                        return null;
                    }
                }),
                pollFrequency,
                maxDuration
            );
        } catch (TimeoutException e) {
            this.cancelCrawl();
            throw new IllegalStateException("Firecrawl crawl " + crawlId + " did not finish within " + maxDuration, e);
        } catch (Exception e) {
            this.cancelCrawl();
            throw e;
        }
    }

    private CrawlOptions crawlOptions(RunContext runContext, int limit, Optional<Integer> maxDepth) throws Exception {
        var builder = CrawlOptions.builder()
            .scrapeOptions(this.scrapeOptions(runContext))
            .limit(limit);

        maxDepth.ifPresent(builder::maxDiscoveryDepth);
        runContext.render(this.crawlEntireDomain).as(Boolean.class).ifPresent(builder::crawlEntireDomain);
        runContext.render(this.allowExternalLinks).as(Boolean.class).ifPresent(builder::allowExternalLinks);
        runContext.render(this.allowSubdomains).as(Boolean.class).ifPresent(builder::allowSubdomains);

        var renderedIncludePaths = runContext.render(this.includePaths).asList(String.class);
        if (!renderedIncludePaths.isEmpty()) {
            builder.includePaths(renderedIncludePaths);
        }

        var renderedExcludePaths = runContext.render(this.excludePaths).asList(String.class);
        if (!renderedExcludePaths.isEmpty()) {
            builder.excludePaths(renderedExcludePaths);
        }

        return builder.build();
    }

    /**
     * Writes the pages to the result file one response at a time, so only a single page of results is held in memory.
     * The SDK only follows the `next` cursor inside the blocking `crawl(...)`, which cannot be cancelled, and has no
     * method taking the cursor, so it is passed with the job id: the SDK appends the id to the request path as is.
     * The loop stops at the page limit or when a cursor repeats, so a misbehaving server cannot make it endless.
     */
    private void writePages(FirecrawlClient client, String crawlId, CrawlJob firstPage, int limit, ResultFile result) throws IOException {
        var visitedCursors = new HashSet<String>();
        var page = firstPage;

        while (true) {
            if (page.getData() != null) {
                result.write(page.getData());
            }

            var next = page.getNext();
            if (next == null || next.isEmpty() || result.count() >= limit || !visitedCursors.add(next)) {
                return;
            }

            var query = URI.create(next).getRawQuery();
            page = client.getCrawlStatus(query == null ? crawlId : crawlId + "?" + query);
        }
    }

    private static void validate(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Firecrawl crawl ID")
        private final String crawlId;

        @Schema(title = "Final crawl status")
        private final String status;

        @Schema(title = "Number of crawled pages")
        private final Integer pagesCount;

        @Schema(title = "Credits used by the crawl")
        private final Integer creditsUsed;

        @Schema(
            title = "Crawled pages URI",
            description = "ION file in the internal storage containing one row per crawled page."
        )
        private final URI uri;
    }
}
