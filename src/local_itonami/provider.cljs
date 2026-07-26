(ns local-itonami.provider
  "WebView 内で動く host provider の cljs 実装。

  `local-itonami.signin/capabilities` のうち `:where :cljs` の 5 個がここ。
  残りは:

  - `:native-exists` 3 個（Keychain）— `AppDelegate.swift` に実装済み。
    ここからは `postMessage` を投げるだけ。
  - `:native-new` 1 個（`open-authorization-url`）— ASWebAuthenticationSession。
    **未実装**。`request-authorization!` は未実装であることを明示的に返す。

  判断は一切持たない。全部 `local-itonami.signin` / `local-itonami.access` 側。
  ここにあるのは『OS/ブラウザに頼む』だけの機構。"
  (:require [clojure.string :as str]
            [authentication.adapters.webcrypto :as webcrypto]))

;; ───────────────────────── bytes / base64url ─────────────────────────

(defn- ->bytes [x]
  (cond
    (instance? js/Uint8Array x) x
    (instance? js/ArrayBuffer x) (js/Uint8Array. x)
    (sequential? x) (js/Uint8Array. (clj->js (vec x)))
    :else (js/Uint8Array. 0)))

(defn base64url->bytes
  "base64url 文字列 → Uint8Array。JWT の署名部と JWK の n/e を読むのに要る。"
  [s]
  (let [b64 (-> (str s) (str/replace "-" "+") (str/replace "_" "/"))
        pad (case (mod (count b64) 4) 2 "==" 3 "=" "")
        raw (js/atob (str b64 pad))]
    (js/Uint8Array.from raw (fn [c] (.charCodeAt c 0)))))

;; ───────────────────────── :random-bytes / :sha256 ─────────────────────────

(defn random-bytes
  "`js/crypto.getRandomValues`。`oauth2.pkce/generate-code-verifier` が
  期待する `[n] -> bytes` の形。"
  [n]
  (let [buf (js/Uint8Array. n)]
    (js/crypto.getRandomValues buf)
    (array-seq buf)))

(defn sha256
  "**同期**の SHA-256。`js/crypto.subtle.digest` は Promise を返すので
  `oauth2.pkce/code-challenge` の `[string] -> bytes` 契約に合わない。

  `local-itonami.signin/begin` を async にすると判断層が Promise に汚染される
  ので、代わりに **PKCE 部分だけ webcrypto の非同期版を使い、begin は
  事前計算した challenge を受け取る**形にする — `begin-async` 参照。
  この fn は同期契約を満たせないことを明示するために存在する。"
  [_]
  (throw (ex-info "sha256 は同期では提供できない。begin-async を使う。" {})))

(defn begin-async
  "`signin/begin` の cljs 版。WebCrypto の digest が Promise なので、
  challenge を先に計算してから同期の `signin/begin` 相当を組む。

  `authentication.adapters.webcrypto/pkce-pair` が verifier と challenge を
  まとめて返すので、それをそのまま使う（PKCE の組み立てを再実装しない）。"
  [{:keys [authorize-endpoint client-id redirect-uri hd]}]
  (-> (webcrypto/pkce-pair)
      (.then
       (fn [pair]
         (let [verifier (or (aget pair "verifier") (aget pair "code-verifier"))
               challenge (or (aget pair "challenge") (aget pair "code-challenge"))
               state (webcrypto/random-token 32)
               nonce (webcrypto/random-token 32)
               qs (->> {"response_type" "code"
                        "client_id" client-id
                        "redirect_uri" redirect-uri
                        "scope" "openid email profile"
                        "state" state
                        "nonce" nonce
                        "code_challenge" challenge
                        "code_challenge_method" "S256"
                        ;; UX のみ。実際の判定は ID token の hd claim に対して
                        ;; signin/complete が行う（リクエストパラメータは
                        ;; 利用者が書き換えられる）。
                        "hd" hd}
                       (keep (fn [[k v]] (when v (str (js/encodeURIComponent k) "="
                                                      (js/encodeURIComponent v)))))
                       (str/join "&"))]
           {:url (str authorize-endpoint "?" qs)
            :pending {:code-verifier verifier :state state :nonce nonce :hd hd}})))))

;; ───────────────────────── :http-post-form / :http-get-json ─────────────────────────

