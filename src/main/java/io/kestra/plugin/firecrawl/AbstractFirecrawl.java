package io.kestra.plugin.firecrawl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.firecrawl.client.FirecrawlClient;
import com.firecrawl.models.ScrapeOptions;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

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

    protected FirecrawlClient client(RunContext runContext) throws IllegalVariableEvaluationException {
        var builder = FirecrawlClient.builder()
            .apiKey(runContext.render(this.apiKey).as(String.class).orElseThrow(() -> new IllegalArgumentException("apiKey is required")));

        runContext.render(this.baseUrl).as(String.class).ifPresent(builder::apiUrl);

        return builder.build();
    }

    protected ScrapeOptions scrapeOptions(RunContext runContext) throws IllegalVariableEvaluationException {
        var builder = ScrapeOptions.builder();

        var renderedFormats = new ArrayList<Object>(runContext.render(this.formats).asList(String.class));
        if (!renderedFormats.isEmpty()) {
            builder.formats(renderedFormats);
        }

        runContext.render(this.onlyMainContent).as(Boolean.class).ifPresent(builder::onlyMainContent);

        var renderedIncludeTags = runContext.render(this.includeTags).asList(String.class);
        if (!renderedIncludeTags.isEmpty()) {
            builder.includeTags(renderedIncludeTags);
        }

        var renderedExcludeTags = runContext.render(this.excludeTags).asList(String.class);
        if (!renderedExcludeTags.isEmpty()) {
            builder.excludeTags(renderedExcludeTags);
        }

        runContext.render(this.waitFor).as(Duration.class)
            .ifPresent(duration -> builder.waitFor(Math.toIntExact(duration.toMillis())));
        runContext.render(this.requestTimeout).as(Duration.class)
            .ifPresent(duration -> builder.timeout(Math.toIntExact(duration.toMillis())));

        return builder.build();
    }
}
