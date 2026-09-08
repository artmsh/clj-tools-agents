(ns examples.mcp.weather
  "Line-for-line port of the official 'Build an MCP server' tutorial at
   https://modelcontextprotocol.io/docs/2026-07-28/develop/build-server —
   the weather server with `get_alerts` and `get_forecast`. This example
   exists to VERIFY tools.agents.mcp against the reference: if the tutorial's
   server can be written here with the same shape, the same tool names and
   the same behaviour, the library covers what the tutorial teaches.

   The Python original:

     from typing import Any
     import httpx2
     from mcp.server import MCPServer

     mcp = MCPServer(\"weather\")
     NWS_API_BASE = \"https://api.weather.gov\"
     USER_AGENT = \"weather-app/1.0\"

     async def make_nws_request(url: str) -> dict[str, Any] | None:
         headers = {\"User-Agent\": USER_AGENT, \"Accept\": \"application/geo+json\"}
         async with httpx2.AsyncClient() as client:
             try:
                 response = await client.get(url, headers=headers, timeout=30.0)
                 response.raise_for_status()
                 return response.json()
             except Exception:
                 return None

     @mcp.tool()
     async def get_alerts(state: str) -> str:
         \"\"\"Get weather alerts for a US state.

         Args:
             state: Two-letter US state code (e.g. CA, NY)
         \"\"\"
         ...

     if __name__ == \"__main__\":
         mcp.run(transport=\"stdio\")

   ONE DELIBERATE DIVERGENCE, and it is the tutorial's own headline. Python
   writes `state: str` and a docstring, and the SDK derives the tool's JSON
   Schema, its description and its argument descriptions from them. Clojure
   has no type hints to derive a schema from, and inventing a
   metadata-scraping layer to fake one would fight this repo's
   data-transparency idiom — the same idiom that has tools.agents.anthropic
   pass `\"max_tokens\"` through verbatim rather than kebab-casing it. So the
   schema is written out as the JSON Schema it will be sent as. Everything
   else — the tool names, the argument names, the string return, the
   `transport=\"stdio\"` entry point — is the tutorial's.

   NOTE ON LOGGING, which the tutorial devotes a section to: a stdio server
   must never write to stdout, because that is the JSON-RPC channel.
   `stdio/log!` writes to stderr and is the equivalent of the tutorial's
   `logger.info(...)`. Plain `println` here would be the equivalent of its
   forbidden `print(...)`."
  (:require [clojure.string :as str]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.server :as server]
            [tools.agents.mcp.stdio :as stdio]
            #?@(:bb [[babashka.http-client]] :clj [])))

(def nws-api-base "https://api.weather.gov")
(def user-agent "weather-app/1.0")

(defn http-get-json!
  "GET a URL and decode its JSON body, or nil on any failure — the same
   swallow-everything contract as the tutorial's `make_nws_request`.

   The tutorial gets its HTTP client for free (`httpx2` is a dependency of
   `mcp` itself). This library has none, on purpose, so the leaf is here in
   the example rather than in the library: an MCP server does not inherently
   need an HTTP client, and the ones that do should choose their own."
  [url]
  (try
    #?(:bb
       (let [resp (babashka.http-client/get url {:headers {"User-Agent" user-agent
                                                           "Accept" "application/geo+json"}
                                                 :throw false})]
         (when (< (:status resp) 400) (mcp/read-json (:body resp))))
       :clj
       (let [req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                     (.header "User-Agent" user-agent)
                     (.header "Accept" "application/geo+json")
                     (.timeout (java.time.Duration/ofSeconds 30))
                     (.GET)
                     (.build))
             resp (.send (java.net.http.HttpClient/newHttpClient) req
                         (java.net.http.HttpResponse$BodyHandlers/ofString))]
         (when (< (.statusCode resp) 400) (mcp/read-json (.body resp)))))
    (catch Exception _ nil)))

(defn format-alert
  "Port of `format_alert`."
  [feature]
  (let [props (get feature "properties")
        v (fn [k d] (or (get props k) d))]
    (str "\nEvent: " (v "event" "Unknown")
         "\nArea: " (v "areaDesc" "Unknown")
         "\nSeverity: " (v "severity" "Unknown")
         "\nDescription: " (v "description" "No description available")
         "\nInstructions: " (v "instruction" "No specific instructions provided")
         "\n")))

(defn get-alerts
  "Port of `get_alerts`. `fetch` stands in for `make_nws_request` so this is
   testable without the network."
  [fetch state]
  (let [data (fetch (str nws-api-base "/alerts/active/area/" state))]
    (cond
      (or (nil? data) (not (contains? data "features")))
      "Unable to fetch alerts or no alerts found."
      (empty? (get data "features")) "No active alerts for this state."
      :else (str/join "\n---\n" (map format-alert (get data "features"))))))

(defn get-forecast
  "Port of `get_forecast`."
  [fetch latitude longitude]
  (let [points (fetch (str nws-api-base "/points/" latitude "," longitude))]
    (if-not points
      "Unable to fetch forecast data for this location."
      (let [forecast (fetch (get-in points ["properties" "forecast"]))]
        (if-not forecast
          "Unable to fetch detailed forecast."
          (str/join "\n---\n"
                    (map (fn [p]
                           (str "\n" (get p "name") ":"
                                "\nTemperature: " (get p "temperature") "°" (get p "temperatureUnit")
                                "\nWind: " (get p "windSpeed") " " (get p "windDirection")
                                "\nForecast: " (get p "detailedForecast")
                                "\n"))
                         (take 5 (get-in forecast ["properties" "periods"])))))))))

(defn weather-server
  "`MCPServer(\"weather\")` plus its two `@mcp.tool()` registrations."
  ([] (weather-server http-get-json!))
  ([fetch]
   (server/server
    {:name "weather"
     :version "1.0.0"
     :tools
     [{:name "get_alerts"
       :description "Get weather alerts for a US state."
       :input-schema {"type" "object"
                      "properties" {"state" {"type" "string"
                                             "description" "Two-letter US state code (e.g. CA, NY)"}}
                      "required" ["state"]}
       :handler (fn [_ctx args] (get-alerts fetch (get args "state")))}
      {:name "get_forecast"
       :description "Get weather forecast for a location."
       :input-schema {"type" "object"
                      "properties" {"latitude" {"type" "number" "description" "Latitude of the location"}
                                    "longitude" {"type" "number" "description" "Longitude of the location"}}
                      "required" ["latitude" "longitude"]}
       :handler (fn [_ctx args]
                  (get-forecast fetch (get args "latitude") (get args "longitude")))}]})))

;; `if __name__ == "__main__": mcp.run(transport="stdio")`
(defn -main [& _]
  (stdio/log! "weather MCP server starting on stdio")
  (stdio/serve! (weather-server)))
