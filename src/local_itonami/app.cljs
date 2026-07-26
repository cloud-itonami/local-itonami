(ns local-itonami.app
  "WebView 内のブラウザエントリ。

  SSR が書いた `index.html` の上に載って、状態が変わるたびに同じ
  `local-itonami.view/screen` を再描画する。**view も判断も共有**していて、
  ここが持つのは DOM への反映と HTTP / WebAuthn の呼び出しだけ。

  reagent を入れていないのは意図的: view は純 hiccup なので
  `html.core/->html` で文字列にして差し替えれば足り、SSR と完全に同じ経路を
  通る（描画差の原因を1つ減らす）。差分更新が要るほど大きい画面ではない。

  ## サインインは cloud-itonami 自身が提供する（ADR-2607262300）

  以前ここには Microsoft Entra ID の OIDC 一式（`signin` / `access` /
  `discovery` / `onboarding`）が入っていた。cloud-itonami が自前で identity を
  提供するようになったので、`/api/auth/*` を呼ぶだけになった。

  **このファイルは『このアドレスは通してよいか』を一切判断しない** —
  判断はサーバ（`cloud-itonami.edge.auth-endpoints`）、ここは状態の畳み込み
  （`local-itonami.org-signin`）と描画だけ。両方に判定を置くと必ずずれて、
  緩い側が穴になる。"
  (:require [html.core :as html]
            [local-itonami.org-signin :as org-signin]
            [local-itonami.provider :as provider]
            [local-itonami.shell-app :as shell-app]
            [local-itonami.view :as view]))

(defonce state (atom shell-app/initial-state))

(def api-origin
  "本番。WebView は `kotoba-webbundle://` スキームで読み込まれるので相対 URL が
  使えない — 明示する。"
  "https://itonami.cloud")

(defn render!
  "state → DOM。`.itonami-app` を丸ごと差し替える（SSR が出したものと
  同じ要素）。"
  []
  (when-let [root (.querySelector js/document ".itonami-app")]
    (set! (.-outerHTML root) (html/->html (view/screen @state)))))

(defn update-state! [f & args]
  (apply swap! state f args)
  (render!))

(defn- input-value [id]
  (some-> (.getElementById js/document id) (.-value) str))

(defn- post!
  "keywordize した応答を返す Promise。

  **HTTP の失敗と『通さない』を混ぜない** — 前者は `:failed`（接続できない）、
  後者は応答の `:ok false`（資格が無い）。混ぜると、サーバが落ちているのか
  自分の入力が悪いのか利用者に分からない。"
  [path body]
  (-> (js/fetch (str api-origin path)
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify (clj->js body))})
      (.then (fn [r] (.then (.json r) (fn [j] (js->clj j :keywordize-keys true)))))))

;; ───────────────────────── base64url ─────────────────────────

