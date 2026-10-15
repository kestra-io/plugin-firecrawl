package io.kestra.plugin.firecrawl;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.firecrawl.client.FirecrawlClient;
import com.firecrawl.models.CrawlJob;
import com.firecrawl.models.CrawlOptions;
import com.firecrawl.models.CrawlResponse;
import com.firecrawl.models.Document;

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
    description = "Starts a Firecrawl crawl from a URL, waits for it to finish and stores every crawled page as one row of an ION file in the internal storage. Firecrawl keeps crawl results for a limited time, so they are downloaded as soon as the crawl completes. If the task is killed or times out, the crawl is cancelled on the Firecrawl side so it stops consuming credits."
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

    @Schema(
        title = "Start URL",
        description = "Absolute URL the crawl starts from."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> url;

    @Schema(
        title = "Page limit",
        description = "Maximum number of pages to crawl."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> limit;

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
        description = "Maximum number of link hops from the start URL."
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
        description = "Maximum total time to wait for the crawl to finish. When it is exceeded the crawl is cancelled on the Firecrawl side and the task fails. Defaults to 60 minutes."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(
        title = "Poll frequency",
        description = "Interval between two status checks of the crawl. Defaults to 2 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(2));

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
        String renderedUrl = runContext.render(this.url).as(String.class).orElseThrow();
        Duration renderedPollFrequency = runContext.render(this.pollFrequency).as(Duration.class).orElseThrow();
        Duration renderedMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElseThrow();

        FirecrawlClient client = this.client(runContext);
        CrawlResponse started = client.startCrawl(renderedUrl, this.crawlOptions(runContext));
        String crawlId = started.getId();

        this.activeClient = client;
        this.activeCrawlId = crawlId;
        runContext.logger().info("Started Firecrawl crawl {} for {}", crawlId, renderedUrl);

        CrawlJob job;
        try {
            job = Await.until(
                throwSupplier(() ->
                {
                    CrawlJob current = client.getCrawlStatus(crawlId);
                    return current.isDone() ? current : null;
                }),
                renderedPollFrequency,
                renderedMaxDuration
            );
        } catch (TimeoutException e) {
            this.kill();
            throw new IllegalStateException("Firecrawl crawl " + crawlId + " did not finish within " + renderedMaxDuration, e);
        }

        if (!"completed".equals(job.getStatus())) {
            throw new IllegalStateException("Firecrawl crawl " + crawlId + " ended with status '" + job.getStatus() + "'");
        }

        List<Document> documents = this.collect(client, crawlId, job);
        runContext.logger().info("Crawl {} completed with {} pages", crawlId, documents.size());

        runContext.metric(Counter.of("pages", documents.size()));
        if (job.getCreditsUsed() != null) {
            runContext.metric(Counter.of("credits", job.getCreditsUsed()));
        }

        return Output.builder()
            .crawlId(crawlId)
            .status(job.getStatus())
            .pagesCount(documents.size())
            .creditsUsed(job.getCreditsUsed())
            .uri(this.store(runContext, documents))
            .build();
    }

    /**
     * Cancels the running crawl on the Firecrawl side when the task is killed or times out.
     */
    @Override
    public void kill() {
        FirecrawlClient client = this.activeClient;
        String crawlId = this.activeCrawlId;

        if (client == null || crawlId == null) {
            return;
        }

        try {
            client.cancelCrawl(crawlId);
            LOGGER.info("Cancelled Firecrawl crawl {}", crawlId);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to cancel Firecrawl crawl {}: {}", crawlId, e.getMessage());
        }
    }

    private CrawlOptions crawlOptions(RunContext runContext) throws Exception {
        CrawlOptions.Builder builder = CrawlOptions.builder()
            .scrapeOptions(this.scrapeOptions(runContext));

        runContext.render(this.limit).as(Integer.class).ifPresent(builder::limit);
        runContext.render(this.maxDiscoveryDepth).as(Integer.class).ifPresent(builder::maxDiscoveryDepth);
        runContext.render(this.crawlEntireDomain).as(Boolean.class).ifPresent(builder::crawlEntireDomain);
        runContext.render(this.allowExternalLinks).as(Boolean.class).ifPresent(builder::allowExternalLinks);
        runContext.render(this.allowSubdomains).as(Boolean.class).ifPresent(builder::allowSubdomains);

        List<String> renderedIncludePaths = runContext.render(this.includePaths).asList(String.class);
        if (!renderedIncludePaths.isEmpty()) {
            builder.includePaths(renderedIncludePaths);
        }

        List<String> renderedExcludePaths = runContext.render(this.excludePaths).asList(String.class);
        if (!renderedExcludePaths.isEmpty()) {
            builder.excludePaths(renderedExcludePaths);
        }

        return builder.build();
    }

    /**
     * Gathers the pages of a finished crawl. Firecrawl paginates large results and exposes the next page as a cursor URL,
     * but the SDK only follows it internally when blocking on {@code crawl(...)}, which would not let us cancel the job.
     * The SDK builds the status request path from the job id, so the cursor query is passed along with the id.
     */
    private List<Document> collect(FirecrawlClient client, String crawlId, CrawlJob firstPage) {
        List<Document> documents = new ArrayList<>();
        CrawlJob current = firstPage;
        String previousNext = null;

        while (true) {
            if (current.getData() != null) {
                documents.addAll(current.getData());
            }

            String next = current.getNext();
            if (next == null || next.isEmpty() || next.equals(previousNext)) {
                return documents;
            }

            String query = URI.create(next).getRawQuery();
            current = client.getCrawlStatus(query == null ? crawlId : crawlId + "?" + query);
            previousNext = next;
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
