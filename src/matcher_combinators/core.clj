(ns matcher-combinators.core
  (:require [clojure.set :as set]
            [matcher-combinators.model :as model]))

(defn- find-first [pred coll]
  (->> coll (filter pred) first))

(defprotocol Matcher
  (select? [this select-fn candidate])
  (match [this actual]))

(extend-type nil
  Matcher
  (select? [_ _ _] false))

(defn- match? [match-result]
  (= :match (first match-result)))

(defn- mismatch? [match-result]
  (= :mismatch (first match-result)))

(defn- value [match-result]
  (second match-result))

(defrecord Value [expected]
  Matcher
  (select? [_this _select-fn candidate]
    (= expected candidate))
  (match [_this actual]
   (cond
     (and (nil? expected)
          (nil? actual))  true
     (nil? actual)        [:mismatch (model/->Missing expected)]
     (= expected actual)  [:match actual]
     :else                [:mismatch (model/->Mismatch expected actual)])))

(defn equals-value [expected]
  (->Value expected))

;(defn- match-map [expected actual])

(defn- compare-maps [expected actual unexpected-handler]
  (let [entry-results      (map (fn [[key value-matcher]]
                                  [key (match value-matcher (get actual key))])
                                expected)
        unexpected-entries (keep (fn [[key val]]
                                   (when-not (find expected key)
                                     [key (unexpected-handler val)]))
                                 actual)]
    (if (and (every? (comp match? value) entry-results)
             (empty? unexpected-entries))
      [:match actual]
      [:mismatch (->> entry-results
                      (map (fn [[key match-result]] [key (value match-result)]))
                      (concat unexpected-entries)
                      (into actual))])))

(defn- match-map [expected actual unexpected-handler]
  (if-not (map? actual)
    [:mismatch (model/->Mismatch expected actual)]
    (compare-maps expected actual unexpected-handler)))

(defrecord ContainsMap [expected]
  Matcher
  (select? [this select-fn candidate]
    (let [selected-matcher (select-fn expected)
          selected-value   (select-fn candidate)]
      (select? selected-matcher select-fn selected-value)))
  (match [_this actual]
    (match-map expected actual identity)))

(defn contains-map [expected]
  (->ContainsMap expected))

(defrecord EqualsMap [expected]
  Matcher
  (select? [_this select-fn candidate]
    (let [selected-matcher (select-fn expected)
          selected-value   (select-fn candidate)]
      (select? selected-matcher select-fn selected-value)))
  (match [_this actual]
    (match-map expected actual model/->Unexpected)))

(defn equals-map [expected]
  (->EqualsMap expected))

(defrecord EqualsSequence [expected]
  Matcher
  (match [_this actual]
    (if-not (sequential? actual)
      [:mismatch (model/->Mismatch expected actual)]
      (let [matcher-fns     (concat (map #(partial match %) expected)
                                    (repeat (fn [extra-element]
                                              [:mismatch (model/->Unexpected extra-element)])))
            actual-elements (concat actual (repeat nil))
            match-results'  (map (fn [match-fn actual-element] (match-fn actual-element))
                                 matcher-fns actual-elements)
            match-results   (take (max (count actual) (count expected)) match-results')]
        (if (some mismatch? match-results)
          [:mismatch (map value match-results)]
          [:match actual])))))

(defn equals-sequence [expected]
  (->EqualsSequence expected))

(defn- matches-in-any-order? [matchers elements]
  (if (empty? elements)
    (empty? matchers)
    (let [[first-element & rest-elements] elements
          matching-matcher (find-first #(match? (match % first-element)) matchers)]
      (if (nil? matching-matcher)
        false
        (recur (remove #{matching-matcher} matchers) rest-elements)))))

(defrecord AllOrNothingInAnyOrder [expected]
  Matcher
  (match [_this actual]
    (cond
      (not (sequential? actual)) [:mismatch (model/->Mismatch expected actual)]
      (matches-in-any-order? expected actual) [:match actual]
      :else [:mismatch (model/->Mismatch expected actual)])))

(defn- foo [matchers matching? matched-elements]
  (if (empty? matchers)
    [matching? (reverse matched-elements)]
    [:mismatch (concat (reverse matched-elements) (map #(value (match % nil)) matchers))]))

(defn- selecting-match [select-fn all-matchers all-elements]
  (loop [elements         all-elements
         matchers         all-matchers
         matching?        :match
         matched-elements []]
    (if (empty? elements)
      (foo matchers matching? matched-elements)
      (let [[element & rest-elements]  elements
            selected-matcher           (find-first #(select? % select-fn element) matchers)
            [match-result match-value] (if selected-matcher
                                         (match selected-matcher element)
                                         [:mismatch (model/->Unexpected element)])]
        (recur rest-elements
               (remove #{selected-matcher} matchers)
               (if (= :mismatch matching?) :mismatch match-result)
               (cons match-value matched-elements))))))

(defrecord SelectingInAnyOrder [select-fn expected]
  Matcher
  (match [_this actual]
    (if-not (sequential? actual)
      [:mismatch (model/->Mismatch expected actual)]
      (selecting-match select-fn expected actual))))

(defn in-any-order
  ([expected]
   (->AllOrNothingInAnyOrder expected))
  ([select-fn expected]
   (->SelectingInAnyOrder select-fn expected)))
