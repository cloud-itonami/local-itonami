(ns local-itonami.access
  "gftd.co.jp ドメイン限定のアクセス許可。

  ## なぜ authentication ではなく authorization に置くのか

  `kotoba-lang/authentication` が答えるのは **『この人はこの資格情報を本当に
  持っているか』**（`authentication.core/decide` は factor の強度 —
  single-factor / multi-factor / phishing-resistant — しか判定しない）。
  **『この人は gftd のデータを見てよいか』は別の問い**で、それは
  `kotoba-lang/authorization` の担当。

  この2つを混ぜると壊れ方が実際に悪い: 認証を通った時点で入れてしまうと、
  ドメイン判定が factor 検証の副作用になり、新しい factor（passkey・CACAO・
  OTP）を足すたびにドメイン判定を書き足す必要が出る。書き忘れた経路が
  そのまま穴になる。ここでは **factor が何であれ authorization を必ず通る**
  形にしてある。

  ## メールドメインを信じてよい条件

  メールアドレスの文字列だけでは何の証拠にもならない。この ns が allow を
  出すには3つ全部が要る:

  1. **`:identity/email-verified?` が true** — provider が実際に到達確認した
     もの。自己申告のアドレスは証拠ではない。
  2. **domain が完全一致** — `gftd.co.jp` のみ。`evil-gftd.co.jp` も
     `gftd.co.jp.evil.com` も `sub.gftd.co.jp` も通さない（部分一致・接尾辞
     一致は典型的な迂回路）。
  3. **Google Workspace の `hd` claim が一致**（OIDC 経路のとき）— 個人の
     Google アカウントはプロフィール email に任意の文字列を入れられるが、
     `hd`（hosted domain）は IdP が組織所属として発行する。email だけを見て
     `hd` を見ないと、個人アカウントが `@gftd.co.jp` を騙って通る。

  ## deny by default

  policy は `:default-decision :deny`。allow rule に一致しない限り、
  理由が何であれ（provider 不明・claim 欠落・rule の書き漏れ）拒否される。"
  (:require [clojure.string :as str]
            [authentication.identity :as identity]
            [authorization.core :as authz]
            [authorization.model :as m]
            [authorization.adapters.policy :as policy]
            [authorization.adapters.rules :as rules]))

(def allowed-domain
  "許可する唯一のメールドメイン。set ではなく単一値にしてあるのは、
  『とりあえず足す』を安くしないため — 増やすなら意図的な変更として
  ここを触る（と ADR を書く）ことになる。"
  "gftd.co.jp")

(defn email-domain
  "正規化済みメールアドレスの domain 部。

  `authentication.identity/normalize-email` を通す（trim + lower-case +
  形状検査）ので、`  Jun@GFTD.co.jp ` は `gftd.co.jp` になる。
  正規化できないアドレスは nil を返す — ここで例外にしない代わりに、
  nil は下の rule に一致しないので deny に落ちる。"
  [email]
  (when-let [normalized (identity/normalize-email email)]
    (let [at (str/last-index-of normalized "@")]
      (when (and at (< (inc at) (count normalized)))
        (subs normalized (inc at))))))

(defn hosted-domain
  "OIDC の `hd`（hosted domain）claim。Google Workspace が組織所属として
  発行する。個人アカウントには付かない。

  claim が無い provider（passkey / CACAO のようにそもそも email を持たない
  経路）では nil。その場合は下の `context` が `:hd nil` になり、
  `:hd` を要求する allow rule には一致しない = deny。"
  [profile]
  (some-> (get-in profile [:identity/claims :hd]) str str/trim str/lower-case not-empty))

(defn context
  "`authorization` に渡す context。判定材料を**全部**明示的に載せる —
  rule 側が `get-in` で profile を掘らないようにするため（掘らせると
  rule ごとに掘り方が違う、という形で穴ができる）。"
  [profile]
  {:email-domain (email-domain (:identity/email profile))
   :email-verified? (true? (:identity/email-verified? profile))
   :hd (hosted-domain profile)
   :provider (:identity/provider profile)})

