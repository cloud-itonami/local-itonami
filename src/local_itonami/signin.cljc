(ns local-itonami.signin
  "Google Workspace OIDC サインインの**判断層**。1回だけ書く、portable。

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
  | **機構** TLS transport・JWT 署名の crypto 検証・Keychain・ブラウザセッション | host provider | Swift / Kotlin / JS | host ごと（ただし**判断ゼロ**） |

  ### なぜ transpile ではないのか

  1. **transpile しても N は減らない。** `ASWebAuthenticationSession`、
     macOS Keychain、`SecKeyVerifySignature` に portable な等価物は無い。
     transpiler を書いても結局 platform ごとの手書き shim を emit することに
     なるので、**N 実装 + transpiler** で増える。
  2. **判断が host 言語に散ると監査できない。** capability confinement の
     利点は『判断が1つの artifact に閉じている』ことなので、judgment を
     Swift/Kotlin に写した時点でその性質を失う。
  3. **N は既に小さい。** 実測: kotoba-lang/shell が生成する macOS の
     `AppDelegate.swift` は約 140 行で、Keychain の read/write/delete と
     WKWebView と message dispatch しか無い — 判断は既にゼロ。

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
  "host provider が満たすべき **capability の全量**。これが『host 言語ごとに
  書く分量』の上限で、どれも判断を含まない。

  この map を実装の checklist として使う（増やすときは、その capability が
  本当に機構であって判断でないかを先に確かめる）。"
  {:random-bytes
   {:sig "[n] -> bytes"
    :why "PKCE code verifier と state/nonce の生成。CSPRNG は OS のもの。"
    :macos "SecRandomCopyBytes"}

   :sha256
   {:sig "[string] -> bytes"
    :why "PKCE S256 code challenge。"
    :macos "CryptoKit SHA256"}

   :open-authorization-url
   {:sig "[url] -> (redirect query params)"
    :why "ユーザーが IdP で同意する。ここはアプリ内 WebView ではなく OS の
          認証セッションを使う — アプリが資格情報のフォームを描かない、が
          安全床(root CLAUDE.md ①)。"
    :macos "ASWebAuthenticationSession"}

   :http-post-form
   {:sig "[url form-params] -> {:status :body}"
    :why "token endpoint への code 交換。client secret を運ぶので TLS は
          OS のスタックに任せる。"
    :macos "URLSession"}

   :http-get-json
   {:sig "[url] -> parsed-json"
    :why "discovery document と JWKS の取得。"
    :macos "URLSession + JSONSerialization"}

   :verify-jwt-signature
   {:sig "[signing-input signature jwk alg] -> boolean"
    :why "**この一点だけは絶対に host に置く。** 署名検証を portable 側で
          やろうとすると RS256/ES256 の実装を自前で持つことになり、そこが
          最も壊れやすい。OS の検証済み実装を使う。"
    :macos "SecKeyVerifySignature"}

   :store-session
   {:sig "[session] -> ok"
    :why "セッションの保管。アプリのメモリにも disk にも置かない。"
    :macos "Keychain (kSecClassGenericPassword)"}

   :read-session
   {:sig "[] -> session|nil"
    :why "起動時の復元。"
    :macos "Keychain"}

   :delete-session
   {:sig "[] -> ok"
    :why "サインアウト。"
    :macos "Keychain"}})

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
  `:hd` を `:identity/claims` に載せるのは、`local-itonami.access` が
  hosted domain を見るため。"
  [claims]
  (identity/normalized-profile
   {:provider :google
    :provider-subject (:sub claims)
    :email (:email claims)
    :email-verified? (true? (:email_verified claims))
    :display-name (:name claims)
    :avatar-url (:picture claims)
    :claims (select-keys claims [:iss :aud :hd])}))

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
