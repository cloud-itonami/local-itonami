(ns local-itonami.access
  "組織アカウント限定のアクセス許可と、その組織に紐づく cloud-itonami
  アカウントの自動発行。

  ## なぜ authentication ではなく authorization に置くのか

  `kotoba-lang/authentication` が答えるのは『この人はこの資格情報を本当に
  持っているか』（`authentication.core/decide` は factor の強度しか判定
  しない）。『この人は gftd のデータを見てよいか』は別の問いで、
  `kotoba-lang/authorization` の担当。混ぜると、新しい factor を足すたびに
  組織判定を書き足す必要が出て、書き忘れた経路がそのまま穴になる。

  ## 訂正（2026-07-26）: gftd.co.jp は Microsoft 365 ドメイン

  当初この ns は Google Workspace の `hd`（hosted domain）claim を前提に
  書いていた。**gftd.co.jp は Outlook / Microsoft 365 で取得されたドメイン**
  （`gftdcojp/m365-archive` が Graph API で同テナントのメールを取っている）
  なので、その前提は誤りだった。**Entra ID に `hd` claim は存在しない。**

  ## Entra ID で『組織』を identify するもの

  | | Google Workspace | Microsoft Entra ID |
  |---|---|---|
  | 組織 binding | `hd`（ドメイン文字列） | **`tid`（テナント GUID）** |
  | issuer | 全組織で共通 | **テナントごと** `.../{tid}/v2.0` |
  | `email_verified` | 出る | **既定では出ない** |

  Entra では **`tid` が正本**で、ドメイン文字列は副次的な確認にすぎない。
  ドメインはテナントに追加・削除できるし、`email` / `preferred_username` は
  構成によっては信頼できない（B2B ゲスト、外部 IdP 連携が典型）。

  そして **`email_verified` を要求できない** — Entra は既定で発行しない。
  Google 版で書いた『未検証メールは通さない』をそのまま持ち込むと
  **常に deny になる**。代わりに tid + issuer + member/guest を材料にする。

  ## 3つの条件（すべて必須）

  1. **`tid` が設定済みテナント GUID と完全一致。** これが組織の正本。
  2. **`iss` がその `tid` のテナント issuer と一致。** 別テナントで発行された
     トークンを使い回す経路を塞ぐ（multi-tenant アプリが `tid` を検証しない
     のは既知の穴。本 ns は単一テナント固定で構造的に避ける）。
  3. **ゲストでない。** B2B ゲストは**我々の `tid` を持つ**が組織の人間では
     ない。ここを見落とすと『テナントに招待された外部の誰か』が入れる。

  ## deny by default

  `:default-decision :deny`。テナント GUID が未設定なら**誰も通らない**
  （設定漏れが素通りにならない）。"
  (:require [clojure.string :as str]
            [authentication.identity :as identity]
            [authorization.core :as authz]
            [authorization.model :as m]
            [authorization.adapters.policy :as policy]
            [authorization.adapters.rules :as rules]))

;; ───────────────────────── 組織の設定 ─────────────────────────

(def allowed-domain
  "組織のメールドメイン。**これ単独では許可の根拠にならない** — Entra では
  `tid` が正本で、ドメインは member/guest の判別に使う副次的な材料。"
  "gftd.co.jp")

(def entra-issuer-prefix "https://login.microsoftonline.com/")

(defn tenant-issuer
  "Entra ID v2.0 のテナント issuer。`tid` から決定的に導出する — 別々に
  設定できるようにすると、食い違いがそのまま穴になる。"
  [tenant-id]
  (str entra-issuer-prefix tenant-id "/v2.0"))

(def ^:dynamic *tenant-id*
  "gftd.co.jp の Entra テナント GUID。

  **既定は nil。** 秘密ではないが設定値であり、ここに何か焼くと『別テナントを
  許可した状態で出荷する』が起こりうる。未設定なら誰も通らない。host が
  `app.kotoba.edn` / env から解決して bind する。"
  nil)

;; ───────────────────────── claims → 判定材料 ─────────────────────────

(defn email-domain
  [email]
  (when-let [normalized (identity/normalize-email email)]
    (let [at (str/last-index-of normalized "@")]
      (when (and at (< (inc at) (count normalized)))
        (subs normalized (inc at))))))

(defn principal-email
  "Entra は状況により `email` / `preferred_username` / `upn` のどれかに
  アドレスを入れる。**どれも『検証済み』の保証は無い**ので、ここで取るのは
  表示と member/guest 判別のためであって、許可の根拠ではない。"
  [profile]
  (or (identity/normalize-email (:identity/email profile))
      (identity/normalize-email (get-in profile [:identity/claims :preferred_username]))
      (identity/normalize-email (get-in profile [:identity/claims :upn]))))

(defn guest?
  "B2B ゲストか。`acct` claim（0=member, 1=guest）が正だが optional claim
  なので**出ないことがある**。出ていなければドメイン不一致をゲストとみなす —
  判定不能を『member 扱い』に倒さない。"
  [profile]
  (let [acct (get-in profile [:identity/claims :acct])
        domain (email-domain (principal-email profile))]
    (cond
      (= 1 acct) true
      (= 0 acct) (not= allowed-domain domain)
      :else (not= allowed-domain domain))))

(defn context
  "`authorization` に渡す判定材料。rule 側に profile を掘らせない。"
  [profile]
  (let [claims (:identity/claims profile)
        tid (some-> (:tid claims) str str/trim str/lower-case not-empty)
        expected (some-> *tenant-id* str str/trim str/lower-case not-empty)]
    {:provider (:identity/provider profile)
     :tid tid
     :tenant-matches? (boolean (and expected tid (= expected tid)))
     :issuer-matches-tenant? (boolean (and expected
                                           (= (:iss claims) (tenant-issuer expected))))
     :email-domain (email-domain (principal-email profile))
     :guest? (guest? profile)}))