(defn http-post-form
  "`[url form-body] -> Promise<{:status :body}>`。body は既に
  `signin/token-request` が組んだ urlencoded 文字列。"
  [url form-body]
  (-> (js/fetch url
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                     :body form-body})
      (.then (fn [res] (-> (.text res)
                           (.then (fn [t] {:status (.-status res) :body t})))))))

(defn http-get-json
  "`[url] -> Promise<parsed-json>`。discovery / JWKS 用。"
  [url]
  (-> (js/fetch url) (.then #(.json %))))

;; ───────────────────────── :verify-jwt-signature ─────────────────────────

(def ^:private alg->params
  "JWT alg → WebCrypto import/verify パラメータ。ここに無い alg は**検証
  しない**（= 未対応 alg を『検証済み』にしない）。`none` を含む未知の alg が
  素通りするのが JWT 実装の古典的な穴なので、許可リストにする。"
  {"RS256" {:import #js {:name "RSASSA-PKCS1-v1_5" :hash "SHA-256"}
            :verify #js {:name "RSASSA-PKCS1-v1_5"}}
   "ES256" {:import #js {:name "ECDSA" :namedCurve "P-256"}
            :verify #js {:name "ECDSA" :hash #js {:name "SHA-256"}}}})

(defn verify-jwt-signature
  "`[signing-input signature jwk alg] -> Promise<boolean>`。

  `js/crypto.subtle.verify` に委譲する。**true/false 以外は返さない** —
  `signin/complete` は `true?` で判定するので、ここが Promise を解決し損ねて
  undefined を返しても許可にはならない。"
  [signing-input signature jwk alg]
  (if-let [{:keys [import verify]} (get alg->params alg)]
    (-> (js/crypto.subtle.importKey "jwk" (clj->js jwk) import false #js ["verify"])
        (.then (fn [key]
                 (js/crypto.subtle.verify verify key (->bytes signature)
                                          (.encode (js/TextEncoder.) signing-input))))
        (.then (fn [ok] (true? ok)))
        (.catch (fn [_] false)))
    (js/Promise.resolve false)))

(defn jwk-for
  "JWKS から `kid` に一致する鍵を選ぶ。一致が無ければ nil —
  『どれか1つで試す』はしない（kid を無視すると失効鍵での検証が通りうる）。"
  [jwks kid]
  (some (fn [k] (when (= kid (get k "kid")) k))
        (js->clj (or (aget jwks "keys") #js []))))

;; ───────────────────────── :native-exists（Keychain bridge）─────────────────────────

(def ^:private auth-message-name
  "app.kotoba.edn の :macos/auth-message-name と一致させる。"
  "itonamiAuth")

(defn- post-native! [payload]
  (if-let [h (some-> js/window .-webkit .-messageHandlers (aget auth-message-name))]
    (do (.postMessage h (clj->js payload)) true)
    false))

(defn request-session! [] (post-native! {:action "request-session"}))
(defn save-session! [email cacao-b64]
  (post-native! {:action "save-session" :email email :cacaoB64 cacao-b64}))
(defn delete-session! [] (post-native! {:action "delete-session"}))

(defn native-bridge-available?
  "AppDelegate の message handler が居るか。ブラウザで開いたときは false。"
  []
  (some? (some-> js/window .-webkit .-messageHandlers (aget auth-message-name))))

;; ───────────────────────── :native-new（未実装）─────────────────────────

(defn request-authorization!
  "**未実装。** ASWebAuthenticationSession を開く native capability。

  自分の WKWebView を authorization URL へ遷移させれば済むように見えるが、
  Google は embedded webview からの OAuth を拒否する（disallowed_useragent）し、
  自分が制御する WebView に IdP のログイン画面を出すのは、アプリが資格情報を
  覗ける構造そのもの。ASWebAuthenticationSession はそれを不可能にするために
  ある — だからここだけは native に残す。

  `AppDelegate.swift` に `open-authorization-url` アクションを足し、
  redirect URL を `dispatchSession` と同じ経路で返すのが次の作業。"
  [_url]
  {:ok? false
   :error :not-implemented
   :capability :open-authorization-url
   :message "サインインはまだ利用できません（認証セッションが未実装です）。"})
