(ns local-itonami.view-test
  "The load-bearing test here is `views-name-no-design-system-class-directly`:
  it is what actually enforces the 共通化 seam. Everything else in this repo
  can be right and the seam is still worthless if one view hard-codes
  `dads-button`."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [clojure.walk :as walk]
            [local-itonami.skin :as skin]
            [local-itonami.view :as view]))

(def ^:private sample-state
  "サインイン済みの通常状態。ゲート自体のテストは
  `business-data-is-not-rendered-without-an-admitted-session` が別に持つ ——
  ここは skin / レーン / 空状態の検証なので、業務セクションが描かれる前提を
  fixture 側で満たしておく。"
  {:status :ready
   :session {:status :admitted :email "jun@gftd.co.jp"
             :display-name "Jun Kawasaki" :domain "gftd.co.jp"}
   :scope {:org "gftdcojp" :repo "gftdcojp"}
   :metrics [{:label "外部テナント" :value "5" :detail "self-registered 含む"}]
   :effects [{:id "eff-1" :kind "crm/advance-opportunity"
              :risk "financial" :state "proposed"}
             {:id "eff-2" :kind "marketing/send-campaign"
              :risk "external-send" :state "waiting-approval"}
             {:id "eff-3" :kind "mail/send" :risk "external-send" :state "executed"}]})

