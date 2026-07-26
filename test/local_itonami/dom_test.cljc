(ns local-itonami.dom-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [local-itonami.dom :as dom]))

(defn- ops-of [tree] (dom/tree->ops tree))

(defn- creates [ops] (filter #(= :dom/create-element (first %)) ops))
(defn- attrs [ops] (filter #(= :dom/set-attr (first %)) ops))

(deftest compiles-a-single-element
  (let [ops (ops-of [:div])]
    (is (= [:dom/create-element 1 "div"] (first ops)))
    (is (= [:dom/mount 1] (last ops)))))

(deftest tag-sugar-is-parsed-not-passed-through
  (testing "[:div.metric] must become a div with class metric, never an element named div.metric"
    (let [ops (ops-of [:div.metric])]
      (is (= [[:dom/create-element 1 "div"]] (creates ops)))
      (is (= [[:dom/set-attr 1 :class "metric"]] (attrs ops)))))

  (testing "id sugar"
    (let [ops (ops-of [:section#queue.wide.tall])]
      (is (= [[:dom/create-element 1 "section"]] (creates ops)))
      (is (= #{[:dom/set-attr 1 :id "queue"]
               [:dom/set-attr 1 :class "wide tall"]}
             (set (attrs ops))))))

  (testing "a bare class selector defaults to div"
    (is (= [[:dom/create-element 1 "div"]] (creates (ops-of [:.card]))))))

(deftest explicit-class-extends-sugar-rather-than-replacing-it
  (let [ops (ops-of [:div.base {:class "extra"}])]
    (is (= [[:dom/set-attr 1 :class "base extra"]] (attrs ops))))
  (testing "a collection of classes is joined"
    (let [ops (ops-of [:div {:class ["a" "b"]}])]
      (is (= [[:dom/set-attr 1 :class "a b"]] (attrs ops))))))

(deftest text-children-become-text-nodes
  (let [ops (ops-of [:p "hello"])]
    (is (some #{[:dom/set-text 2 "hello"]} ops))
    (is (some #{[:dom/append-child 1 2]} ops))))

(deftest nil-and-false-children-are-dropped
  (testing "(when cond ...) must not emit an element"
    (is (= 1 (count (creates (ops-of [:div nil])))))
    (is (= 1 (count (creates (ops-of [:div false])))))
    (is (= 2 (count (creates (ops-of [:div (when true [:span])])))))))

(deftest seqs-are-spliced
  (testing "(map render items) works without a wrapper element"
    (let [ops (ops-of [:ul (map (fn [i] [:li (str i)]) [1 2 3])])]
      (is (= 4 (count (filter #(#{"ul" "li"} (nth % 2)) (creates ops))))
          "one ul + three li, not a nested seq element"))))

(deftest nil-attribute-values-are-omitted-not-stringified
  (let [ops (ops-of [:a {:href nil :title "t"}])]
    (is (= [[:dom/set-attr 1 :title "t"]] (attrs ops)))))

(deftest ids-are-stable-and-depth-first
  (let [ops (ops-of [:div [:span "a"] [:span "b"]])]
    (is (= [[:dom/create-element 1 "div"]
            [:dom/create-element 2 "span"]
            [:dom/create-element 3 "span"]
            [:dom/create-element 4 "span"]
            [:dom/create-element 5 "span"]]
           (creates ops))
        "text nodes take ids too; the sequence must be deterministic")
    (is (= ops (ops-of [:div [:span "a"] [:span "b"]]))
        "same tree in, same ops out -- a caller can diff two renders")))

(deftest element?-recognises-hiccup-only
  (is (true? (dom/element? [:div])))
  (is (false? (dom/element? ["div"])))
  (is (false? (dom/element? "div")))
  (is (false? (dom/element? nil))))

(deftest parse-tag-contract
  (is (= ["div" nil []] (dom/parse-tag :div)))
  (is (= ["div" "x" ["a"]] (dom/parse-tag :div#x.a)))
  (is (= ["span" nil ["a" "b"]] (dom/parse-tag :span.a.b))))
