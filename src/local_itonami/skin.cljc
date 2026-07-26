(ns local-itonami.skin
  "The 共通化 seam: one component vocabulary, two design systems behind it.

  ## The problem this solves

  This workspace has two design systems and a repo-wide rule that names only
  one of them:

  - `kotoba-ui` (+ `appkit`/`uikit`) — the HIG/liquid-glass paved road, the
    mandatory entry point per ADR-2607122200. light + dark.
  - `jp-go-digital-design-system` — デジタル庁デザインシステム (DADS),
    introduced by ADR-2607141915 as an **explicit opt-out** for
    公共・行政文脈 services, adopted so far by one app (`ai-gftd-itad`).
    light-fixed (upstream ships no dark palette).

  An app that hard-codes `dads-*` classes in its views cannot move to the
  other skin without a rewrite, and vice versa. That is the whole cost of
  having two, and it is paid per app, forever, unless the views are written
  against something neutral.

  ## The seam

  Views call this namespace. It returns hiccup carrying the **active skin's**
  classes. Swapping skin is `alter-var-root` on `*skin*` (or a different
  build), not an edit to any view.

  Two things make this cheap rather than a new abstraction empire:

  1. **The component sets are already near-isomorphic.** DADS gives
     button/heading/table/chip/divider/accordion/input + `dds-ext-*`
     container/section/grid/stack/card; `kotoba-ui.shell` gives
     stack/section/hero/grid/app-shell/page. The vocabulary below is their
     intersection plus what this cockpit actually needs — not a superset
     invented up front.
  2. **Colour and type do not go through here at all.** Both skins publish
     the `--hig-*` token contract (`jp-go-dds.tokens/skin-css` bridges it onto
     DADS primitives), so views and SVG assets reference tokens directly and
     are already skin-neutral. Only *structure* needs a seam.

  ## Why this lives in the app and not in a library (yet)

  It is one consumer. Promoting it to `kotoba-lang` before a second app needs
  it would be the same speculative-generality mistake as the abstraction it
  replaces. ADR-2607262000 records the promotion trigger: **the second app
  that wants to switch skins.** Until then this stays ~80 lines here."
  (:require [clojure.string :as str]))

(def dads
  "DADS class vocabulary. Names are upstream's (`dads-*`) plus this repo's
  vendored layout helpers (`dds-ext-*`); neither is invented here."
  {:id :dads
   :container "dds-ext-container"
   :section "dds-ext-section"
   :stack "dds-ext-stack"
   :card "dds-ext-card"
   :heading "dads-heading"
   :chip "dads-chip-label"
   ;; DADS は severity を data-type 属性で選ぶので、ここに修飾子クラスは無い。
   :notice "dads-notification-banner"})

(def kotoba-ui
  "kotoba-ui/HIG class vocabulary, for an app that wants light+dark. Present
  so the seam is demonstrably two-sided rather than a DADS wrapper with a
  hypothetical second implementation."
  {:id :kotoba-ui
   :container "kui-container"
   :section "kui-section"
   :stack "kui-stack"
   :card "liquid-glass__panel"
   :heading "hig-title2"
   :chip "kui-chip"
   :chip-warning "kui-chip--warning"
   :notice "liquid-glass__alert"
   :notice-error "liquid-glass__alert--error"})

(def ^:dynamic *skin*
  "The active skin. DADS by default: itonami is a 事業者向け business-OS whose
  surface is 許認可 / 調達 / 法人実体 / 官公庁 adjacent, which is squarely the
  context ADR-2607141915 introduced DADS for — this is a deliberate,
  documented opt-out from the kotoba-uiux default, not an oversight."
  dads)

(defn skin-id [] (:id *skin*))

(defn- cls
  "Resolve one or more vocabulary keys against the active skin and join them.

  Every argument is a KEY, never a literal class string — `(cls :chip
  :chip-warning)` must look up both. An earlier revision spliced the extra
  arguments through unresolved, which put the raw keyword `:chip-warning`
  into the class attribute of every warning chip and notice."
  [& ks]
  (str/join " " (remove str/blank? (map #(get *skin* %) ks))))

;; ───────────────────────── vocabulary ─────────────────────────

(defn container [& children]
  (into [:div {:class (cls :container)}] children))

(defn section [& children]
  (into [:section {:class (cls :section)}] children))

(defn stack [& children]
  (into [:div {:class (cls :stack)}] children))

(defn card [& children]
  (into [:div {:class (cls :card)}] children))

(defn heading
  "`level` is the semantic heading level (h1…h6). The class is the skin's
  display style — the two are separate on purpose so a visually-smaller
  heading never costs document structure."
  [level & children]
  (into [(keyword (str "h" level)) {:class (cls :heading)}] children))

(defn chip
  "`:tone` — `:neutral` (default) or `:warning`。

  DADS の `dads-chip-label` は severity 修飾子を持たない（`__icon` しか
  子要素が無い）ので、色は app 所有の `itonami-chip--warning` で付ける。
  上流に無い修飾子を捏造してクラス名に書かない — 書いても CSS が無いので
  無言で効かないだけになる。"
  ([label] (chip label {}))
  ([label {:keys [tone]}]
   [:span {:class (str (cls :chip)
                       (when (= :warning tone) " itonami-chip--warning"))}
    (str label)]))

(def ^:private dads-notice-type
  "DADS の notification banner は severity を **class ではなく
  `data-type` 属性**で選ぶ（`dads-notification-banner[data-type=\"error\"]`）。

  最初 `dads-notification-banner--error` という修飾子クラスを書いていたが、
  vendored CSS にそんなセレクタは無く、しかも banner の base rule は
  `__heading`/`__body` の子要素を前提にした内部レイアウトなので、裸の div に
  文字列を入れると**1文字ずつ縦積みになる**（実機で確認した）。"
  {:error "error" :warning "warning" :success "success" :info "info-1"})

(defn notice
  "`:tone` — `:info` (default) / `:error` / `:warning` / `:success`。

  DADS では実際の contract（`data-type` + `data-style` + `__body`）を満たす
  markup を出す。kotoba-ui skin では class 表現に落ちる — severity の表し方が
  2つのデザインシステムで違う、というのがまさに接ぎ目がある理由。"
  ([body] (notice body {}))
  ([body {:keys [tone] :or {tone :info}}]
   (let [role (if (= :error tone) "alert" "status")]
     (if (= :dads (skin-id))
       [:div {:class (cls :notice)
              :data-type (get dads-notice-type tone "info-1")
              :data-style "standard"
              :role role}
        [:div {:class "dads-notification-banner__body"} body]]
       [:div {:class (if (= :error tone) (cls :notice :notice-error) (cls :notice))
              :role role}
        body]))))