(defn- classes-in
  "Every class string anywhere in a hiccup tree."
  [tree]
  (let [found (atom [])]
    (walk/postwalk
     (fn [node]
       (when (map? node)
         (when-let [c (:class node)] (swap! found conj (str c))))
       node)
     tree)
    (mapcat #(str/split % #"\s+") @found)))

;; ───────────────── the seam ─────────────────

(deftest views-name-no-design-system-class-directly
  (testing "under the DADS skin the tree carries dads-*/dds-ext-* classes"
    (let [cs (set (classes-in (view/screen sample-state)))]
      (is (contains? cs "dds-ext-container"))
      (is (some #(str/starts-with? % "dads-") cs))))

  (testing "swapping the skin swaps every design-system class, with no view edit"
    (binding [skin/*skin* skin/kotoba-ui]
      (let [cs (set (classes-in (view/screen sample-state)))]
        (is (not-any? #(or (str/starts-with? % "dads-")
                           (str/starts-with? % "dds-ext-"))
                      cs)
            "a DADS class survived a skin swap -- something is hard-coded")
        (is (contains? cs "kui-container")))))

  (testing "app-owned itonami-* classes are skin-independent and survive both"
    (doseq [s [skin/dads skin/kotoba-ui]]
      (binding [skin/*skin* s]
        (let [cs (set (classes-in (view/screen sample-state)))]
          (is (contains? cs "itonami-app"))
          (is (contains? cs "itonami-metric")))))))

(deftest views-contain-no-raw-colour-or-size
  (testing "kotoba-uiux rule 2 -- colour/type come from --hig-* tokens only"
    (let [s (pr-str (view/screen sample-state))]
      (is (not (re-find #"#[0-9a-fA-F]{6}\b" s)) "raw hex in a view")
      (is (not (re-find #"font-size" s)) "ad-hoc font-size in a view"))))

;; ───────────────── queue semantics ─────────────────

(deftest only-undecided-effects-reach-the-queue
  (let [tree (view/queue-section (:effects sample-state))
        s (pr-str tree)]
    (is (str/includes? s "eff-1"))
    (is (str/includes? s "eff-2"))
    (is (not (str/includes? s "eff-3"))
        "an already-executed effect is not waiting on anybody")))

(deftest empty-and-not-loaded-are-distinguishable
  (testing "nil = not loaded yet"
    (is (str/includes? (pr-str (view/queue-section nil)) "読み込み中")))
  (testing "empty = nothing waiting"
    (is (str/includes? (pr-str (view/queue-section [])) "ありません")))
  (testing "all-decided = nothing waiting, not blank"
    (is (str/includes?
         (pr-str (view/queue-section [{:id "e" :kind "mail/send" :state "executed"}]))
         "ありません"))))

(deftest every-bridged-effect-kind-has-a-label
  (testing "the CRM/marketing kinds from ADR-2607261200 must not fall through"
    (doseq [k ["crm/advance-opportunity" "crm/qualify-lead" "crm/convert-lead"
               "crm/disclose-account" "marketing/send-campaign"
               "marketing/advance-lead" "marketing/score-lead"]]
      (is (contains? view/kind-label k) (str k " のラベルが無い"))
      (is (not= k (view/label-for k))))))

(deftest unknown-kind-is-legible-not-blank
  (is (= "future/kind" (view/label-for "future/kind")))
  (is (= "(不明な種別)" (view/label-for nil))))

(deftest risk-is-shown-as-a-chip-on-every-row
  (let [tree (view/effect-row {:id "e" :kind "marketing/send-campaign"
                               :risk "external-send" :state "proposed"})]
    (is (str/includes? (pr-str tree) "外部送信")
        "risk must be visible on the row a human is deciding")))

(deftest needs-attention?-covers-both-undecided-states
  (is (true? (view/needs-attention? {:state "proposed"})))
  (is (true? (view/needs-attention? {:state "waiting-approval"})))
  (is (false? (view/needs-attention? {:state "executed"})))
  (is (false? (view/needs-attention? {:state nil}))))

(deftest metrics-empty-and-not-loaded-are-distinguishable
  (testing "the boot state must not render a heading over a blank box"
    (is (str/includes? (pr-str (view/metrics-section [] :booting)) "読み込み中")))
  (testing "a failed fetch says so rather than showing nothing"
    (is (str/includes? (pr-str (view/metrics-section [] :error)) "取得できませんでした")))
  (testing "real metrics render"
    (is (str/includes?
         (pr-str (view/metrics-section [{:label "外部テナント" :value "5" :detail "d"}] :ready))
         "外部テナント"))))

;; ───────────────── サインインゲート ─────────────────

(def ^:private admitted-session
  {:status :admitted :email "jun@gftd.co.jp" :display-name "Jun Kawasaki"
   :domain "gftd.co.jp"})

(deftest business-data-is-not-rendered-without-an-admitted-session
  (testing "未サインインでは業務セクションを一切描かない"
    (let [s (pr-str (view/screen (assoc sample-state :session nil)))]
      (is (str/includes? s "サインイン"))
      (is (not (str/includes? s "現況")))
      (is (not (str/includes? s "承認待ち")))
      (is (not (str/includes? s "eff-1"))
          "承認待ちの effect id が未サインイン画面に出ている")))

  (testing "拒否されたセッションでも同じ — 理由だけ出す"
    (let [s (pr-str (view/screen
                     (assoc sample-state :session
                            {:status :denied :reason :domain-not-allowed
                             :message "このサービスは gftd.co.jp のアカウントのみ利用できます。"})))]
      (is (str/includes? s "gftd.co.jp のアカウントのみ"))
      (is (not (str/includes? s "eff-1")))
      (is (not (str/includes? s "現況")))))

  (testing "許可されたセッションでのみ業務セクションが出る"
    (let [s (pr-str (view/screen (assoc sample-state :session admitted-session)))]
      (is (str/includes? s "現況"))
      (is (str/includes? s "eff-1"))
      (is (str/includes? s "jun@gftd.co.jp")))))

(deftest effects-are-split-into-lanes
  (let [s (pr-str (view/screen (assoc sample-state :session admitted-session)))]
    (is (str/includes? s "承認待ち — 業務"))
    (is (str/includes? s "承認待ち — 商談"))
    (is (str/includes? s "承認待ち — マーケ")))
  (testing "kind ごとに正しいレーンへ"
    (is (= :crm (view/lane-of {:kind "crm/advance-opportunity"})))
    (is (= :marketing (view/lane-of {:kind "marketing/send-campaign"})))
    (is (= :workspace (view/lane-of {:kind "mail/send"}))))
  (testing "未知の kind は捨てずに :workspace へ"
    (is (= :workspace (view/lane-of {:kind "future/kind"})))
    (is (= :workspace (view/lane-of {:kind nil})))))

(deftest setup-section-never-claims-a-manual-step-is-automatic
  (let [setup {:steps [{:id :resolve-tenant :label "組織テナントの特定"
                        :automatic? true :done? true :detail "特定しました。"}
                       {:id :register-app :label "Entra アプリ登録"
                        :automatic? false :done? false
                        :requires "Entra の管理者権限"
                        :detail "管理者同意が要ります。"}]}
        rendered (pr-str (view/setup-section setup))]
    (is (str/includes? rendered "完了") "終わった段が完了と出ていない")
    (is (str/includes? rendered "要操作") "管理者操作が要る段が自動扱いされている")
    (is (str/includes? rendered "必要: Entra の管理者権限"))))

(deftest setup-section-is-absent-until-discovery-has-run
  (is (nil? (view/setup-section nil))))