(defn- b64url->buf [s]
  (let [b64 (-> (str s)
                (.replace (js/RegExp. "-" "g") "+")
                (.replace (js/RegExp. "_" "g") "/"))
        pad (case (mod (count b64) 4) 2 "==" 3 "=" "")
        bin (js/atob (str b64 pad))
        out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
    (.-buffer out)))

(defn- buf->b64url [buf]
  (let [bytes (js/Uint8Array. buf)]
    (-> (js/btoa (.apply js/String.fromCharCode nil bytes))
        (.replace (js/RegExp. "\\+" "g") "-")
        (.replace (js/RegExp. "/" "g") "_")
        (.replace (js/RegExp. "=+$") ""))))

;; ───────────────────────── native bridge の受け口 ─────────────────────────

(def session-event-name
  "AppDelegate.swift の `dispatchSession` が投げる CustomEvent 名。

  最初これを `window.itonami.onSession(...)` の関数呼び出しだと思って書いて
  いたが、生成された Swift を読むと実際は
  `window.dispatchEvent(new CustomEvent('itonami-auth-session',{detail:…}))`
  だった。契約は Swift 側が正なので、こちらを合わせる。"
  "itonami-auth-session")

(defn on-session
  "Keychain の保存済みセッションが CustomEvent 経由で届く。

  **復元できたことは認可の証拠ではない。** Keychain に何か入っていれば入れる、
  にすると保存物を信頼した認可になる。ここでは『サインインし直してください』
  を出すだけで、業務データは描かない。"
  [detail]
  (let [email (some-> detail (aget "email"))]
    (update-state!
     (fn [s]
       (if email
         (-> s
             (assoc :org-signin/email email :org-signin/step :email)
             (assoc :org-signin/message "サインインし直してください。"
                    :org-signin/tone nil))
         s)))))

;; ───────────────────────── 1. 組織の判別 ─────────────────────────

(defn ^:export continueSignIn []
  (let [next-state (org-signin/submit-email @state (input-value "signin-email"))]
    (reset! state next-state)
    (render!)
    ;; 形で弾かれたら通信しない。
    (when (:org-signin/busy? next-state)
      (-> (post! "/api/auth/discover" {:email (:org-signin/email next-state)})
          (.then (fn [r] (update-state! shell-app/apply-identity :discovered r)))
          (.catch (fn [_] (update-state! shell-app/apply-identity :failed nil)))))))

;; ───────────────────────── 2. 初期パスワード ─────────────────────────

(defn ^:export submitPassword []
  (let [pw (input-value "signin-password")]
    (if (empty? pw)
      (update-state! shell-app/apply-identity :password
                     {:ok false :error "初期パスワードを入力してください。"})
      (do
        (update-state! org-signin/busy)
        (-> (post! "/api/auth/password" {:email (:org-signin/email @state) :password pw})
            (.then (fn [r] (update-state! shell-app/apply-identity :password r)))
            (.catch (fn [_] (update-state! shell-app/apply-identity :failed nil))))))))

;; ───────────────────────── 3. パスキー ─────────────────────────

(def rp-id
  "パスキーが紐づく Relying Party。**この値を名乗れるのは、同じドメインから
  配信されたページだけ**（WebAuthn の RP ID 制約）。"
  "itonami.cloud")

(defn passkey-possible-here?
  "この origin から RP ID `itonami.cloud` のパスキーを作れるか。

  作れるのは `itonami.cloud` そのものか、その登録可能サブドメインから配信
  されたページだけ。このアプリは native では `kotoba-webbundle://`、開発時は
  `localhost` なので **どちらも作れない** — 試す前に分かる。

  試してから SecurityError を握るのでは『接続できませんでした』のような
  無関係な失敗に見えるので、**先に判定して正しい導線を出す**。"
  []
  (let [h (str (.-hostname js/location))]
    (or (= h rp-id) (.endsWith h (str "." rp-id)))))

(defn ^:export createPasskey []
  (if-not (passkey-possible-here?)
    ;; WebView / localhost からは構造的に作れない。ブラウザへ渡す。
    (update-state! shell-app/apply-identity :rp-mismatch nil)
    (if-not (exists? js/PublicKeyCredential)
      (update-state! shell-app/apply-identity :enrolled
                     {:ok false :error "この端末はパスキーに対応していません。"})
    (let [s @state
          tenant (:org-signin/tenant s)
          email (:org-signin/email s)
          token (:org-signin/enrollment-token s)]
      (update-state! org-signin/busy)
      (-> (post! "/api/webauthn/challenge"
                 {:resources [(str "kotoba://itonami/" tenant "/enroll")]})
          (.then (fn [r]
                   (when-not (:ok r) (throw (js/Error. "challenge failed")))
                   (.create js/navigator.credentials
                            #js {:publicKey
                                 #js {:challenge (b64url->buf (:challenge r))
                                      :rp #js {:name "cloud-itonami" :id "itonami.cloud"}
                                      ;; user.id にアドレスそのものを入れない。
                                      ;; 本人の特定は引換券でサーバが行う。
                                      :user #js {:id (b64url->buf
                                                      (buf->b64url
                                                       (.-buffer (.encode (js/TextEncoder.) (str tenant)))))
                                                 :name email
                                                 :displayName email}
                                      :pubKeyCredParams #js [#js {:type "public-key" :alg -7}]
                                      :authenticatorSelection #js {:residentKey "preferred"
                                                                   :userVerification "preferred"}
                                      :timeout 120000
                                      :attestation "none"}})))
          (.then (fn [cred]
                   ;; `.-rawId` / `.-response` のような dot-property は
                   ;; **:advanced で改名されて実行時に壊れる**（PublicKeyCredential は
                   ;; Closure の externs が完全には覆っていない外部オブジェクト）。
                   ;; ビルドが :infer-warning で教えてくれたので aget にする。
                   (let [resp (aget cred "response")]
                     (post! "/api/auth/enroll-passkey"
                            {:enrollmentToken token
                             :credentialIdB64url (buf->b64url (aget cred "rawId"))
                             :clientDataJsonB64url (buf->b64url (aget resp "clientDataJSON"))
                             :attestationObjectB64url (buf->b64url (aget resp "attestationObject"))}))))
          (.then (fn [r] (update-state! shell-app/apply-identity :enrolled r)))
          (.catch (fn [e]
                    (let [n (when e (aget e "name"))]
                      (cond
                        ;; 利用者が生体認証を取り消しただけ。失敗にしない。
                        (or (= n "NotAllowedError") (= n "AbortError"))
                        (update-state! shell-app/apply-identity :cancelled nil)

                        ;; RP ID がこの origin で使えない。**通信の失敗ではない**
                        ;; ので、そう言わない（passkey-possible-here? で普通は
                        ;; ここまで来ないが、来たときに誤診しないため）。
                        (= n "SecurityError")
                        (update-state! shell-app/apply-identity :rp-mismatch nil)

                        :else
                        (update-state! shell-app/apply-identity :failed nil))))))))))

(defn ^:export openBrowserSignIn
  "パスキー登録をシステムブラウザ（ASWebAuthenticationSession）で続ける。

  native bridge が居ればそちらへ、居なければ普通に新しいタブで開く。"
  []
  (let [url (org-signin/passkey-origin-url @state)]
    (if (provider/native-bridge-available?)
      (provider/request-authorization! url)
      (.open js/window url "_blank" "noopener"))))

;; ───────────────────────── boot ─────────────────────────

(defn ^:export init []
  ;; 画面のボタンから呼べるように window に出す。SSR だけの状態では
  ;; window.itonami が無いので押しても何も起きない —— このボタンが動くこと
  ;; 自体が『WebView 内で cljs が生きている』の観測点になる。
  (set! (.-itonami js/window)
        #js {:continueSignIn continueSignIn
             :submitPassword submitPassword
             :createPasskey createPasskey
             :openBrowserSignIn openBrowserSignIn})
  (.addEventListener js/window session-event-name
                     (fn [e] (on-session (.-detail e))))
  ;; native bridge（Keychain の CACAO）が居れば保存済みセッションを問い合わせる。
  ;; ブラウザで開いたときは居ないので、未サインインのまま描画する。
  (when (provider/native-bridge-available?)
    (provider/request-session!))
  (render!))
