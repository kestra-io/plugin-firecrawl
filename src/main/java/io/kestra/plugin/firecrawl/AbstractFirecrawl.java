package io.kestra.plugin.firecrawl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.firecrawl.client.FirecrawlClient;
import com.firecrawl.models.Document;
import com.firecrawl.models.ScrapeOptions;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

/**
 * Base class for Firecrawl tasks: connection settings, page-level scrape options and result storage.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFirecrawl extends Task implements FirecrawlInterface {

    @NotNull
    @ToString.Exclude
    protected Property<String> apiKey;

    protected Property<String> baseUrl;

    @Builder.Default
    protected Property<List<String>> formats = Property.ofValue(List.of("markdown"));

    protected Property<Boolean> onlyMainContent;

    protected Property<List<String>> includeTags;

    protected Property<List<String>> excludeTags;

    protected Property<Duration> waitFor;

    protected Property<Duration> requestTimeout;

    /**
     * Creates a Firecrawl SDK client from the rendered connection properties.
     */
    protected FirecrawlClient client(RunContext runContext) throws IllegalVariableEvaluationException {
        FirecrawlClient.Builder builder = FirecrawlClient.builder()
            .apiKey(runContext.render(this.apiKey).as(String.class).orElseThrow());

        runContext.render(this.baseUrl).as(String.class).ifPresent(builder::apiUrl);

        return builder.build();
    }

    /**
     * Builds the page-level scrape options shared by {@code Scrape} and {@code Crawl}.
     */
    protected ScrapeOptions scrapeOptions(RunContext runContext) throws IllegalVariableEvaluationException {
        ScrapeOptions.Builder builder = ScrapeOptions.builder();

        List<Object> renderedFormats = new ArrayList<>(runContext.render(this.formats).asList(String.class));
        if (!renderedFormats.isEmpty()) {
            builder.formats(renderedFormats);
        }

        runContext.render(this.onlyMainContent).as(Boolean.class).ifPresent(builder::onlyMainContent);

        List<String> renderedIncludeTags = runContext.render(this.includeTags).asList(String.class);
        if (!renderedIncludeTags.isEmpty()) {
            builder.includeTags(renderedIncludeTags);
        }

        List<String> renderedExcludeTags = runContext.render(this.excludeTags).asList(String.class);
        if (!renderedExcludeTags.isEmpty()) {
            builder.excludeTags(renderedExcludeTags);
        }

        runContext.render(this.waitFor).as(Duration.class)
            .ifPresent(duration -> builder.waitFor(Math.toIntExact(duration.toMillis())));
        runContext.render(this.requestTimeout).as(Duration.class)
            .ifPresent(duration -> builder.timeout(Math.toIntExact(duration.toMillis())));

        return builder.build();
    }

    /**
     * Writes documents to an ION file in the internal storage, one row per document.
     */
    protected URI store(RunContext runContext, List<Document> documents) throws IOException {
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            List<Map<String, Object>> rows = documents.stream()
                .map(document -> JacksonMapper.toMap(document))
                .toList();
            FileSerde.writeAll(writer, Flux.fromIterable(rows)).block();
        }

        return runContext.storage().putFile(tempFile);
    }
}
