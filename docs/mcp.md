# tools.agents.mcp

A pure-Clojure implementation of the [Model Context
Protocol](https://modelcontextprotocol.io/docs/2026-07-28), revision
**2026-07-28** — server, client, and both transports — ergonomically modeled
on the official [Python
SDK](https://github.com/modelcontextprotocol/python-sdk): a `server` config
map standing in for `MCPServer(...)`, descriptor maps standing in for its
`@mcp.tool()` / `@mcp.resource()` / `@mcp.prompt()` decorators, a handler
`ctx` map standing in for its `Context`, and a typed error hierarchy matching
its exception classes.

Runs unmodified on **JVM Clojure** and **Babashka**.

Third sibling of [tools.agents.anthropic](anthropic.md) and
[tools.agents.openai](openai.md) in this repo — same architecture (single
leaf for I/O, hand-rolled portable JSON codec, `ex-info`-with-`:type` error
hierarchy, plain maps rather than records, two-runtime test matrix). The
one architectural difference is forced by the problem: those two are clients
of an HTTP API, this one is a *protocol* implementation, so its core is a
**pure function** — `server/handle` takes a message and returns everything it
produced, and the transports are thin shells around it.

| Namespace | What it is |
|---|---|
| `tools.agents.mcp` | Protocol constants, JSON codec, JSON-RPC framing, typed errors, `_meta` helpers, content-block and MRTR constructors |
| `tools.agents.mcp.server` | `server` (registration), `handle` (pure dispatch), capabilities, pagination, URI templates |
| `tools.agents.mcp.client` | `client`, typed requests, MRTR retry loop, pagination walkers, the backward-compatibility probe |
| `tools.agents.mcp.stdio` | stdio transport, both halves (`serve!`, `connect!`) |
| `tools.agents.mcp.http` | Streamable HTTP transport, both halves (`handle-http`/`serve!`, `connect!`) |

## Usage

### A server

```clojure
(require '[tools.agents.mcp.server :as server]
         '[tools.agents.mcp.stdio :as stdio])

(def srv
  (server/server
   {:name "weather"
    :version "1.0.0"
    :tools
    [{:name "get_alerts"
      :description "Get weather alerts for a US state."
      :input-schema {"type" "object"
                     "properties" {"state" {"type" "string"
                                            "description" "Two-letter US state code"}}
                     "required" ["state"]}
      :handler (fn [ctx args]
                 ((:log! ctx) "info" (str "alerts for " (get args "state")))
                 (fetch-alerts (get args "state")))}]}))

(stdio/serve! srv)   ;; blocks until EOF on stdin
```

A tool handler returns a string, a vector of content blocks, or a full result
map — `mcp/tool-result`, `mcp/tool-error` and `mcp/text`/`mcp/image`/
`mcp/audio`/`mcp/resource-link`/`mcp/embedded-resource` build the latter two.

### A client

```clojure
(require '[tools.agents.mcp.client :as client]
         '[tools.agents.mcp.stdio :as stdio])

(def transport (stdio/connect! ["python" "-m" "my_server"] {}))
(def c (client/client {:name "my-app" :version "1.0.0"
                       :capabilities {"elicitation" {}}
                       :send! (:send! transport)}))

(client/discover! c)                                  ;; server/discover
(client/list-all-tools! c)                            ;; walks nextCursor
(client/call-tool! c "get_alerts" {"state" "CA"})
```

### Over Streamable HTTP

```clojure
(require '[tools.agents.mcp.http :as http])

(def endpoint (http/serve! srv {:port 8000}))          ;; JVM Clojure only
(def c (client/client {:send! (:send! (http/connect! "http://127.0.0.1:8000/mcp"))}))
(:stop! endpoint)
```

`serve!` is JVM Clojure only, because it hosts on the JDK's
`com.sun.net.httpserver` and Babashka does not ship that module. Hosting
under bb would need a bb-only server dependency, which this namespace
deliberately avoids: `http/handle-http` is a pure function from an HTTP
request map to an HTTP response map, so plugging it into whatever server you
already run is the whole integration. The HTTP *client* half works on both
runtimes.

### Examples

- `examples/mcp/weather.cljc` — a line-for-line port of the official [Build an
  MCP server](https://modelcontextprotocol.io/docs/2026-07-28/develop/build-server)
  tutorial, which is how this library is verified against the reference.
- `examples/mcp/everything.cljc` — a port of the reference [`everything`
  server](https://github.com/modelcontextprotocol/servers/tree/main/src/everything):
  16 tools, 107 resources, 3 templates, 4 prompts, completions, progress,
  logging, subscriptions and MRTR.

## Design notes

### The revision this targets, and what it removed

2026-07-28 is the **stateless** revision. Almost every design choice below
follows from that one fact, so it is worth stating what is gone:

| Gone | Replaced by |
|---|---|
| `initialize` / `notifications/initialized` | `server/discover`, an ordinary cacheable request |
| Sessions, `Mcp-Session-Id` | Nothing. Per-request `_meta` carries what a session used to (SEP-2567) |
| The HTTP `GET` endpoint, `resources/subscribe` / `unsubscribe` | `subscriptions/listen` (SEP-2575) |
| Server→client requests (sampling, elicitation, roots) | MRTR: the server *returns* an `input_required` result and the client retries (SEP-2322) |
| `ping`, `logging/setLevel` | Transport keepalive; per-request `io.modelcontextprotocol/logLevel` |
| SSE resumability, `Last-Event-ID` | Nothing — a dropped stream is re-established, not resumed |
| Error codes `-32002`, `-32042` | `-32602` for a resource that does not exist |
| Tasks in the core protocol | The `io.modelcontextprotocol/tasks` extension |

Three error codes are new: `-32020` HeaderMismatch, `-32021`
MissingRequiredClientCapability, `-32022` UnsupportedProtocolVersion.

Because there is no handshake, **every request must carry
`io.modelcontextprotocol/protocolVersion` and
`io.modelcontextprotocol/clientCapabilities` in `params._meta`**, and a
request missing either is malformed. `server/validate-request-meta!` rejects
it with `-32602` before any handler runs; `client/request` stamps both on
every outgoing request so a caller cannot forget.

### `handle` is a pure function

```clojure
(server/handle srv message)
;; => {:response      <json-rpc response, or nil>
;;     :notifications [<in order, already gated>]
;;     :subscription  {:id .. :filter ..}   ;; subscriptions/listen only
;;     :cancelled     {:request-id .. :reason ..}}
```

No I/O, no globals, no clock. The transports do nothing but frame bytes and
call it, which is why the conformance suite can assert the spec's MUSTs
directly against `handle` on both runtimes with no server running, and
why the client suite can loop a real client into a real server in-process
without a mock at either end.

The spec's MUST/MUST-NOTs live in the **dispatcher**, not in handlers:
`resultType` and `_meta.serverInfo` are stamped once for every result;
`ttlMs`/`cacheScope` once for every cacheable one; progress notifications are
dropped unless the request carried a `progressToken`; log notifications are
dropped unless it carried a `logLevel` and the message meets it. A handler
that calls `(:progress! ctx)` unconditionally is still conformant — the
conformance suite proves this with a deliberately careless handler.

### Parity with the Python SDK

#### Server construction

| Python SDK | tools.agents.mcp |
|---|---|
| `MCPServer(name, version=..., instructions=...)` | `(server/server {:name .. :version .. :instructions ..})` |
| `@mcp.tool()` on a typed function | `:tools [{:name .. :input-schema .. :handler (fn [ctx args])}]` |
| `mcp.add_tool(...)` / `remove_tool(name)` | Rebuild the map, or `assoc` into `:tools` — a server is a value |
| `@mcp.resource("uri://x")` | `:resources [{:uri .. :handler (fn [ctx uri])}]` |
| `@mcp.resource("uri://{id}")` (templated) | `:resource-templates [{:uri-template .. :handler (fn [ctx uri vars])}]` |
| `@mcp.prompt()` | `:prompts [{:name .. :arguments .. :handler (fn [ctx args])}]` |
| `@mcp.completion()` | `:completions (fn [ctx ref argument context])` |
| `mcp.run(transport="stdio")` | `(stdio/serve! srv)` |
| `mcp.run(transport="streamable-http")` | `(http/serve! srv {:port ..})` |
| `mcp.streamable_http_app()` (ASGI app) | `http/handle-http` — a function of a request map |
| Capabilities inferred from what is registered | Same: `server/capabilities` derives them, so a server cannot advertise `tools` while having none |

**The deliberate divergence, and it is the tutorial's own headline.** Python
writes `state: str` plus a docstring and the SDK *derives* the JSON Schema,
the description and the argument descriptions from them. Clojure has no type
hints to derive a schema from, and a metadata-scraping layer to fake one
would fight this repo's data-transparency idiom — the same idiom that has
`tools.agents.anthropic` pass `"max_tokens"` through verbatim rather than
kebab-casing it. So `:input-schema` is written out as the JSON Schema it will
be sent as. Everything else about a tool is the SDK's shape.

Registration is otherwise **declarative rather than imperative**: the Python
SDK mutates a server object through decorators, this takes one map. Wire
values inside it are string-keyed and pass through verbatim
(`"inputSchema"`, `"annotations"`, `"outputSchema"`); config around them is
keyword-keyed and kebab-cased (`:input-schema`, `:mime-type`). That split is
the same one both siblings use.

#### The handler context

| Python `Context` | ctx map key | Note |
|---|---|---|
| `await ctx.report_progress(p, total, message)` | `(:progress! ctx)` | No-op unless the request carried a `progressToken` |
| `await ctx.log(level, data)` / `.debug` / `.info` / `.warning` / `.error` | `(:log! ctx)` | Gated on the request's `logLevel`; one function, not five |
| `ctx.client_capabilities` | `:client-capabilities` | |
| `ctx.protocol_version` | `:protocol-version` | |
| `ctx.request_id` | `(get (:request ctx) "id")` | |
| `ctx.input_responses` / `ctx.request_state` | `:input-responses` / `:request-state` | The MRTR retry legs |
| `await ctx.elicit(...)` / `.elicit_url(...)` | `(mcp/input-required {:input-requests {"k" (mcp/elicit-form ...)}})` | Returned, not awaited — see MRTR below |
| `await ctx.read_resource(uri)` | Call the resource's `:handler`, or `server/handle` with a `resources/read` | |
| `await ctx.notify_resources_changed()` etc. | `(stdio/notify! conn mcp/resources-list-changed-notification)` | A transport concern here, not a request concern |
| `ctx.headers` | Passed into `handle-http` by the host | |
| `ctx.session` | — | There are no sessions in this revision |

`:emit!` is also on the ctx map, but `:progress!` and `:log!` are the
sanctioned way to notify: they are pre-gated, so a handler that ignores the
spec's MUST-NOTs still obeys them.

#### Client

| Python `Client` | tools.agents.mcp.client |
|---|---|
| `Client(transport)` | `(client/client {:send! ..})` |
| `await client.list_tools()` / `list_prompts()` / `list_resources()` / `list_resource_templates()` | `list-tools!` / `list-prompts!` / `list-resources!` / `list-resource-templates!` |
| — (manual `cursor` loop) | `list-all-tools!` etc. — walk `nextCursor` to exhaustion |
| `await client.call_tool(name, args)` | `(call-tool! c name args)` |
| `await client.read_resource(uri)` | `(read-resource! c uri)` |
| `await client.get_prompt(name, args)` | `(get-prompt! c name args)` |
| `await client.complete(ref, argument)` | `(complete! c ref argument context)` |
| `client.listen(...)` | `(client/listen! c filter)` builds the request; sending it over a stdio transport blocks for the life of the stream, with notifications going to that transport's `:on-notification` |
| `client.server_capabilities` / `server_info` / `instructions` | Fields of `(discover! c)` — there is no cached handshake to read them from |
| `@deprecated send_ping`, `set_logging_level`, `subscribe_resource`, `unsubscribe_resource` | **Not exposed.** The Python SDK keeps them, marked deprecated, to drive 2025-era servers; this library implements one revision |
| `input_handlers` for elicitation/sampling/roots | `:input-handlers {"elicitation/create" (fn [req] result)}` |

Everything is synchronous. There is no `async`/`await` to mirror: `:send!` is
one function of a request map to a response map, and a caller who wants
concurrency has `future` and their own thread pool. This is the same choice
both siblings make.

### Multi round-trip requests (MRTR)

This replaces server-initiated requests, and it is the largest behavioural
change in the revision. A server that needs something from the client
**returns** it:

```clojure
:handler
(fn [ctx args]
  (if-let [answer (get (:input-responses ctx) "confirm")]
    (mcp/tool-result (str "you said " (get-in answer ["content" "ok"])))
    (mcp/input-required
     {:input-requests {"confirm" (mcp/elicit-form "Proceed?" schema)}
      :request-state "step-1"})))
```

The client retries the *same* request with `inputResponses` and the echoed
`requestState`, under a **new JSON-RPC id** — the two legs are independent
requests, not a continuation. `client/with-mrtr` runs that loop, bounded by
`:max-rounds` (default 5), and refuses on the client's own side to answer an
input request for a capability the client did not declare. On the server
side, `mcp/require-capabilities!` raises `-32021` with
`data.requiredCapabilities` naming what was missing.

Only `tools/call`, `resources/read` and `prompts/get` may return
`input_required`.

### subscriptions/listen

One long-lived request replaces the HTTP GET endpoint and the old
subscribe/unsubscribe pair. `handle` answers it with `:subscription
{:id :filter}` and **no response** — the response comes only at graceful
closure (`server/close-subscription`). The first message on the stream is
`notifications/subscriptions/acknowledged`, and every message on the stream
carries `_meta.io.modelcontextprotocol/subscriptionId`.

`server/subscription-notification` returns `nil` for a notification type the
subscription did not ask for, which is how "the server MUST NOT send
notification types the client did not request" is enforced at the one place
notifications are tagged. Request-scoped notifications (`progress`,
`message`) are deliberately *not* deliverable on a listen stream: the spec
confines them to the response stream of the request they belong to.

On stdio, `notifications/cancelled` closes the stream.

### Streamable HTTP: header mirroring

SEP-2243 collapsed the transport to a single POST endpoint whose required
headers duplicate what is already in the body, so a proxy can route without
parsing JSON:

- `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` (the tool/prompt/
  resource the request targets)
- `Mcp-Param-{Name}` for any tool argument the tool's schema marks with an
  `x-mcp-header` annotation

Any mismatch between a header and the body is `-32020` + HTTP 400 — the
server never resolves the disagreement in the body's favour. Non-ASCII header
values use the spec's `=?base64?<b64>?=` sentinel, which is why this library
carries its own portable UTF-8 and Base64 (`http/utf8-bytes`,
`http/base64-encode`) rather than reaching for a runtime's.

`handle-http` also validates `Origin` (403 on a disallowed one), answers a
notification POST with 202 and no body, sets `X-Accel-Buffering: no` on SSE
responses, and returns 405 for anything but POST.

### Error hierarchy

Every error is an `ex-info` whose `ex-data` carries a `:type` keyword, the
same convention both siblings use. Protocol errors additionally carry `:code`
and, where the schema fixes one, `:data`.

| `:type` | Raised when |
|---|---|
| `::error/parse` | `-32700` — the peer sent unparseable JSON |
| `::error/invalid-request` | `-32600` |
| `::error/method-not-found` | `-32601` |
| `::error/invalid-params` | `-32602` — including *resource not found*, which lost its own code in this revision |
| `::error/internal` | `-32603` |
| `::error/header-mismatch` | `-32020` — an HTTP header disagreed with the body |
| `::error/missing-client-capability` | `-32021` — carries `data.requiredCapabilities` |
| `::error/unsupported-protocol-version` | `-32022` — carries `data.supported` and `data.requested` |
| `::error/api` | A server error whose code is not one of the above |
| `::error/protocol` | Internal marker for "this ex-data already holds the wire shape" |
| `::error/json-parse` / `::error/json-encode` | The codec |
| `::error/invalid-registration` / `::error/duplicate-name` | `server/server` rejected a bad descriptor |
| `::error/invalid-input-required` | `mcp/input-required` was given neither `inputRequests` nor `requestState` |
| `::error/mrtr-exhausted` / `::error/mrtr-unsupported-method` | The client's MRTR loop hit `:max-rounds`, or the server asked for MRTR on a method that may not use it |
| `::error/undeclared-input-request` / `::error/no-input-handler` | The server asked for a capability the client never declared, or one it declared without wiring a handler |
| `::error/transport` | The transport failed underneath a request |
| `::error/unsupported-runtime` | See Platform limitations |

`:type` values are flat keywords, not a `derive` hierarchy: flat keywords
compare and pattern-match the same everywhere, and nothing here needs
hierarchical dispatch.

### Legacy-client compatibility

`server/handle` implements only revision 2026-07-28 (SEP-2575), which
removed the `initialize` handshake and protocol-level sessions entirely — by
design, per its own docstring. A client still speaking an earlier revision's
classic handshake gets a flat `-32602` from `server/handle` on its very
first message, because that revision's `initialize` request carries no
`_meta` at all. This is not hypothetical: as of this writing, every
general-purpose MCP client this library has been run against in practice
(Claude Code's own) still sends the classic handshake.

`tools.agents.mcp.stdio/serve!` takes `:legacy-handshake? true` to bridge
this without touching `server/handle` itself:

```clojure
(stdio/serve! srv {:legacy-handshake? true})
```

What it does, all in the transport layer, none of it in the protocol core:

- Answers `initialize` locally with a classic `InitializeResult`, echoing
  back whatever `protocolVersion` the client asked for and taking
  `serverInfo` from `(:info srv)` — the same map `server/handle` itself
  advertises. `server/handle` never sees this request.
- Swallows `notifications/initialized` (a notification; no response is
  correct either way, but a legacy client sends it unprompted).
- Answers `ping` with an empty result. `server/handle` has no `"ping"`
  method — the stateless revision has no session to keep alive — but a
  legacy client's keepalive MUST get a prompt response or it may drop the
  connection mid-session. That failure mode is invisible to a startup-time
  smoke test: it only shows up minutes into a real session.
- Repairs (not replaces) every other request's `_meta`: fills in
  `io.modelcontextprotocol/protocolVersion` and
  `.../clientCapabilities` only if missing, leaving any `_meta` the client
  did send — a legacy `tools/call` typically carries its own `_meta` (a
  `progressToken`), just never those two required fields. An
  all-or-nothing "stamp only if `_meta` is entirely absent" guard would
  therefore stamp nothing on exactly the requests that need it; clobbering
  the whole map instead would silently drop `progressToken`, which
  `server/handle` correlates progress notifications by.

The three pieces are individually public — `legacy-initialize-response`,
`legacy-stamp-meta`, and the line-level dispatcher `handle-line-legacy!` —
for a caller building its own loop instead of using `serve!` directly (e.g.
one layering additional interception, the way a private deployment's TLS
setup might sit beside this in the same process). `handle-line-legacy!` is
to `handle-line!` exactly as `:legacy-handshake? true` is to the default:
same signature, same return shape, three extra method interceptions and a
`_meta` repair in front of `handle-message!`.

This is opt-in and additive — plain `serve!` (the default,
`:legacy-handshake?` unset) is unchanged, and `server/handle` gained no new
surface at all. Leave it off for a revision-2026-07-28-native client, or a
test harness driving the server directly through JSON that already carries
`_meta`.

### Verification against the build-server tutorial

`examples/mcp/weather.cljc` is the tutorial's server, function for function —
`make_nws_request` → `http-get-json!`, `format_alert` → `format-alert`,
`get_alerts`, `get_forecast`, and `mcp.run(transport="stdio")` →
`stdio/serve!`. `test/tools/agents/mcp/examples_test.cljc` drives it two
ways: directly (every branch of both tools, including the three failure
paths the tutorial's `try/except` swallows) and end-to-end through a real
`client` over a real stdio subprocess, asserting the tool list, the schemas,
and byte-exact tool output.

The tutorial's logging section is honoured the way it insists: a stdio server
must never write to stdout, so `stdio/log!` writes to stderr, and a test
asserts that calling it puts nothing on stdout.

Two tutorial facilities have no equivalent here and their absence is the
divergence noted above: schema inference from type hints, and `Context`
injection by parameter *name* (`ctx` is always the handler's first argument).

### The everything server, row by row

The reference server registers 19 tools; this port registers 16.

| Reference | Port | Why |
|---|---|---|
| `echo` | `echo` | |
| `get-sum` | `get-sum` | |
| `get-tiny-image` | `get-tiny-image` | Same base64 payload, byte for byte |
| `get-annotated-message` | `get-annotated-message` | |
| `get-env` | `get-env` | |
| `get-structured-content` | `get-structured-content` | |
| `get-resource-links` | `get-resource-links` | |
| `get-resource-reference` | `get-resource-reference` | |
| `trigger-long-running-operation` | `trigger-long-running-operation` | Sleep is injected (`:sleep-fn`) so the test suite does not actually sleep |
| `trigger-elicitation-request` | `trigger-elicitation-request` | Now MRTR, not a server→client request |
| `trigger-url-elicitation` | `trigger-url-elicitation` | Same |
| `trigger-sampling-request` | `trigger-sampling-request` | Same |
| `get-roots-list` | `get-roots-list` | Same |
| `toggle-simulated-logging` | **`emit-simulated-logs`** | The reference toggles a per-session interval timer. There are no sessions and no server-push channel for request-scoped logs, so the tool emits its logs on the calling request's own stream, gated by that request's `logLevel`. A toggle would have nothing to toggle |
| `toggle-subscriber-updates` | **`touch-resource`** | Same reason: the reference's timer pushes `resources/updated` to session subscribers. Here a tool call bumps a resource's version and the notification fans out to whatever `subscriptions/listen` streams asked for that URI |
| `gzip-file-as-resource` | **`register-resource`** | The reference fetches an arbitrary URL and gzips it — neither of which a zero-dependency library provides. The interesting half is `registerSessionResource`, so the port keeps that: `register-resource` stores a caller-supplied payload and returns a `resource_link`. Session-scoped `demo://resource/session/<name>` becomes handle-scoped `demo://resource/registered/{handle}`, because SEP-2567 requires cross-request state to be named by an explicit identifier the client passes back |
| `simulate-research-query` | — | Tasks |
| `trigger-sampling-request-async` | — | Tasks |
| `trigger-elicitation-request-async` | — | Tasks |

The three Tasks tools are not ported because Tasks left the core protocol in
this revision for the `io.modelcontextprotocol/tasks` extension.
`server/server`'s `:methods` option — a map of method name to `(fn [ctx
request] result)` — is the seam an extension is built on, and it is the only
thing the port would need.

Resources, prompts and completions:

| Reference | Port |
|---|---|
| 7 static documents at `demo://resource/static/document/<name>`, read from `src/everything/docs/*.md` | Same 7 names and URIs, held in source. A server that needs its own working directory to answer `resources/read` is a worse demonstration than one that does not |
| 2 templates (`demo://resource/dynamic/text/{id}`, `.../blob/{id}`) | Same 2, plus `demo://resource/registered/{handle}` for `register-resource` |
| Nothing listed beyond the static documents | **Added:** 100 numbered resources (odd ids text, even ids blob) matching the templates' URIs, so that `resources/list` pagination is reachable at all — no other primitive here exercises it |
| `PAGE_SIZE = 10` over everything | `:page-size {:resources 10}`. A server whose purpose is to be explored must not hide 6 of its 16 tools behind a cursor |
| 4 prompts: simple, args, completions, embedded resource | Same 4 |
| Completions for prompt arguments, context-sensitive | Same, through the single `:completions` hook |

Everything impure is injected — `:timestamp-fn`, `:sleep-fn`, `:notify!`,
`:store` — so `everything-server` is a pure function of its inputs and the
suite can assert byte-exact output rather than shapes.

### Platform limitations

| | JVM Clojure | Babashka |
|---|---|---|
| Server core, both transports' pure halves | ✓ | ✓ |
| stdio `serve!` (be a server) | ✓ | ✓ |
| stdio `connect!` (drive a subprocess server) | ✓ | ✓ |
| HTTP `connect!` | ✓ | ✓ |
| HTTP `serve!` | ✓ | ✗ no `com.sun.net.httpserver` |

The `serve!` gap is a choice of implementation, not a capability limit — bb
can bind a socket, just not through the JDK module this namespace uses, and
adding a bb-only server dependency would cost more than it buys. It throws
`::error/unsupported-runtime` with the workaround in the message: host
`http/handle-http` in whatever server you already run, or use stdio.

## Testing

```
clojure -M:test-mcp                             # JVM Clojure
bb test-mcp                                     # Babashka
./script/test-all.sh                            # every runtime, every library
```

The MCP suite is green on both runtimes: 186 tests / 699 assertions. So is
the rest of `test-all.sh` — anthropic 167/424, openai 84/304 and gemini
47/126.

Six suites, hermetic — no network, no fixed ports, and the only subprocess is
this repo's own weather server.

- **`mcp_test.cljc`** — the core namespace: the JSON codec (round trips, full
  C0 escaping, framing safety, surrogate pairs), JSON-RPC framing, typed
  errors, `_meta` construction and extraction, capability helpers, content
  blocks, notifications, MRTR constructors. Opens with literal-value
  assertions on every protocol constant.
- **`conformance_test.cljc`** — one test per normative clause `server/handle`
  enforces: `resultType` on every result, `ttlMs`/`cacheScope` on every
  cacheable one, `serverInfo` stamping, `-32602` for missing or invalid
  `_meta`, the fixed `-32022` data shape, progress and log gating, `emit!`
  ordering, "`handle` never produces a JSON-RPC request" (there are no
  server-initiated requests in this revision), capability truthfulness, MRTR
  round trips, `-32021` both when a handler checks and when it forgets,
  pagination including per-kind page size, `subscriptions/listen` and its
  filter, and every malformed-message branch.
- **`client_test.cljc`** — a real client looped into a real server
  in-process, `server/handle` as `:send!`. Not a mock: request `_meta`, id
  minting (including MRTR's new-id-per-leg rule), per-request opt-ins,
  `result-of` backfill, code→`:type` mapping, discovery, pagination walking,
  the MRTR bound, refusal to answer an undeclared capability, `listen!` /
  `cancel!`, and all three `probe!` classifications.
- **`http_test.cljc`** — RFC 4648 Base64 vectors, UTF-8 round trips including
  astral planes, the header sentinel, `x-mcp-header` validation, `Mcp-Name`
  mirroring, every `-32020` case, code→status mapping, SSE framing and
  parsing, and `handle-http` end to end (JSON, 405, 403, `-32700`, 202, SSE,
  listen streams).

  Not covered: `http/serve!` and `http/connect!`, the two adapters that put
  this logic on a real socket. Both are thin — `serve!` binds
  `com.sun.net.httpserver` and delegates every request to `handle-http`;
  `connect!` is the private `http-post!` leaf (`java.net.http.HttpClient` on
  JVM, `babashka.http-client` on bb) plus `parse-sse` — and testing either
  means binding a fixed port, which is a host-state assumption the
  anthropic/openai/gemini suites accept and this one chose not to take on.
  The logic underneath both
  is tested; the socket wiring is not. A change to either leaf therefore has
  to be checked out of band, and the anthropic and openai suites — which do
  exercise their own identical leaf — are the tripwire that catches it.
- **`stdio_test.cljc`** — framing (one message per line; tool output that
  contains newlines cannot break it), `-32700` with a null id, notification
  ordering, listen/notify/cancel/close, `log!` writing nothing to stdout, and
  a real subprocess round trip against the weather server.
  Legacy-handshake compatibility: `initialize`
  answered without reaching `server/handle` (and its version-omitted
  fallback), `notifications/initialized` and `ping` handled,
  `legacy-stamp-meta` repairing a partial `_meta` without losing
  `progressToken` (asserted by checking the progress notification it still
  drives) versus leaving a well-formed one untouched, the parse-error path
  unchanged, and a full classic session end to end through `serve!
  {:legacy-handshake? true}`.
- **`examples_test.cljc`** — both example servers, including every divergence
  claimed above: `resources-paginate-but-tools-do-not`,
  `static-documents-are-served-in-a-stable-order`,
  `the-tasks-tools-are-deliberately-absent`, handles replacing session
  resources, `touch-resource` reaching a notification sink, MRTR replacing
  server-initiated requests, and capability-gated tools that list
  unconditionally but refuse at call time.

## License

Apache License 2.0 — see `LICENSE`.
