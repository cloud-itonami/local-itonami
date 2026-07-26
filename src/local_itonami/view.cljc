(ns local-itonami.view
  "Skin-neutral hiccup views for the itonami cockpit.

  ## The one rule this namespace exists to hold

  **These views name no `dads-*` class and no `liquid-glass__*` class
  directly.** They call `local-itonami.skin`, which resolves a component to
  whichever design system the app was built with. That is what makes the same
  view render as DADS (デジタル庁デザインシステム, light-fixed, the skin this
  app ships) or as kotoba-ui/HIG (light+dark) without a rewrite — the
  共通化 seam described in ADR-2607262000.

  Colour and type come from the `--hig-*` token contract in both skins
  (`jp-go-dds.tokens` bridges it onto DADS primitives), so a view never
  writes a raw hex or a px font-size — kotoba-uiux rule 2.

  ## Why the cockpit view lives here and not in cloud-itonami

  `gftdcojp/cloud-itonami` already renders this cockpit server-side
  (`cloud-itonami.keiei.ui.gftdcojp/screen` via `local-app-view.cljc`). This
  namespace deliberately does NOT depend on that repo: a native client that
  put the server's whole classpath (datomic, langgraph, the two CRM
  verticals, every workspace sub-actor) behind a desktop app would be a
  client in name only. local-itonami is an ordinary HTTP client of
  `itonami.cloud` and owns its own presentation of the JSON that API returns.

  The cost of that split is real and named: the two cockpits can drift. What
  keeps them honest is the API contract itself (`/api/{org}/{repo}/…`), not a
  shared render path."
  (:require [clojure.string :as str]
            [local-itonami.skin :as skin]))

;; ───────────────────────── formatting ─────────────────────────

(defn risk-label
  "Japanese label for an effect risk tag. Mirrors
  cloud-itonami.keiei.ui.gftdcojp/risk-label — the vocabulary is the server's,
  so it is transcribed rather than invented."
  [risk]
  (get {"read-only" "読取専用"
        "external-send" "外部送信"
        "financial" "financial"
        "destructive" "破壊的"}
       (some-> risk name)
       (str risk)))

(def kind-label
  "Display label per effect kind. Covers the CRM/marketing kinds
  cloud-itonami.crm added (ADR-2607261200) alongside the workspace kinds, so
  the native cockpit does not fall through to a bare kind string on exactly
  the :financial / :external-send rows that most need a legible preview."
  {"crm/advance-opportunity" "商談ステージ進行"
   "crm/qualify-lead" "リード選別"
   "crm/convert-lead" "リード転換"
   "crm/disclose-account" "取引先情報開示"
   "marketing/send-campaign" "キャンペーン送信"
   "marketing/advance-lead" "ライフサイクル進行"
   "marketing/score-lead" "リードスコア更新"
   "mail/send" "メール送信"
   "document/generate-deck" "デッキ下書き"
   "document/publish-deck" "デッキ公開"
   "calendar/schedule-event" "予定下書き"
   "calendar/share-event" "予定共有"
   "chat/draft-message" "チャット下書き"
   "chat/post-message" "チャット投稿"
   "billing.change" "課金変更"})

(defn label-for [kind]
  (let [k (some-> kind name)]
    (get kind-label k (or k "(不明な種別)"))))

(defn needs-attention?
  "An effect a human still has to decide. The native cockpit's whole reason
  to exist is this subset, so it is a named predicate rather than an inline
  filter."
  [{:keys [state]}]
  (contains? #{"proposed" "waiting-approval"} (some-> state name)))

;; ───────────────────────── pieces ─────────────────────────

(defn metric
  "One headline number. `detail` is optional supporting text."
  [label value detail]
  (skin/card
   [:div {:class "itonami-metric"}
    [:span {:class "itonami-metric__label"} (str label)]
    [:strong {:class "itonami-metric__value"} (str value)]
    (when (seq (str detail))
      [:small {:class "itonami-metric__detail"} (str detail)])]))

(defn effect-row
  "One approval-queue row. Risk is shown as a chip because it is the field a
  human is actually deciding on — burying it in body text is how an
  :external-send row gets approved as if it were :read-only."
  [{:keys [id kind risk state] :as effect}]
  [:div {:class (str "itonami-effect" (when (needs-attention? effect)
                                        " itonami-effect--attention"))}
   [:div {:class "itonami-effect__head"}
    [:span {:class "itonami-effect__kind"} (label-for kind)]
    (skin/chip (risk-label risk) {:tone (if (= "read-only" (some-> risk name))
                                          :neutral
                                          :warning)})]
   [:div {:class "itonami-effect__meta"}
    [:code {:class "itonami-effect__id"} (str id)]
    [:span {:class "itonami-effect__state"} (str (some-> state name))]]])

(defn queue-section
  "The approval queue. Empty is a first-class state, not a blank area — an
  operator must be able to tell 'nothing waiting' from 'failed to load'."
  [effects]
  (let [pending (filter needs-attention? effects)]
    (skin/section
     (skin/heading 2 "承認待ち")
     (cond
       (nil? effects)
       [:p {:class "itonami-empty"} "読み込み中…"]

       (empty? pending)
       [:p {:class "itonami-empty"} "承認待ちの effect はありません。"]

       :else
       (into [:div {:class "itonami-effects"}] (map effect-row pending))))))

(defn header
  [{:keys [org repo]}]
  [:header {:class "itonami-header"}
   (skin/container
    [:span {:class "itonami-brand"} "itonami"]
    [:span {:class "itonami-scope"} (str org "/" repo)])])

(defn metrics-section
  "Headline numbers, with the same three-state treatment `queue-section`
  gets. An empty metrics grid is indistinguishable from a failed render —
  the first real launch of this app showed exactly that: a 現況 heading over
  a blank box, because the boot state carries `:metrics []`. Applying the
  distinction to one section and not the other was the bug."
  [metrics status]
  (skin/section
   (skin/heading 2 "現況")
   (cond
     (seq metrics)
     (into [:div {:class "itonami-metrics"}]
           (map (fn [{:keys [label value detail]}] (metric label value detail))
                metrics))

     (= :error status)
     [:p {:class "itonami-empty"} "取得できませんでした。"]

     :else
     [:p {:class "itonami-empty"} "読み込み中…"])))

(defn screen
  "The whole cockpit as one hiccup tree.

  Pure: `state` in, hiccup out. `local-itonami.shell-app` compiles this to
  kotoba:dom operations and `local-itonami.web` renders the same tree to
  HTML — one view, two surfaces."
  [{:keys [scope metrics effects status] :as _state}]
  [:div {:class "itonami-app"}
   (header scope)
   (skin/container
    (when (= :error status)
      (skin/notice "itonami.cloud に接続できませんでした。" {:tone :error}))
    (metrics-section metrics status)
    (queue-section effects))])
