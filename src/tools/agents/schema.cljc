(ns tools.agents.schema
  "Zero-dependency JSON Schema constructors for tool inputs and outputs.
   Every result is ordinary wire data: string-keyed maps, usable anywhere
   a hand-written schema is accepted. No validation or inference is done.

   Property names and required names accept strings or keywords (via name).
   Trailing opts maps merge verbatim OVER generated fields; use wire keys
   such as \"description\", \"enum\", \"default\", \"minimum\", \"maximum\",
   \"title\" and arbitrary \"x-*\" annotations. Raw child schemas pass through
   untouched, including boolean schemas and reference/composition maps."
  (:refer-clojure :exclude [boolean]))

;; Design panel (#45), following anthropic's content-block constructors:
;; - Hiccup/Malli-style tagged vectors: compact, but require a second grammar
;;   and conversion step before callers can inspect or override wire data.
;; - Plain builders (Zod's composable constructors, without schema objects):
;;   simple, portable, consistent with this repo, and compose with raw maps.
;; - Plain-data normalizer/spec-tools-style derivation: adds rules about
;;   inference and keyword translation, obscuring the actual wire schema.
;; - Macro templating: adds a compiler/SCI surface for no gain over functions.
;; Choose builders; opts remain verbatim, required stays explicit (defaults
;; do not imply optionality), and there is no closed-object policy. Objects
;; nest via properties, keeping MCP's x-mcp-header annotations reachable.

(defn string
  "A string schema, optionally merged with wire-keyed opts."
  ([] {"type" "string"})
  ([opts] (merge (string) opts)))

(defn number
  "A number schema, optionally merged with wire-keyed opts."
  ([] {"type" "number"})
  ([opts] (merge (number) opts)))

(defn integer
  "An integer schema, optionally merged with wire-keyed opts."
  ([] {"type" "integer"})
  ([opts] (merge (integer) opts)))

(defn boolean
  "A boolean schema, optionally merged with wire-keyed opts."
  ([] {"type" "boolean"})
  ([opts] (merge (boolean) opts)))

(defn enum
  "An untyped enum schema. Values are preserved; opts can add a type.
   Typed enums can also use (string {\"enum\" [...]}) or another builder."
  ([values] {"enum" (vec values)})
  ([values opts] (merge (enum values) opts)))

(defn array
  "An array schema whose items schema passes through verbatim."
  ([items] {"type" "array" "items" items})
  ([items opts] (merge (array items) opts)))

(defn object
  "An object schema. No args emits an empty properties map. Child schemas
   pass through verbatim; property/required names are encoded with name.
   Omitted or nil required emits no required field; [] emits an empty one.
   For opts without required, use (object properties nil opts). Opts may
   override any generated field, including properties and required."
  ([] (object {}))
  ([properties] (object properties nil))
  ([properties required] (object properties required nil))
  ([properties required opts]
   (merge (cond-> {"type" "object"
                  "properties" (into {} (map (fn [[k v]] [(name k) v])) properties)}
            (some? required) (assoc "required" (mapv name required)))
          opts)))