(def policy
  "deny-by-default の policy bundle。allow rule は 1 本だけ。

  `:hd` に `#{allowed-domain nil}` ではなく `#{allowed-domain}` を要求して
  いる点が要。nil を許すと『hd claim を出さない provider』が素通りする —
  つまり email だけ騙れば入れる。email 経路を使うなら hd が要る、が本 policy。"
  (rules/policy-bundle
   "local-itonami/domain-admission"
   1
   [{:id :allow-verified-gftd-workspace
     :action :access
     :resource :itonami/tenant
     :decision :allow
     :by "local-itonami.access"
     :reason :verified-gftd-workspace-identity
     :context {:email-domain allowed-domain
               :email-verified? true
               :hd allowed-domain}}]
   {:default-decision :deny}))

(def engine
  "`rules-engine` は `IPolicyEngine`（rule 評価）であって `IAuthorization`
  ではない。`authorization.core/authorize` が呼ぶのは後者なので、
  `policy/policy-port` で包む — この2段構えのおかげで request/decision の
  検証（principal 欠落・deny なのに reason 無し等）が必ず通る。"
  (policy/policy-port (rules/rules-engine policy) {}))

(defn principal
  "判定対象の呼び名。email が正規化できないときは provider-subject に落ちる
  （`authorization.core/request-problems` が principal 欠落を弾くので、
  nil のまま渡すと例外になる）。"
  [profile]
  (or (identity/normalize-email (:identity/email profile))
      (:identity/provider-subject profile)
      "unknown"))

(defn decide
  "profile → authorization decision map。

  `authorization.core/authorize` を通すので、decision は
  `:authz.decision/*` 形状で ledger にそのまま載る（判断の監査証跡が
  この ns の外側で取れる）。

  `request-id` は相関 id。省略時は principal から決定的に導出する — この ns
  は純関数のまま（clock も乱数も持たない）にしておきたいので、ここで
  ランダム id を作らない。監査で個々の試行を区別したい呼び出し側は
  自分で渡す。"
  ([profile] (decide profile (str "local-itonami/access:" (principal profile))))
  ([profile request-id]
   (authz/authorize engine
                    (m/request request-id
                               (principal profile)
                               :access
                               :itonami/tenant
                               {:context (context profile)}))))

(defn admitted?
  "この profile は itonami テナントに入れるか。"
  [profile]
  (= :allow (:authz.decision/decision (decide profile))))

(defn denial-reason
  "拒否された理由を UI に出せる粒度で。**どの条件で落ちたかは出すが、
  『どの条件なら通るか』は出さない** — 総当たりの手がかりを与えないため。"
  [profile]
  (let [{:keys [email-domain email-verified? hd]} (context profile)]
    (cond
      (nil? email-domain) :no-verifiable-email
      (not email-verified?) :email-not-verified
      (not= allowed-domain email-domain) :domain-not-allowed
      (nil? hd) :no-hosted-domain-claim
      (not= allowed-domain hd) :hosted-domain-mismatch
      :else :denied)))

(def denial-message
  "拒否理由の日本語表示。利用者に次の行動が分かる粒度で書く。"
  {:no-verifiable-email "メールアドレスを確認できませんでした。"
   :email-not-verified "メールアドレスが未確認です。確認を完了してください。"
   :domain-not-allowed (str "このサービスは " allowed-domain " のアカウントのみ利用できます。")
   :no-hosted-domain-claim (str allowed-domain " の組織アカウントでサインインしてください。")
   :hosted-domain-mismatch (str "このサービスは " allowed-domain " のアカウントのみ利用できます。")
   :denied "アクセスできません。"})

(defn session
  "profile → cockpit が読むサインイン状態。

  拒否のときは **profile を保持しない** — 表示に必要な理由だけを残す。
  拒否された相手の email/claim を握り続ける理由が無いし、握れば
  画面のどこかに漏れる経路が増える。"
  [profile]
  (if (admitted? profile)
    {:status :admitted
     :email (:identity/email profile)
     :display-name (:identity/display-name profile)
     :domain allowed-domain}
    (let [reason (denial-reason profile)]
      {:status :denied
       :reason reason
       :message (get denial-message reason (:denied denial-message))})))
