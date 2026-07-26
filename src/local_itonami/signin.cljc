(ns local-itonami.signin
  "Microsoft Entra ID (Microsoft 365) OIDC サインインの**判断層**。
  1回だけ書く、portable。

  gftd.co.jp は Outlook / M365 で取得されたドメインなので IdP は Entra ID。
  Google の `hd` claim は存在せず、組織の正本は `tid`（テナント GUID）—
  詳細は `local-itonami.access` の ns docstring を参照。

  ## host provider を何語で書くか — の答え

  結論から言うと **kotoba を host 言語へ transpile するのは逆方向**。
  このワークスペースは既に別の答えを採っていて、`org-openid-oidc` の README が
  そのまま言っている: 『署名検証は host の仕事であって、このライブラリの
  仕事ではない』。

  境界は言語ではなく **判断 (judgment) と機構 (mechanism)** で引く
  （`kotoba/docs/lang/application-profile.md` の Ownership boundary、
  および ADR-2607241100 D6 の『decision-free C mechanism』と同じ線）:

  | | 誰が持つ | 何語 | 何回書くか |
  |---|---|---|---|
  | **判断** PKCE 検証・state/nonce 照合・ID token claims 検証・hd/email_verified 抽出・profile 正規化・ドメイン許可 | この ns と `local-itonami.access` | portable（`.cljc` → 将来 `.kotoba`） | **1回** |
  | **機構** transport・JWT 署名検証・Keychain・ブラウザセッション | host provider | **大半は cljs**（WebCrypto/fetch）。native は Keychain(実装済み) と認証セッション(1個)だけ | `capabilities-by-where` を参照 |

  ### なぜ transpile ではないのか

  1. **transpile しても N は減らない。** `ASWebAuthenticationSession`、
     macOS Keychain、`SecKeyVerifySignature` に portable な等価物は無い。
     transpiler を書いても結局 platform ごとの手書き shim を emit することに
     なるので、**N 実装 + transpiler** で増える。
  2. **判断が host 言語に散ると監査できない。** capability confinement の
     利点は『判断が1つの artifact に閉じている』ことなので、judgment を
     Swift/Kotlin に写した時点でその性質を失う。
  3. **N は既に小さい。** 実測: kotoba-lang/shell の描画基盤は WKWebView で、
     生成される `AppDelegate.swift` は 143 行・UI コード無し・message bridge は
     Keychain の3アクションだけ（SwiftUI は使っていない）。つまり capability の
     大半は **WebView 内の cljs** で書けて host 言語には落ちない — 実際
     `capabilities` の 9 個中、新規に native が要るのは 1 個だけ。

  『host 言語ごとに実装が要る』は正しいが、正しい対処は transpile ではなく
  **N を小さく固定する**こと: host は下の `capabilities` に対する判断ゼロの
  shim だけを実装する。

  ## この ns が持たないもの

  乱数も clock も HTTP も crypto も持たない。全部**注入**される
  （`oauth2.pkce/generate-code-verifier` が `random-bytes-fn` を取るのと
  同じ流儀）。だから JVM でも cljs でも同じテストが通り、host を差し替えても
  判断は変わらない。"
  (:require [clojure.string :as str]
            [oauth2.core :as oauth2]
            [oauth2.pkce :as pkce]
            [oidc.core :as oidc]
            [authentication.identity :as identity]
            [local-itonami.access :as access]))

;; ───────────────────────── host capability contract ─────────────────────────