;; ───────────────────────── policy ─────────────────────────

(def policy
  "deny-by-default。allow rule は1本だけ。

  `:tenant-matches?` / `:issuer-matches-tenant?` を boolean にしてあるのは
  rule に GUID を書かないため — テナント GUID は設定値で、policy は
  『設定と一致したか』だけを見る。"
  (rules/policy-bundle
   "local-itonami/organization-admission"
   2
   [{:id :allow-entra-tenant-member
     :action :access
     :resource :itonami/tenant
     :decision :allow
     :by "local-itonami.access"
     :reason :verified-organization-member
     :context {:provider :microsoft
               :tenant-matches? true
               :issuer-matches-tenant? true
               :guest? false
               :email-domain allowed-domain}}]
   {:default-decision :deny}))

(def engine (policy/policy-port (rules/rules-engine policy) {}))

(defn principal [profile]
  (or (principal-email profile)
      (:identity/provider-subject profile)
      "unknown"))

(defn decide
  ([profile] (decide profile (str "local-itonami/access:" (principal profile))))
  ([profile request-id]
   (authz/authorize engine
                    (m/request request-id (principal profile) :access :itonami/tenant
                               {:context (context profile)}))))

(defn admitted? [profile]
  (= :allow (:authz.decision/decision (decide profile))))

(defn denial-reason
  "拒否理由。どの条件で落ちたかは出すが、通過条件の総当たり手がかりは出さない。"
  [profile]
  (let [{:keys [provider tid tenant-matches? issuer-matches-tenant?
                guest? email-domain]} (context profile)]
    (cond
      (nil? *tenant-id*) :organization-not-configured
      (not= :microsoft provider) :unsupported-provider
      (nil? tid) :no-tenant-claim
      (not tenant-matches?) :different-organization
      (not issuer-matches-tenant?) :issuer-tenant-mismatch
      guest? :guest-account
      (not= allowed-domain email-domain) :domain-not-allowed
      :else :denied)))

(def denial-message
  {:organization-not-configured "組織が未設定のため利用できません。"
   :unsupported-provider (str allowed-domain " の組織アカウントでサインインしてください。")
   :no-tenant-claim (str allowed-domain " の組織アカウントでサインインしてください。")
   :different-organization (str "このサービスは " allowed-domain " の組織アカウントのみ利用できます。")
   :issuer-tenant-mismatch "サインイン情報を検証できませんでした。"
   :guest-account "ゲストアカウントでは利用できません。"
   :domain-not-allowed (str "このサービスは " allowed-domain " のアカウントのみ利用できます。")
   :denied "アクセスできません。"})

(defn session
  "profile → cockpit のサインイン状態。拒否時は identity を保持しない。"
  [profile]
  (if (admitted? profile)
    {:status :admitted
     :email (principal-email profile)
     :display-name (:identity/display-name profile)
     :domain allowed-domain}
    (let [reason (denial-reason profile)]
      {:status :denied
       :reason reason
       :message (get denial-message reason (:denied denial-message))})))

;; ───────────────────────── ドメイン連動のアカウント発行 ─────────────────────────

(def cloud-itonami-org
  "発行先の cloud-itonami テナント（ADR-2607022300 の private 本番テナント）。"
  {:org "gftdcojp" :repo "gftdcojp"})

(def member-capabilities
  "組織メンバーに既定で与える capability。

  **`:effect/approve` を含めない。** サインインできることと、`:financial` な
  商談進行や `:external-send` なキャンペーン送信を承認してよいことは別。
  承認権限は明示的な付与に留める（承認の lane スコープが未実装であることは
  ADR-2607261200 に記録済み）。"
  #{:queue/read :effect/propose :audit/read})

(defn actor-id
  "cloud-itonami の actor id。**`oid`（Entra の不変ユーザー GUID）を使う** —
  メールアドレスは改称で変わるが `oid` は変わらない。`oid` が無ければ
  発行しない。"
  [profile]
  (some-> (get-in profile [:identity/claims :oid]) str str/trim not-empty))

(defn provision
  "許可された profile から cloud-itonami アカウント発行リクエストを作る。

  **純関数** — ここでは何も書き込まない。テナントストアへの transact は
  cloud-itonami 側（`cloud-itonami.tenant`）の仕事で、この ns は『誰を、
  どの org/repo に、どの capability で』を決めるだけ。

  発行しない条件を silent にしない: 許可されていない profile も、`oid` の
  無い profile も `{:provision? false :reason ...}` を返す。"
  [profile]
  (cond
    (not (admitted? profile))
    {:provision? false :reason (denial-reason profile)}

    (nil? (actor-id profile))
    {:provision? false :reason :no-stable-user-id}

    :else
    (let [email (principal-email profile)]
      {:provision? true
       :org (:org cloud-itonami-org)
       :repo (:repo cloud-itonami-org)
       :actor (actor-id profile)
       :actor-name (or (:identity/display-name profile) email)
       :email email
       :role :contributor
       :capabilities member-capabilities
       ;; 由来を残す。後から『なぜこの actor が居るのか』を台帳だけで
       ;; 説明できるようにする。
       :provenance {:provider :microsoft
                    :tenant-id *tenant-id*
                    :issuer (tenant-issuer *tenant-id*)}})))
