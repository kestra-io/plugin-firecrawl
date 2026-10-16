Use the [Firecrawl](https://docs.firecrawl.dev) plugin to turn web pages into clean, LLM-ready content inside a flow, for example to feed RAG pipelines, monitor pages or collect web data.

## Authentication

Create a Firecrawl API key and store it as a [Kestra secret](https://kestra.io/docs/concepts/secret), then reference it on each task:

```yaml
apiKey: "{{ secret('FIRECRAWL_API_KEY') }}"
```

To avoid repeating it on every task, set it once with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) for `io.kestra.plugin.firecrawl`.

To target a self-hosted Firecrawl instance, set `baseUrl` to its address without the `/v2` suffix. It defaults to `https://api.firecrawl.dev`.

## Tasks

Both tasks write their result to the internal storage as an ION file, with one row per page, which can be used by downstream tasks such as `io.kestra.plugin.serdes.json.IonToJson` or a database loader.

### Scrape

Scrapes a single URL.

- Required: `apiKey`, `url`.
- Optional: `baseUrl`, `formats` (defaults to `markdown`), `onlyMainContent`, `includeTags`, `excludeTags`, `waitFor`, `requestTimeout`.
- Outputs: `uri`, plus `markdown` (only when under 100,000 characters), `title` and `statusCode`.

### Crawl

Crawls a site from a start URL, waits for the crawl to finish and stores every page.

- Required: `apiKey`, `url`.
- Optional: `baseUrl`, `limit` (defaults to 100), `includePaths`, `excludePaths`, `maxDiscoveryDepth`, `crawlEntireDomain`, `allowExternalLinks`, `allowSubdomains`, `pollFrequency` (defaults to 5 seconds, at least 1 second), `maxDuration` (defaults to 60 minutes), and the same page options as `Scrape`.
- Outputs: `uri`, `crawlId`, `status`, `pagesCount`, `creditsUsed`.
- Metrics: `pages`, `credits`.
- The crawl is cancelled on the Firecrawl side when the task is killed, exceeds `maxDuration` or fails while waiting, so it stops consuming credits.