(def capabilities
  "host provider が満たすべき capability の全量と、**それぞれどこで実装するか**。

  ## 訂正（2026-07-26）

  当初これを『host 言語ごとに9個実装する』前提で書いたが、実測すると誤り
  だった。kotoba-lang/shell の描画基盤は WKWebView で、生成される
  `AppDelegate.swift` は 143 行・UI コードなし・message bridge は
  `request-session` / `save-session` / `delete-session` の3つだけ（Keychain と
  生体認証のみ）。SwiftUI は使っていない。

  したがって **9個中8個は cljs（WebCrypto / fetch）で書ける**。しかも
  `authentication.adapters.webcrypto` に `random-token` / `sha256-bytes` /
  `pkce-pair` が既にある。

  `:where` の値:
    `:cljs`         — WebView 内の cljs。host 言語ごとの実装は**不要**
    `:native-exists` — 既に AppDelegate.swift にある（追加実装ゼロ）
    `:native-shared` — native だが kotoba-lang/shell が全アプリ向けに実装済み。
                       このアプリが書く Swift は**ゼロ**"
  {:random-bytes
   {:where :cljs
    :sig "[n] -> bytes"
    :why "PKCE code verifier と state/nonce。"
    :impl "js/crypto.getRandomValues — authentication.adapters.webcrypto/random-token"}

   :sha256
   {:where :cljs
    :sig "[string] -> bytes"
    :why "PKCE S256 code challenge。"
    :impl "js/crypto.subtle.digest — authentication.adapters.webcrypto/sha256-bytes"}

   :http-post-form
   {:where :cljs
    :sig "[url form-body] -> {:status :body}"
    :why "token endpoint への code 交換。"
    :impl "fetch"}

   :http-get-json
   {:where :cljs
    :sig "[url] -> parsed-json"
    :why "discovery document と JWKS の取得。"
    :impl "fetch + js/JSON.parse"}

   :verify-jwt-signature
   {:where :cljs
    :sig "[signing-input signature jwk alg] -> boolean"
    :why "ID token の署名検証。**WebCrypto が RS256/ES256 を標準で持つ**ので
          native に落とす理由が無い（当初 SecKeyVerifySignature に置くと
          書いたのは、WKWebView 前提を見落としていた誤り）。"
    :impl "js/crypto.subtle.importKey + js/crypto.subtle.verify"}

   :store-session
   {:where :native-exists
    :sig "postMessage {:action \"save-session\"}"
    :why "セッション保管。アプリのメモリにも disk にも置かない。"
    :impl "AppDelegate.swift saveSession — 実装済み"}

   :read-session
   {:where :native-exists
    :sig "postMessage {:action \"request-session\"}"
    :why "起動時の復元。LAContext で生体認証も掛かる。"
    :impl "AppDelegate.swift unlockSession/readSession — 実装済み"}

   :delete-session
   {:where :native-exists
    :sig "postMessage {:action \"delete-session\"}"
    :why "サインアウト。"
    :impl "AppDelegate.swift deleteSession — 実装済み"}

   :open-authorization-url
   {:where :native-shared
    :sig "[url] -> redirect-url"
    :why "**native が要る唯一のもの。** 自分の WKWebView を
          accounts.google.com へ遷移させれば済むように見えるが、2つ理由で
          駄目: (1) Google は embedded webview からの OAuth を拒否する
          (disallowed_useragent)。(2) 自分が制御する WebView に IdP の
          ログイン画面を出すと、アプリが資格情報を覗ける構造になる —
          ASWebAuthenticationSession はまさにそれを不可能にするために在る。"
    :impl "ASWebAuthenticationSession — kotoba-lang/shell が全アプリ向けに実装
           （この repo に Swift は無い）。manifest の :macos/oauth-callback-scheme
           を設定するだけで有効になる。"}})

(defn capabilities-by-where
  "実装先ごとの内訳。『host 言語ごとに何個書くのか』の答えがこれ。"
  []
  (reduce-kv (fn [m k v] (update m (:where v) (fnil conj #{}) k)) {} capabilities))

;; ───────────────────────── 1. 開始 ─────────────────────────

(defn begin
  "サインイン開始。authorization URL と、**後で照合するために保持すべき秘密**
  を返す。

  `random-bytes-fn` / `sha256-fn` は host capability。ここで乱数を作らない
  のは、この ns を純関数に保つため（同じ入力で同じ出力 = テストできる）。

  `hd` を authorization request に載せているのは UX（Google のアカウント
  選択画面が組織アカウントに絞られる）であって**セキュリティではない** —
  リクエストパラメータは利用者が書き換えられる。実際の判定は
  `complete` が ID token の `hd` claim に対して行う。"
  [{:keys [authorize-endpoint client-id redirect-uri random-bytes-fn sha256-fn]}]
  (let [verifier (pkce/generate-code-verifier random-bytes-fn)
        {:keys [code-challenge code-challenge-method]} (pkce/code-challenge verifier sha256-fn)
        state (pkce/generate-code-verifier random-bytes-fn)
        nonce (pkce/generate-code-verifier random-bytes-fn)]
    {:url (oauth2/authorization-url
           {:authorize-endpoint authorize-endpoint
            :client-id client-id
            :redirect-uri redirect-uri
            :scope "openid email profile"
            :state state
            :code-challenge code-challenge
            :code-challenge-method code-challenge-method})
     ;; host はこの3つを、リダイレクトが返るまで保持する。
     :pending {:code-verifier verifier :state state :nonce nonce
               :hd access/allowed-domain}}))

;; ───────────────────────── 2. リダイレクト照合 ─────────────────────────

(defn redirect->code
  "リダイレクトのクエリを検証して authorization code を取り出す。

  **state 照合をここでやる。** host に任せると『比較を忘れた host』が
  CSRF を素通しする。等値比較なので host 言語に持っていく理由が無い。"
  [query-params {:keys [state]}]
  (let [parsed (oauth2/parse-authorization-response query-params)]
    (cond
      (:error parsed)
      {:ok? false :error :authorization-error :detail (:error parsed)}

      (not= state (:state parsed))
      {:ok? false :error :state-mismatch}

      (str/blank? (str (:code parsed)))
      {:ok? false :error :no-code}

      :else {:ok? true :code (:code parsed)})))

(defn token-request
  "token endpoint に投げる form body。host は `:http-post-form` で送るだけ。"
  [{:keys [code client-id client-secret redirect-uri code-verifier]}]
  (oauth2/->form-body
   {:grant_type "authorization_code"
    :code code
    :client_id client-id
    :client_secret client-secret
    :redirect_uri redirect-uri
    :code_verifier code-verifier}))

;; ───────────────────────── 3. ID token 検証 → profile ─────────────────────────

(defn- claims->profile
  "検証済み claims を `authentication.identity/normalized-profile` にする。

  Entra ID 固有の点:
  - **`sub` ではなく `oid` が不変のユーザー識別子。** `sub` はアプリごとに
    pairwise で変わるので、アカウント発行の actor id には使えない
    （`access/actor-id` は `oid` を見る）。
  - メールは `email` に入るとは限らず `preferred_username` / `upn` のことも
    ある。どれも検証済みの保証は無いので、そのまま claims に渡して
    `access` 側に判断させる。
  - **`email_verified` は既定で発行されない**ので要求しない。

  `access` が読む claim（`tid`/`iss`/`oid`/`acct`/`preferred_username`/`upn`）を
  そのまま渡す。ここで判定はしない。"
  [claims]
  (identity/normalized-profile
   {:provider :microsoft
    :provider-subject (or (:oid claims) (:sub claims))
    :email (or (:email claims) (:preferred_username claims) (:upn claims))
    :email-verified? false
    :display-name (:name claims)
    :claims (select-keys claims [:iss :aud :tid :oid :acct
                                 :preferred_username :upn])}))

(defn complete
  "token response の ID token を検証し、アクセス判定まで通す。

  `verify-signature-fn` は host capability（`[signing-input signature] -> bool`）。
  **署名が検証できない ID token は claims を一切見ない** — 未検証 JWT の
  claims は攻撃者が書いた文字列でしかないので、そこから hd や email を
  読んで判定するのは判定していないのと同じ。

  戻り値は `local-itonami.access/session` と同じ形なので、そのまま
  cockpit の `:session` に入る。"
  [{:keys [id-token json-read verify-signature-fn issuer audience nonce now]}]
  (let [{:keys [claims signing-input signature]}
        (try (oidc/decode-jwt-segments id-token json-read)
             (catch #?(:clj Exception :cljs :default) _ nil))]
    (cond
      (nil? claims)
      {:status :denied :reason :malformed-id-token
       :message (get access/denial-message :denied)}

      (not (true? (verify-signature-fn signing-input signature)))
      {:status :denied :reason :bad-id-token-signature
       :message (get access/denial-message :denied)}

      :else
      (let [{:keys [valid? errors]}
            (oidc/validate-id-token-claims claims {:issuer issuer :audience audience
                                                   :nonce nonce :now now})]
        (if-not valid?
          {:status :denied :reason :invalid-id-token-claims :detail errors
           :message (get access/denial-message :denied)}
          ;; ここまで来て初めて claims を信じてよい。ドメイン許可は
          ;; local-itonami.access（deny by default）が決める。
          (access/session (claims->profile claims)))))))
