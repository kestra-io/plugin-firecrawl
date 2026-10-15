package io.kestra.plugin.firecrawl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import org.junit.jupiter.api.Test;

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
            .pollFrequency(Property.ofValue(Duration.ofMillis(50)));
    }

    @Test
    void shouldCrawlAndStorePages(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/crawl"))
            .withHeader("Authorization", equalTo("Bearer fc-test"))
            .willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("""
                {
                  "status": "completed",
                  "total": 2,
                  "completed": 2,
                  "creditsUsed": 2,
                  "data": [
                    {"markdown": "# Page 1", "metadata": {"sourceURL": "https://example.com/docs/1"}},
                    {"markdown": "# Page 2", "metadata": {"sourceURL": "https://example.com/docs/2"}}
                  ]
                }
                """)));

        RunContext runContext = runContextFactory.of(Map.of());

        Crawl.Output output = task(wmRuntimeInfo)
            .limit(Property.ofValue(2))
            .includePaths(Property.ofValue(List.of("/docs/.*")))
            .build()
            .run(runContext);

        assertThat(output.getCrawlId(), is("crawl-1"));
        assertThat(output.getStatus(), is("completed"));
        assertThat(output.getPagesCount(), is(2));
        assertThat(output.getCreditsUsed(), is(2));

        List<Map> rows = readRows(runContext, output);
        assertThat(rows.size(), is(2));
        assertThat(rows.getFirst().get("markdown"), is("# Page 1"));

        verify(postRequestedFor(urlEqualTo("/v2/crawl"))
            .withRequestBody(containing("\"url\":\"https://example.com/docs\""))
            .withRequestBody(containing("\"limit\":2"))
            .withRequestBody(containing("\"includePaths\":[\"/docs/.*\"]")));
    }

    @Test
    void shouldFollowNextPageCursor(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("""
                {
                  "status": "completed",
                  "total": 3,
                  "completed": 3,
                  "next": "%s/v2/crawl/crawl-1?skip=2",
                  "data": [{"markdown": "# Page 1"}, {"markdown": "# Page 2"}]
                }
                """.formatted(wmRuntimeInfo.getHttpBaseUrl()))));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1?skip=2"))
            .willReturn(okJson("""
                {
                  "status": "completed",
                  "total": 3,
                  "completed": 3,
                  "data": [{"markdown": "# Page 3"}]
                }
                """)));

        RunContext runContext = runContextFactory.of(Map.of());

        Crawl.Output output = task(wmRuntimeInfo).build().run(runContext);

        assertThat(output.getPagesCount(), is(3));
        assertThat(readRows(runContext, output).getLast().get("markdown"), is("# Page 3"));
    }

    @Test
    void shouldPollUntilCompleted(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .inScenario("crawl")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(okJson("{\"status\": \"scraping\", \"total\": 1, \"completed\": 0, \"data\": []}"))
            .willSetStateTo("done"));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .inScenario("crawl")
            .whenScenarioStateIs("done")
            .willReturn(okJson("{\"status\": \"completed\", \"total\": 1, \"completed\": 1, \"data\": [{\"markdown\": \"# Done\"}]}")));

        Crawl.Output output = task(wmRuntimeInfo).build().run(runContextFactory.of(Map.of()));

        assertThat(output.getPagesCount(), is(1));
        verify(2, getRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
    }

    @Test
    void shouldFailWhenCrawlFails(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"failed\", \"total\": 0, \"completed\": 0, \"data\": []}")));

        Crawl task = task(wmRuntimeInfo).build();

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> task.run(runContextFactory.of(Map.of()))
        );

        assertThat(exception.getMessage().contains("failed"), is(true));
    }

    @Test
    void shouldCancelCrawlWhenMaxDurationIsExceeded(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"scraping\", \"total\": 10, \"completed\": 1, \"data\": []}")));
        stubFor(delete(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"cancelled\"}")));

        Crawl task = task(wmRuntimeInfo)
            .maxDuration(Property.ofValue(Duration.ofMillis(500)))
            .build();

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> task.run(runContextFactory.of(Map.of()))
        );

        assertThat(exception.getMessage().contains("did not finish"), is(true));
        verify(1, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));
    }

    @Test
    void shouldCancelCrawlWhenKilled(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"scraping\", \"total\": 10, \"completed\": 1, \"data\": []}")));
        stubFor(delete(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"cancelled\"}")));

        Crawl task = task(wmRuntimeInfo).build();
        RunContext runContext = runContextFactory.of(Map.of());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Crawl.Output> future = executor.submit(() -> task.run(runContext));

            // wait until the crawl is started and being polled
            long deadline = System.currentTimeMillis() + 10_000;
            while (findAll(getRequestedFor(urlEqualTo("/v2/crawl/crawl-1"))).isEmpty()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("The crawl was never polled");
                }
                Thread.sleep(25);
            }

            stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
                .willReturn(okJson("{\"status\": \"cancelled\", \"total\": 10, \"completed\": 1, \"data\": []}")));
            task.kill();

            verify(1, deleteRequestedFor(urlEqualTo("/v2/crawl/crawl-1")));

            ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertThat(exception.getCause(), instanceOf(IllegalStateException.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRenderProperties(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"completed\", \"total\": 1, \"completed\": 1, \"data\": [{\"markdown\": \"# Page\"}]}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "startUrl", "https://example.com/rendered",
            "pageLimit", 5
        ));

        Crawl.Output output = task(wmRuntimeInfo)
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
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"completed\", \"total\": 0, \"completed\": 0, \"data\": []}")));

        RunContext runContext = runContextFactory.of(Map.of());

        Crawl.Output output = task(wmRuntimeInfo).build().run(runContext);

        assertThat(output.getPagesCount(), is(0));
        assertThat(output.getCreditsUsed(), nullValue());
        assertThat(readRows(runContext, output).isEmpty(), is(true));
    }

    @Test
    void shouldFailWhenCrawlIsCancelled(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(post(urlEqualTo("/v2/crawl")).willReturn(okJson(START_RESPONSE)));
        stubFor(get(urlEqualTo("/v2/crawl/crawl-1"))
            .willReturn(okJson("{\"status\": \"cancelled\", \"total\": 0, \"completed\": 0, \"data\": []}")));

        Crawl task = task(wmRuntimeInfo).build();

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> task.run(runContextFactory.of(Map.of()))
        );

        assertThat(exception.getMessage().contains("cancelled"), is(true));
    }

    @Test
    void shouldNotCancelAnythingWhenKilledBeforeStart(WireMockRuntimeInfo wmRuntimeInfo) {
        Crawl task = task(wmRuntimeInfo).build();

        task.kill();

        verify(0, deleteRequestedFor(urlMatching("/v2/crawl/.*")));
    }

    private List<Map> readRows(RunContext runContext, Crawl.Output output) throws Exception {
        return FileSerde.readAll(
            new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8)),
            Map.class
        ).collectList().block();
    }
}
