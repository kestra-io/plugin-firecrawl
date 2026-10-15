Use the Firecrawl plugin to turn web pages into clean, LLM-ready content inside a flow.

## What this plugin ships

- `Scrape` scrapes a single URL and returns its content as markdown, HTML, links or other formats.
- `Crawl` crawls a website from a start URL, waits for the crawl to finish and stores every page in the internal storage.

## How to use it

1. Create a Firecrawl API key and store it as a Kestra secret, for example `FIRECRAWL_API_KEY`.
2. Reference it with `apiKey: "{{ secret('FIRECRAWL_API_KEY') }}"` on each task.
3. Use the `uri` output, an ION file with one row per page, in downstream tasks such as `io.kestra.plugin.serdes.json.IonToJson` or a database or vector store loader.

To target a self-hosted Firecrawl instance, set `baseUrl` to its address without the `/v2` suffix.
