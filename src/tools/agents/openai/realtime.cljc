(ns tools.agents.openai.realtime
  "OpenAI Realtime API — HTTP control-plane methods only. Port of
   openai-python's `client.realtime.client_secrets`
   (`resources/realtime/client_secrets.py`).

   Takes the `OpenAIClient` record built by `tools.agents.openai/client` and
   POSTs through `tools.agents.openai/post-json!` (same retries, error typing
   and `:stream` refusal as `responses-create`).

   NOT IMPLEMENTED: the WebSocket event plane (`client.realtime.connect`) and
   the WebRTC/SIP call controls (`client.realtime.calls.*`)."
  (:require [tools.agents.openai :as oai]))

(defn realtime-client-secrets-create
  "POST {base-url}/realtime/client_secrets — the analogue of
   `client.realtime.client_secrets.create(**params)`. Mints a short-lived
   client secret (\"ek_...\") a browser or mobile client uses in place of the
   API key. `request` passes through to JSON verbatim: \"expires_after\"
   ({\"anchor\" \"created_at\" \"seconds\" n}), \"session\" (realtime or
   transcription session config); `{}` is valid. Returns the decoded
   response map (string keys: \"value\", \"expires_at\", \"session\").

   Throws ex-info with the same `:type`/`:status`/`:body` contract as
   `tools.agents.openai/responses-create`, message prefix
   \"tools.agents.openai/realtime-client-secrets-create: \"."
  ([client] (realtime-client-secrets-create client {}))
  ([client request]
   (oai/post-json! client "realtime-client-secrets-create" "/realtime/client_secrets" (or request {}))))
