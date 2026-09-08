(ns tools.agents.mcp.stdio-child
  "The SERVER half of the stdio end-to-end test: a real process, launched by
   `tools.agents.mcp.stdio/connect!` over real pipes.

   It serves `examples.mcp.weather/weather-server` — the verbatim port of the
   official 'Build an MCP server' tutorial — with the tutorial's HTTP leaf
   replaced by canned NWS payloads. That substitution is the only difference
   from what `examples.mcp.weather/-main` runs: same server, same tools, same
   framing, no network. The tutorial's own advice is that a stdio server must
   never print to stdout, and this file is where that is actually put to the
   test, because anything it leaks would corrupt the client's parse.

   A .clj file, not .cljc, deliberately: it is
   never given this namespace."
  (:require [examples.mcp.weather :as weather]
            [tools.agents.mcp.stdio :as stdio])
  (:gen-class))

(def alerts-payload
  {"features"
   [{"properties" {"event" "Flood Warning"
                   "areaDesc" "Sacramento County"
                   "severity" "Moderate"
                   "description" "Rivers are high."
                   "instruction" "Avoid low crossings."}}]})

(def points-payload
  {"properties" {"forecast" "https://api.weather.gov/gridpoints/TEST/1,2/forecast"}})

(def forecast-payload
  {"properties"
   {"periods" [{"name" "Tonight" "temperature" 55 "temperatureUnit" "F"
                "windSpeed" "5 mph" "windDirection" "NW"
                "detailedForecast" "Clear."}
               {"name" "Tuesday" "temperature" 72 "temperatureUnit" "F"
                "windSpeed" "10 mph" "windDirection" "W"
                "detailedForecast" "Sunny."}]}})

(defn canned-fetch
  "Stands in for `make_nws_request`. Returns nil for an unknown URL, which is
   exactly what the tutorial's own error handling produces on a failure."
  [url]
  (cond
    (re-find #"/alerts/active" url) alerts-payload
    (re-find #"/points/" url) points-payload
    (re-find #"/forecast" url) forecast-payload
    :else nil))

(defn -main [& _]
  ;; Written to stderr on purpose: a line of this on stdout would break the
  ;; client, and the test asserts the client is not broken.
  (stdio/log! "stdio-child: weather server up (canned NWS payloads)")
  (stdio/serve! (weather/weather-server canned-fetch)))
