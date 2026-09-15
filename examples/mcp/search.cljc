(ns examples.mcp.search
  "Port of mcp-servers/search-mcp (this repo's TypeScript sibling,
   `@modelcontextprotocol/sdk` + `bun run`) onto tools.agents.mcp — same
   single tool, same env var, same error contract, now on stdio via
   tools.agents.mcp.server/stdio instead of the TS SDK.

   The TypeScript original:

     const SEARXNG_URL = process.env.SEARXNG_URL ?? \"https://sear.xng\";
     const server = new McpServer({ name: \"search-mcp\", version: \"1.0.0\" });
     server.tool(\"search\", \"Search the web via a self-hosted SearXNG instance. \\
       Returns results as JSON.\", { query: z.string().describe(\"Search query\") },
       async ({ query }) => {
         const url = `${SEARXNG_URL}/search?${new URLSearchParams({ q: query, format: \"json\" })}`;
         try {
           const res = await fetch(url, { tls: { rejectUnauthorized: false }, ... });
           if (!res.ok) return { content: [{ type: \"text\", text: `SEARXNG_UNAVAILABLE: HTTP ${res.status} ${res.statusText}` }] };
           return { content: [{ type: \"text\", text: JSON.stringify(await res.json()) }] };
         } catch (e) {
           return { content: [{ type: \"text\", text: `SEARXNG_UNAVAILABLE: ${e.message}` }] };
         }
       });

   DIVERGENCE, and the reason this is safe to drop: the TS original's
   `tls: { rejectUnauthorized: false }` was a Bun-only workaround, not a
   SearXNG requirement — Bun/Node's `fetch()` does not consult the macOS
   System keychain, so it rejects sear.xng's home-CA-signed certificate
   outright unless `NODE_EXTRA_CA_CERTS` is *also* set (see this project's
   `feedback_searxng_mcp` memory). `http-get!` below does plain, verified
   TLS on every runtime; there is no insecure-mode escape hatch to port,
   since neither this library's own leaves (`tools.agents.anthropic`'s
   `http-post!`, `tools.agents.openai`'s) nor `:bb`/`:clj` expose one. Both
   go through the JVM trust store, so if sear.xng's CA is not already in the
   runtime JVM's `cacerts`, it must be imported there; not exercised by the
   test suite below, which injects `fetch` and never touches the network.

   PORTABILITY: same shape as examples/mcp/weather.cljc — one leaf,
   `http-get!`, isolated with a #?(:bb ... :clj ...) reader
   conditional; `search!` above it is plain, portable clojure.core, and takes
   its `http-get!` as an argument so it is exact rather than approximate to
   test."
  (:require [tools.agents.mcp :as mcp]
            [tools.agents.mcp.server :as server]
            [tools.agents.mcp.stdio :as stdio]
            #?@(:bb [[babashka.http-client]] :clj [])))


(def default-searxng-url "https://sear.xng")

(defn- url-encode
  "Percent-encode a query-string value. `URLSearchParams` did this in the TS
   original; the JDK's `URLEncoder` here."
  [s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn http-get!
  "GET url. Returns {:status int :body string} on ANY HTTP response (2xx or
   not) — same contract as this repo's own `http-post!` leaves in
   tools.agents.anthropic/tools.agents.openai: callers classify status
   themselves. Throws only on a genuine transport failure (DNS, connection
   refused, TLS handshake failure — no response at all), which `search!`
   below catches exactly where the TS original's try/catch did."
  [url]
  #?(:bb
     (let [resp (babashka.http-client/get url {:throw false})]
       {:status (:status resp) :body (:body resp)})
     :clj
     (let [req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                   (.timeout (java.time.Duration/ofSeconds 5))
                   (.GET)
                   (.build))
           resp (.send (java.net.http.HttpClient/newHttpClient) req
                       (java.net.http.HttpResponse$BodyHandlers/ofString))]
       {:status (.statusCode resp) :body (.body resp)})))

(defn search!
  "Port of the TS handler's body (minus the tool-result wrapping, added by
   `search-server` below). `fetch` stands in for `http-get!` so this is
   testable without the network — same pattern as weather.cljc's
   `get-alerts`/`get-forecast`.

   Returns the SearXNG JSON response body verbatim on a 2xx/3xx response
   (matching the original's `JSON.stringify(await res.json())`, which is a
   re-serialization of exactly the same bytes), or a `SEARXNG_UNAVAILABLE:`-
   prefixed string on any non-2xx status or transport failure — the
   original's swallow-everything contract, unchanged."
  [fetch searxng-url query]
  (try
    (let [url (str searxng-url "/search?q=" (url-encode query) "&format=json")
          {:keys [status body]} (fetch url)]
      (if (< status 400)
        body
        (str "SEARXNG_UNAVAILABLE: HTTP " status)))
    (catch Exception e
      (str "SEARXNG_UNAVAILABLE: " (ex-message e)))))

(defn search-server
  "`new McpServer({ name: \"search-mcp\", version: \"1.0.0\" })` plus its one
   `server.tool(\"search\", ...)` registration."
  ([] (search-server http-get! (or (System/getenv "SEARXNG_URL") default-searxng-url)))
  ([fetch searxng-url]
   (server/server
    {:name "search-mcp"
     :version "1.0.0"
     :tools
     [{:name "search"
       :description "Search the web via a self-hosted SearXNG instance. Returns results as JSON."
       :input-schema {"type" "object"
                      "properties" {"query" {"type" "string" "description" "Search query"}}
                      "required" ["query"]}
       :handler (fn [_ctx args] (search! fetch searxng-url (get args "query")))}]})))

;; TS original's `await server.connect(new StdioServerTransport())`.
(defn -main [& _]
  (stdio/log! "search-mcp MCP server starting on stdio")
  (stdio/serve! (search-server)))
