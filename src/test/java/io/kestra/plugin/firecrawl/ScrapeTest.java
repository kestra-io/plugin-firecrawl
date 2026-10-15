package io.kestra.plugin.firecrawl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.firecrawl.errors.AuthenticationException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class ScrapeTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldScrapeMarkdown(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .withHeader("Authorization", equalTo("Bearer fc-test"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "data": {
                    "markdown": "# Example Domain",
                    "metadata": {"title": "Example Domain", "statusCode": 200, "sourceURL": "https://example.com"}
                  }
                }
                """)));

        RunContext runContext = runContextFactory.of(Map.of());

        Scrape task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com"))
            .build();

        Scrape.Output output = task.run(runContext);

        assertThat(output.getMarkdown(), is("# Example Domain"));
        assertThat(output.getTitle(), is("Example Domain"));
        assertThat(output.getStatusCode(), is(200));
        assertThat(output.getUri(), notNullValue());

        List<Map> rows = FileSerde.readAll(
            new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8)),
            Map.class
        ).collectList().block();

        assertThat(rows.size(), is(1));
        assertThat(rows.getFirst().get("markdown"), is("# Example Domain"));

        verify(postRequestedFor(urlEqualTo("/v2/scrape"))
            .withRequestBody(containing("\"url\":\"https://example.com\""))
            .withRequestBody(containing("\"markdown\"")));
    }

    @Test
    void shouldSendScrapeOptions(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(okJson("""
                {"success": true, "data": {"markdown": "content", "links": ["https://example.com/a"], "metadata": {"statusCode": 200}}}
                """)));

        Scrape task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com/blog"))
            .formats(Property.ofValue(List.of("markdown", "links")))
            .onlyMainContent(Property.ofValue(true))
            .excludeTags(Property.ofValue(List.of("nav")))
            .waitFor(Property.ofValue(Duration.ofSeconds(2)))
            .requestTimeout(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        Scrape.Output output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getTitle(), nullValue());

        verify(postRequestedFor(urlEqualTo("/v2/scrape"))
            .withRequestBody(containing("\"links\""))
            .withRequestBody(containing("\"onlyMainContent\":true"))
            .withRequestBody(containing("\"excludeTags\":[\"nav\"]"))
            .withRequestBody(containing("\"waitFor\":2000"))
            .withRequestBody(containing("\"timeout\":30000")));
    }

    @Test
    void shouldNotInlineLargeMarkdown(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String largeMarkdown = "a".repeat(Scrape.MAX_INLINE_MARKDOWN_LENGTH + 1);
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(okJson("{\"success\": true, \"data\": {\"markdown\": \"" + largeMarkdown + "\"}}")));

        RunContext runContext = runContextFactory.of(Map.of());

        Scrape task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com/large"))
            .build();

        Scrape.Output output = task.run(runContext);

        assertThat(output.getMarkdown(), nullValue());

        String stored = new String(runContext.storage().getFile(output.getUri()).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(stored, containsString(largeMarkdown));
    }

    @Test
    void shouldRenderProperties(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .withHeader("Authorization", equalTo("Bearer fc-rendered"))
            .willReturn(okJson("{\"success\": true, \"data\": {\"markdown\": \"ok\", \"metadata\": {\"statusCode\": 200}}}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "key", "fc-rendered",
            "target", "https://example.com/rendered"
        ));

        Scrape task = Scrape.builder()
            .apiKey(Property.ofExpression("{{ key }}"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofExpression("{{ target }}"))
            .build();

        Scrape.Output output = task.run(runContext);

        assertThat(output.getMarkdown(), is("ok"));
        verify(postRequestedFor(urlEqualTo("/v2/scrape"))
            .withRequestBody(containing("\"url\":\"https://example.com/rendered\"")));
    }

    @Test
    void shouldFailWhenScrapeFails(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(okJson("{\"success\": false, \"error\": \"DNS resolution failed\", \"code\": \"SCRAPE_DNS_RESOLUTION_ERROR\"}")));

        Scrape task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://does-not-exist.invalid"))
            .build();

        // depending on the SDK version this is a FirecrawlException or an IllegalStateException raised by the task
        assertThrows(RuntimeException.class, () -> task.run(runContextFactory.of(Map.of())));
    }

    @Test
    void shouldFailWithInvalidApiKey(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(unauthorized().withHeader("Content-Type", "application/json").withBody("{\"success\": false, \"error\": \"Unauthorized\"}")));

        Scrape task = Scrape.builder()
            .apiKey(Property.ofValue("invalid"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com"))
            .build();

        assertThrows(AuthenticationException.class, () -> task.run(runContextFactory.of(Map.of())));
    }
}
