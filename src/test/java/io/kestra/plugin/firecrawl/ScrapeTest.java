package io.kestra.plugin.firecrawl;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.firecrawl.errors.AuthenticationException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

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

        var runContext = runContextFactory.of(Map.of());

        var task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getMarkdown(), is("# Example Domain"));
        assertThat(output.getTitle(), is("Example Domain"));
        assertThat(output.getStatusCode(), is(200));
        assertThat(output.getUri(), notNullValue());

        var rows = IonRows.read(runContext, output.getUri());
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

        var task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com/blog"))
            .formats(Property.ofValue(List.of("markdown", "links")))
            .onlyMainContent(Property.ofValue(true))
            .excludeTags(Property.ofValue(List.of("nav")))
            .waitFor(Property.ofValue(Duration.ofSeconds(2)))
            .requestTimeout(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

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
        var largeMarkdown = "a".repeat(Scrape.MAX_INLINE_MARKDOWN_LENGTH + 1);
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(okJson("{\"success\": true, \"data\": {\"markdown\": \"" + largeMarkdown + "\"}}")));

        var runContext = runContextFactory.of(Map.of());

        var task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com/large"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getMarkdown(), nullValue());
        assertThat(IonRows.read(runContext, output.getUri()).getFirst().get("markdown"), is(largeMarkdown));
    }

    @Test
    void shouldKeepNonAsciiContent(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(okJson("{\"success\": true, \"data\": {\"markdown\": \"# Caf\u00e9 \u65e5\u672c\u8a9e \u2713\"}}")));

        var runContext = runContextFactory.of(Map.of());

        var task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com/unicode"))
            .build();

        var output = task.run(runContext);

        assertThat(IonRows.read(runContext, output.getUri()).getFirst().get("markdown"), is("# Caf\u00e9 \u65e5\u672c\u8a9e \u2713"));
    }

    @Test
    void shouldRenderProperties(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .withHeader("Authorization", equalTo("Bearer fc-rendered"))
            .willReturn(okJson("{\"success\": true, \"data\": {\"markdown\": \"ok\", \"metadata\": {\"statusCode\": 200}}}")));

        var runContext = runContextFactory.of(Map.of(
            "key", "fc-rendered",
            "target", "https://example.com/rendered"
        ));

        var task = Scrape.builder()
            .apiKey(Property.ofExpression("{{ key }}"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofExpression("{{ target }}"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getMarkdown(), is("ok"));
        verify(postRequestedFor(urlEqualTo("/v2/scrape"))
            .withRequestBody(containing("\"url\":\"https://example.com/rendered\"")));
    }

    @Test
    void shouldFailWhenApiReturnsNoContent(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(okJson("{\"success\": false, \"error\": \"DNS resolution failed\", \"code\": \"SCRAPE_DNS_RESOLUTION_ERROR\"}")));

        var task = Scrape.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://does-not-exist.invalid"))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(exception.getMessage(), containsString("Firecrawl returned no content for https://does-not-exist.invalid"));
    }

    @Test
    void shouldFailWithInvalidApiKey(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v2/scrape"))
            .willReturn(unauthorized().withHeader("Content-Type", "application/json").withBody("{\"success\": false, \"error\": \"Unauthorized\"}")));

        var task = Scrape.builder()
            .apiKey(Property.ofValue("invalid"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com"))
            .build();

        assertThrows(AuthenticationException.class, () -> task.run(runContextFactory.of(Map.of())));
    }
}
