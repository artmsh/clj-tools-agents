(ns tools.agents.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.json :as json]
            [tools.agents.schema :as schema]))

(deftest primitive-constructors-and-verbatim-overrides
  (doseq [[build type] [[schema/string "string"] [schema/number "number"]
                       [schema/integer "integer"] [schema/boolean "boolean"]]]
    (is (= {"type" type} (build)))
    (let [opts {"type" [type "null"] "x-custom" {"kept" true}
                "default" nil "description" "Exact wire keys"}]
      (is (= opts (build opts)))
      (is (= opts (json/read-json (json/write-json (build opts)))))))
  (testing "constraints and false/zero defaults stay data, with no inference"
    (is (= {"type" "number" "minimum" 0 "maximum" 10 "default" 0}
           (schema/number {"minimum" 0 "maximum" 10 "default" 0})))
    (is (= {"type" "boolean" "default" false}
           (schema/boolean {"default" false}))))
  (testing "opts do not translate keys or hide unknown ones"
    (is (= {"type" "string" :typo-key true}
           (schema/string {:typo-key true})))))

(deftest objects-keep-required-explicit-and-encode-names
  (is (= {"type" "object" "properties" {}} (schema/object)))
  (let [properties {:tenant-id (schema/string {"default" "acme"})
                    "page" (schema/integer)}
        expected {"type" "object"
                  "properties" {"tenant-id" {"type" "string" "default" "acme"}
                                "page" {"type" "integer"}}}]
    (is (= expected (schema/object properties)))
    (is (= expected (schema/object properties nil)))
    (is (= (assoc expected "required" []) (schema/object properties [])))
    (is (= (assoc expected "required" ["tenant-id" "page"])
           (schema/object properties [:tenant-id "page"])))
    (is (= (assoc expected "title" "Input" "description" "Page selection")
           (schema/object properties nil
                          {"title" "Input" "description" "Page selection"}))))
  (testing "opts win even over generated structural fields"
    (is (= {"type" "object" "properties" {} "required" ["raw"]}
           (schema/object {:generated (schema/string)} [:generated]
                          {"properties" {} "required" ["raw"]})))))

(deftest nested-arrays-enums-and-raw-schemas-compose
  (let [raw {"$ref" "#/$defs/Value" "x-custom" [false nil 0]}
        built (schema/object
               {:nested (schema/object
                          {"values" (schema/array raw {"minItems" 1})
                           :choice (schema/enum ["a" "b"] {"default" "a"})
                           :anything true :nothing false})}
               [:nested]
               {"additionalProperties" false})]
    (is (= {"type" "object"
            "properties"
            {"nested" {"type" "object"
                       "properties" {"values" {"type" "array" "items" raw "minItems" 1}
                                     "choice" {"enum" ["a" "b"] "default" "a"}
                                     "anything" true "nothing" false}}}
            "required" ["nested"] "additionalProperties" false}
           built))
    (is (identical? raw (get-in built ["properties" "nested" "properties" "values" "items"])))
    (is (= built (json/read-json (json/write-json built)))))
  (is (= {"type" "array" "items" false} (schema/array false)))
  (is (= {"enum" [false nil 0]} (schema/enum '(false nil 0))))
  (is (= {"enum" [1 2] "type" "integer"}
         (schema/enum [1 2] {"type" "integer"}))))
