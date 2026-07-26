(ns local-itonami.dom
  "Hiccup → `kotoba:dom` operation compiler.

  `kotoba-lang/shell` owns the native host and commits `[:dom/* …]` operation
  vectors to the surface, but **it does not ship a compiler that produces
  them** — its only hiccup-shaped code is a hand-written op vector in
  `kotoba.shell.stack-e2e`. `gftdcojp/local-manimani` therefore wrote its own
  (`manimani.shell-app/tree->ops`, described in its own docstring as a 'small
  deterministic Hiccup-to-kotoba:dom compiler').

  This is the **second independent copy**. It is deliberately isolated in its
  own namespace with no app-specific knowledge so that lifting it into
  `kotoba-lang/shell` — where the surface contract lives and where a third
  app would otherwise write a third copy — is a move rather than a rewrite.
  Recorded as a named follow-up in ADR-2607262000, not left implicit.

  ## Why hiccup at all

  Hiccup `.cljc` is the one authoring format that reaches every surface this
  workspace renders to: `html.core/->html` for SSR and WebView bundles,
  reagent for the browser, and this compiler for the native `kotoba:dom`
  surface. That is what makes a single skin-neutral view layer possible
  across `jp-go-dds` (DADS) and `kotoba-ui` (HIG) — see the same ADR.

  ## Contract

  `(tree->ops hiccup)` returns a vector of operations. Node ids are assigned
  depth-first from 1 and are stable for a given tree, so a caller can diff
  two op vectors without re-keying. Nothing here performs I/O, touches a
  `js/` global, or references the shell runtime — it is a pure function from
  data to data, which is what makes it testable on the JVM."
  (:require [clojure.string :as str]))

(defn element?
  "True for a hiccup element vector `[tag …]`."
  [value]
  (and (vector? value) (keyword? (first value))))

(defn- text
  "Attribute/text values reach the surface as strings. nil becomes the empty
  string rather than the literal \"null\" a bare `str` would produce."
  [value]
  (if (nil? value) "" (str value)))

(defn parse-tag
  "Split hiccup's `:div#id.a.b` sugar into `[tag id classes]`.

  local-manimani's compiler does not do this — it treats the whole keyword as
  the tag name, so `[:div.metric …]` reaches the native surface as an element
  literally named `div.metric`, which no host renders. Views written for the
  HTML path use that sugar constantly, so a compiler that ignores it silently
  produces a blank window."
  [tag]
  (let [s (name tag)
        [head & classes] (str/split s #"\.")
        [tag-name id] (str/split head #"#")]
    [(if (str/blank? tag-name) "div" tag-name)
     id
     (vec (remove str/blank? classes))]))

(defn- merge-class
  "Sugar classes come first, then any `:class` attribute, so an explicit
  attribute extends rather than replaces the sugar."
  [sugar-classes attr-class]
  (let [attr (cond
               (nil? attr-class) []
               (string? attr-class) [attr-class]
               (coll? attr-class) (vec (map name attr-class))
               :else [(text attr-class)])
        all (into (vec sugar-classes) attr)]
    (when (seq all) (str/join " " all))))

(defn tree->ops
  "Compile a hiccup tree into a vector of `kotoba:dom` operations.

  Emitted ops:
    [:dom/create-element id tag]
    [:dom/set-attr id attr value]
    [:dom/set-text id value]
    [:dom/append-child parent-id child-id]
    [:dom/mount root-id]

  Strings/numbers become text nodes. nil and false children are dropped (so
  `(when cond …)` works in a view). Seqs are spliced, which is what makes
  `(map render items)` work without a wrapping element."
  [tree]
  (let [next-id (volatile! 0)
        ops (volatile! [])
        emit! (fn [op] (vswap! ops conj op))
        allocate! (fn [] (vswap! next-id inc))]
    (letfn [(children-of [body]
              (->> body
                   (mapcat #(if (and (seq? %) (not (element? %))) % [%]))
                   (remove #(or (nil? %) (false? %)))))
            (compile! [node]
              (cond
                (element? node)
                (let [[tag & body] node
                      attrs (if (map? (first body)) (first body) {})
                      body (if (map? (first body)) (rest body) body)
                      [tag-name sugar-id sugar-classes] (parse-tag tag)
                      id (allocate!)
                      class-value (merge-class sugar-classes (:class attrs))]
                  (emit! [:dom/create-element id tag-name])
                  (when-let [element-id (or (:id attrs) sugar-id)]
                    (emit! [:dom/set-attr id :id (text element-id)]))
                  (when class-value
                    (emit! [:dom/set-attr id :class class-value]))
                  (doseq [[k v] (dissoc attrs :class :id)
                          :when (some? v)]
                    (emit! [:dom/set-attr id k (text v)]))
                  (doseq [child (children-of body)]
                    (emit! [:dom/append-child id (compile! child)]))
                  id)

                :else
                (let [id (allocate!)]
                  (emit! [:dom/create-element id "span"])
                  (emit! [:dom/set-text id (text node)])
                  id)))]
      (let [root (compile! tree)]
        (conj @ops [:dom/mount root])))))
