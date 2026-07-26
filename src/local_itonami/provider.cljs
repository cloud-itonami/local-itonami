(ns local-itonami.provider
  "WebView 内で動く host provider の cljs 実装。

  `local-itonami.signin/capabilities` のうち `:where :cljs` の 5 個がここ。
  残りは:

  - `:native-exists` 3 個（Keychain）— `AppDelegate.swift` に実装済み。
    ここからは `postMessage` を投げるだけ。
  - `:native-shared` 1 個（`open-authorization-url`）— ASWebAuthenticationSession。
    **kotoba-lang/shell が全アプリ向けに実装済み**なので、この repo に Swift は
    無い。`app.kotoba.edn` の `:macos/oauth-callback-scheme` を設定するだけ。

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
  [{:keys [authorize-endpoint client-id redirect-uri]}]
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
                        "code_challenge_method" "S256"}
                       (keep (fn [[k v]] (when v (str (js/encodeURIComponent k) "="
                                                      (js/encodeURIComponent v)))))
                       (str/join "&"))]
           {:url (str authorize-endpoint "?" qs)
            :pending {:code-verifier verifier :state state :nonce nonce}})))))

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

;; ───────────────────── :native-shared（ASWebAuthenticationSession）─────────────────────

(def authorization-event-name
  "kotoba-lang/shell の `dispatchAuthorization` が投げる CustomEvent 名。
  Keychain 側の `itonami-auth-session` と違い、これは shell が全アプリ共通で
  出すので app 固有の名前ではない。"
  "kotoba-shell-authorization")

(defn request-authorization!
  "authorization URL を native の ASWebAuthenticationSession で開き、
  callback URL を Promise で返す。

  host 言語に残る唯一の capability（kotoba-lang/shell が全アプリ向けに実装、
  この repo に Swift は無い）。**ここも判断はしない** — callback URL を
  そのまま返し、state/nonce の照合も claims 検証も
  `local-itonami.signin` が行う。

  戻り値: `{:ok? true :callback-url s}` /
          `{:ok? false :cancelled? true}`（利用者が閉じた）/
          `{:ok? false :error msg}`"
  [url]
  (js/Promise.
   (fn [resolve _reject]
     (if-not (native-bridge-available?)
       (resolve {:ok? false :error "native bridge がありません（ブラウザで開いています）。"})
       (let [handler (atom nil)
             done! (fn [result]
                     (when-let [h @handler]
                       (.removeEventListener js/window authorization-event-name h))
                     (resolve result))]
         (reset! handler
                 (fn [e]
                   (let [d (.-detail e)
                         cb (some-> d (aget "callbackURL"))
                         err (some-> d (aget "error"))
                         cancelled (true? (some-> d (aget "cancelled")))]
                     (done! (cond
                              cancelled {:ok? false :cancelled? true}
                              err {:ok? false :error err}
                              cb {:ok? true :callback-url cb}
                              :else {:ok? false :error "認証セッションが結果を返しませんでした。"})))))
         (.addEventListener js/window authorization-event-name @handler)
         (when-not (post-native! {:action "open-authorization-url" :url url})
           (done! {:ok? false :error "認証セッションを開始できませんでした。"})))))))

(defn callback-url->query
  "callback URL のクエリを map にする。`signin/redirect->code` に渡す形。"
  [callback-url]
  (let [u (js/URL. callback-url)
        params (.-searchParams u)]
    (persistent!
     (reduce (fn [m k] (assoc! m k (.get params k)))
             (transient {})
             (js->clj (js/Array.from (.keys params)))))))
