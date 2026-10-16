package io.kestra.plugin.firecrawl;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.firecrawl.errors.FirecrawlException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class CrawlTest {

    private static final String START_RESPONSE = """
        {"success": true, "id": "crawl-1", "url": "https://api.firecrawl.dev/v2/crawl/crawl-1"}
        """;

    @Inject
    private RunContextFactory runContextFactory;

    private Crawl.CrawlBuilder<?, ?> task(WireMockRuntimeInfo wmRuntimeInfo) {
        return Crawl.builder()
            .apiKey(Property.ofValue("fc-test"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .url(Property.ofValue("https://example.com/docs"))
            .pollFrequency(Property.ofValue(Duration.ofSeconds(1)));
    }

    private static void stubStart() {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
    }

    private static void stubStatus(String url, String status, int total, String data, String extra) {
        stubFor(get(urlEqualTo(url))
            .willReturn(okJson("{\"status\": \"" + status + "\", \"total\": " + total + ", \"completed\": " + total
                + extra + ", \"data\": " + data + "}")));
    }

    @Test
    void shouldCrawlAndStorePages(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/crawl"))
            .withHeader("Authorization", equalTo("Bearer fc-test"))
            .willReturn(okJson(START_RESPONSE)));
        stubStatus("/v2/crawl/crawl-1", "completed", 2, """
            [
              {"markdown": "# Page 1", "metadata": {"sourceURL": "https://example.com/docs/1"}},
              {"markdown": "# Page 2", "metadata": {"sourceURL": "https://example.com/docs/2"}}
            ]
            """, ", \"creditsUsed\": 2");

        var runContext = runContextFactory.of(Map.of());

        var output = task(wmRuntimeInfo)
            .limit(Property.ofValue(2))
            .includePaths(Property.ofValue(List.of("/docs/.*")))
            .maxDiscoveryDepth(Property.ofValue(3))
            .build()
            .run(runContext);

        assertThat(output.getCrawlId(), is("crawl-1"));
        assertThat(output.getStatus(), is("completed"));
        assertThat(output.getPagesCount(), is(2));
        assertThat(output.getCreditsUsed(), is(2));

        var rows = IonRows.read(runContext, output.getUri());
        assertThat(rows.size(), is(2));
        assertThat(rows.getFirst().get("markdown"), is("# Page 1"));

        verify(postRequestedFor(urlEqualTo("/v2/crawl"))
            .withRequestBody(containing("\"url\":\"https://example.com/docs\""))
            .withRequestBody(containing("\"limit\":2"))
            .withRequestBody(containing("\"maxDiscoveryDepth\":3"))
            .withRequestBody(containing("\"includePaths\":[\"/docs/.*\"]")));
    }

    @Test
    void shouldUseDefaultLimit(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "completed", 1, "[{\"markdown\": \"# Page\"}]", "");

        task(wmRuntimeInfo).build().run(runContextFactory.of(Map.of()));

        verify(postRequestedFor(urlEqualTo("/v2/crawl")).withRequestBody(containing("\"limit\":100")));
    }

    @Test
    void shouldFollowEveryNextPageCursor(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        var baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "completed", 5, "[{\"markdown\": \"# 1\"}, {\"markdown\": \"# 2\"}]",
            ", \"next\": \"" + baseUrl + "/v2/crawl/crawl-1?skip=2\"");
        stubStatus("/v2/crawl/crawl-1?skip=2", "completed", 5, "[{\"markdown\": \"# 3\"}, {\"markdown\": \"# 4\"}]",
            ", \"next\": \"" + baseUrl + "/v2/crawl/crawl-1?skip=4\"");
        stubStatus("/v2/crawl/crawl-1?skip=4", "completed", 5, "[{\"markdown\": \"# 5\"}]", "");

        var runContext = runContextFactory.of(Map.of());

        var output = task(wmRuntimeInfo).build().run(runContext);

        var rows = IonRows.read(runContext, output.getUri());
        assertThat(output.getPagesCount(), is(5));
        assertThat(rows.stream().map(row -> row.get("markdown")).toList(), is(List.of("# 1", "# 2", "# 3", "# 4", "# 5")));
        verify(1, getRequestedFor(urlEqualTo("/v2/crawl/crawl-1?skip=2")));
        verify(1, getRequestedFor(urlEqualTo("/v2/crawl/crawl-1?skip=4")));
    }

    @Test
    void shouldStopFollowingCursorsAtTheLimit(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "completed", 4, "[{\"markdown\": \"# 1\"}, {\"markdown\": \"# 2\"}]",
            ", \"next\": \"" + wmRuntimeInfo.getHttpBaseUrl() + "/v2/crawl/crawl-1?skip=2\"");
        stubStatus("/v2/crawl/crawl-1?skip=2", "completed", 4, "[{\"markdown\": \"# 3\"}, {\"markdown\": \"# 4\"}]", "");

        var output = task(wmRuntimeInfo).limit(Property.ofValue(2)).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getPagesCount(), is(2));
        verify(0, getRequestedFor(urlEqualTo("/v2/crawl/crawl-1?skip=2")));
    }

    @Test
    void shouldStopWhenTheServerRepeatsACursor(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        var next = ", \"next\": \"" + wmRuntimeInfo.getHttpBaseUrl() + "/v2/crawl/crawl-1?skip=2\"";
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "completed", 3, "[{\"markdown\": \"# 1\"}, {\"markdown\": \"# 2\"}]", next);
        stubStatus("/v2/crawl/crawl-1?skip=2", "completed", 3, "[{\"markdown\": \"# 3\"}]", next);

        var output = task(wmRuntimeInfo).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getPagesCount(), is(3));
        verify(1, getRequestedFor(urlEqualTo("/v2/crawl/crawl-1?skip=2")));
    }

    @Test
    void shouldPollUntilCompleted(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubStart();
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .inScenario("crawl")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"status\": \"scraping\", \"total\": 1, \"completed\": 0, \"data\": []}"))
            .willSetStateTo("done"));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .inScenario("crawl")
            .whenScenarioStateIs("done")
            .willReturn(okJson("{\"status\": \"completed\", \"total\": 1, \"completed\": 1, \"data\": [{\"markdown\": \"# Done\"}]}")));

        var output = task(wmRuntimeInfo).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getPagesCount(), is(1));
        verify(2, getRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
        verify(0, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
    }

    @Test
    void shouldKeepPollingWhenRateLimited(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubStart();
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .inScenario("rate-limit")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json").withBody("{\"error\": \"Too many requests\"}"))
            .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .inScenario("rate-limit")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson("{\"status\": \"completed\", \"total\": 1, \"completed\": 1, \"data\": [{\"markdown\": \"# Page\"}]}")));

        var output = task(wmRuntimeInfo).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getPagesCount(), is(1));
        verify(0, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
    }

    @Test
    void shouldFailWithoutCancellingWhenCrawlFails(WireMockRuntimeInfo wmRuntimeInfo) {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "failed", 0, "[]", "");

        var task = task(wmRuntimeInfo).build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(exception.getMessage(), containsString("failed"));
        verify(0, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
    }

    @Test
    void shouldFailWhenCrawlIsCancelled(WireMockRuntimeInfo wmRuntimeInfo) {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "cancelled", 0, "[]", "");

        var task = task(wmRuntimeInfo).build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(exception.getMessage(), containsString("cancelled"));
    }

    @Test
    void shouldCancelCrawlWhenPollingFails(WireMockRuntimeInfo wmRuntimeInfo) {
        stubStart();
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(forbidden().withHeader("Content-Type", "application/json").withBody("{\"error\": \"Forbidden\"}")));
        stubFor(delete(urlEqualTo("/v2/crawl/crawl-1")).willReturn(okJson("{\"status\": \"cancelled\"}")));

        var task = task(wmRuntimeInfo).build();

        assertThrows(FirecrawlException.class, () -> task.run(runContextFactory.of(Map.of())));

        verify(1, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
    }

    @Test
    void shouldCancelCrawlWhenMaxDurationIsExceeded(WireMockRuntimeInfo wmRuntimeInfo) {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "scraping", 10, "[]", "");
        stubFor(delete(urlEqualTo("/v2/crawl/crawl-1")).willReturn(okJson("{\"status\": \"cancelled\"}")));

        var task = task(wmRuntimeInfo)
            .maxDuration(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(exception.getMessage(), containsString("did not finish within"));
        verify(1, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
    }

    @Test
    void shouldCancelCrawlOnceWhenKilled(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "scraping", 10, "[]", "");
        stubFor(delete(urlEqualTo("/v2/crawl/crawl-1")).willReturn(okJson("{\"status\": \"cancelled\"}")));

        var task = task(wmRuntimeInfo).build();
        var runContext = runContextFactory.of(Map.of());

        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> task.run(runContext));

            var deadline = System.currentTimeMillis() + 10_000;
            while (findAll(getRequestedFor(urlEqualTo("/v2/crawl/crawl-1"))).isEmpty()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("The crawl was never polled");
                }
                Thread.sleep(25);
            }

            stubStatus("/v2/crawl/crawl-1", "cancelled", 10, "[]", "");
            task.kill();
            task.kill();

            verify(1, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));

            var exception = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertThat(exception.getCause(), instanceOf(IllegalStateException.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotCancelAnythingWhenKilledBeforeStart(WireMockRuntimeInfo wmRuntimeInfo) {
        task(wmRuntimeInfo).build().kill();

        verify(0, deleteRequestedFor(urlMatching("/v2/crawl/.*")));
    }

    @Test
    void shouldRenderProperties(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "completed", 1, "[{\"markdown\": \"# Page\"}]", "");

        var runContext = runContextFactory.of(Map.of(
            "startUrl", "https://example.com/rendered",
            "pageLimit", 5
        ));

        var output = task(wmRuntimeInfo)
            .url(Property.ofExpression("{{ startUrl }}"))
            .limit(Property.ofExpression("{{ pageLimit }}"))
            .build()
            .run(runContext);

        assertThat(output.getPagesCount(), is(1));
        verify(postRequestedFor(urlEqualTo("/v2/crawl"))
            .withRequestBody(containing("\"url\":\"https://example.com/rendered\""))
            .withRequestBody(containing("\"limit\":5")));
    }

    @Test
    void shouldHandleCrawlWithoutPages(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubStart();
        stubStatus("/v2/crawl/crawl-1", "completed", 0, "[]", "");

        var runContext = runContextFactory.of(Map.of());

        var output = task(wmRuntimeInfo).build().run(runContext);

        assertThat(output.getPagesCount(), is(0));
        assertThat(output.getCreditsUsed(), nullValue());
        assertThat(IonRows.read(runContext, output.getUri()).isEmpty(), is(true));
    }

    @Test
    void shouldRejectInvalidProperties(WireMockRuntimeInfo wmRuntimeInfo) {
        var runContext = runContextFactory.of(Map.of());

        assertInvalid(task(wmRuntimeInfo).limit(Property.ofValue(0)), "limit");
        assertInvalid(task(wmRuntimeInfo).limit(Property.ofValue(-5)), "limit");
        assertInvalid(task(wmRuntimeInfo).maxDiscoveryDepth(Property.ofValue(-1)), "maxDiscoveryDepth");
        assertInvalid(task(wmRuntimeInfo).pollFrequency(Property.ofValue(Duration.ZERO)), "pollFrequency");
        assertInvalid(task(wmRuntimeInfo).pollFrequency(Property.ofValue(Duration.ofMillis(200))), "pollFrequency");
        assertInvalid(task(wmRuntimeInfo).maxDuration(Property.ofValue(Duration.ZERO)), "maxDuration");

        verify(0, postRequestedFor(urlEqualTo("/v2/crawl")));
    }

    private void assertInvalid(Crawl.CrawlBuilder<?, ?> builder, String property) {
        var task = builder.build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(exception.getMessage(), containsString(property));
    }
}
