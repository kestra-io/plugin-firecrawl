# Kestra Firecrawl Plugin

## What

- Provides plugin components under `io.kestra.plugin.firecrawl`.
- Includes classes such as `Scrape`, `Crawl`.

## Why

- What user problem does this solve? Flows that feed RAG pipelines, monitor web pages or collect web data need clean page content from Firecrawl without rewriting the asynchronous crawl loop.
- Why would a team adopt this plugin in a workflow? It scrapes or crawls sites in one task, stores the results in internal storage and cancels crawls when the task is killed.
- What operational/business outcome does it enable? Web ingestion becomes a declarative, retryable workflow step.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `firecrawl`

Infrastructure dependencies (Docker Compose services):

- none (tests use WireMock)

### Key Plugin Classes

- `io.kestra.plugin.firecrawl.Scrape`
- `io.kestra.plugin.firecrawl.Crawl`

### Project Structure

```
plugin-firecrawl/
├── src/main/java/io/kestra/plugin/firecrawl/
├── src/test/java/io/kestra/plugin/firecrawl/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
