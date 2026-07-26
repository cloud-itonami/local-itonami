(ns local-itonami.app
  "WebView 内のブラウザエントリ。

  SSR が書いた `index.html` の上に載って、状態が変わるたびに同じ
  `local-itonami.view/screen` を再描画する。**view も判断も共有**していて、
  ここが持つのは DOM への反映と native bridge の受け口だけ。

  reagent を入れていないのは意図的: view は純 hiccup なので
  `html.core/->html` で文字列にして差し替えれば足り、SSR と完全に同じ経路を
  通る（描画差の原因を1つ減らす）。差分更新が要るほど大きい画面ではない。"
  (:require [clojure.string :as str]
            [html.core :as html]
            [local-itonami.access :as access]
            [local-itonami.provider :as provider]
            [local-itonami.shell-app :as shell-app]
            [local-itonami.signin :as signin]
            [local-itonami.view :as view]))

(defonce state (atom shell-app/initial-state))

(defn render!
  "state → DOM。`.itonami-app` を丸ごと差し替える（SSR が出したものと
  同じ要素）。"
  []
  (when-let [root (.querySelector js/document ".itonami-app")]
    (set! (.-outerHTML root) (html/->html (view/screen @state)))))

(defn update-state! [f & args]
  (apply swap! state f args)
  (render!))

;; ───────────────────────── native bridge の受け口 ─────────────────────────

(def session-event-name
  "AppDelegate.swift の `dispatchSession` が投げる CustomEvent 名。

  最初これを `window.itonami.onSession(...)` の関数呼び出しだと思って書いて
  いたが、生成された Swift を読むと実際は
  `window.dispatchEvent(new CustomEvent('itonami-auth-session',{detail:…}))`
  だった。契約は Swift 側が正なので、こちらを合わせる。"
  "itonami-auth-session")

(defn on-session
  "AppDelegate.swift の `dispatchSession` から CustomEvent 経由で届く。

  Keychain に入っているのは既存の CACAO セッション（email + cacaoB64）で、
  OIDC の profile ではない。**この経路では OIDC の hd claim を検証していない**
  ので、`access/session` にそのまま渡してはいけない — 渡すと『Keychain に
  何か入っていれば入れる』になり、ドメイン判定が実質無効化される。

  したがってここでは復元したことだけを記録し、**認可は必ず
  `local-itonami.signin/complete` を通った結果を使う**。Keychain の中身を
  信頼して admit する経路は作らない。"
  [detail]
  (let [email (some-> detail (aget "email"))]
    (update-state!
     (fn [s]
       (if email
         ;; 復元はできたが、これは認可の証拠ではない。
         (assoc s :session {:status :denied
                            :reason :reauthentication-required
                            :message "サインインし直してください。"})
         (assoc s :session nil))))))

;; ───────────────────────── サインイン ─────────────────────────

(defn tenant-id
  "Entra テナント GUID。host が window に載せる。**既定を持たない** —
  未設定なら access が誰も通さない。"
  []
  (some-> (aget js/window "ITONAMI_ENTRA_TENANT_ID") str not-empty))

(defn config
  "Entra ID v2.0 の authorize endpoint はテナントごと。`common` は使わない —
  使うと任意の Microsoft アカウントがサインイン画面を通れてしまい、拒否が
  ID token 検証まで遅れる（最終判定は access が tid で行うので破綻はしないが、
  部外者に無駄な同意画面を見せることになる）。"
  []
  (let [tid (tenant-id)]
    {:authorize-endpoint (str "https://login.microsoftonline.com/" tid
                              "/oauth2/v2.0/authorize")
     :redirect-uri "jp.co.gftd.itonami:/oauth2redirect"}))

(defn- signin-failed [reason message]
  {:status :denied :reason reason :message message})

(defn ^:export beginSignIn
  "サインイン開始 → ASWebAuthenticationSession → callback 照合。

  token 交換から先（`signin/token-request` → `http-post-form` → JWKS →
  `signin/complete`）はまだ配線していない。**できたふりをしない**ので、
  callback を受け取れたところで『続きは未配線』と表示して止まる。"
  []
  (let [client-id (or (aget js/window "ITONAMI_OIDC_CLIENT_ID") "")
        tid (tenant-id)]
    (cond
      (str/blank? client-id)
      (update-state! assoc :session
                     (signin-failed :no-client-id "OIDC クライアント ID が未設定です。"))

      (nil? tid)
      (update-state! assoc :session
                     (signin-failed :organization-not-configured
                                    "組織が未設定のため利用できません。"))

      :else
      (-> (provider/begin-async (assoc (config) :client-id client-id))
          (.then (fn [{:keys [url pending]}]
                   (swap! state assoc :pending-signin pending)
                   (-> (provider/request-authorization! url)
                       (.then
                        (fn [{:keys [ok? callback-url cancelled? error]}]
                          (cond
                            cancelled?
                            ;; 利用者が自分で閉じたので、失敗として騒がない。
                            (update-state! assoc :session nil)

                            (not ok?)
                            (update-state! assoc :session
                                           (signin-failed :authorization-failed error))

                            :else
                            (let [q (provider/callback-url->query callback-url)
                                  r (signin/redirect->code q (:pending-signin @state))]
                              (if-not (:ok? r)
                                ;; state 不一致などはここで止まる。判断は
                                ;; portable 側(signin)がしている。
                                (update-state! assoc :session
                                               (signin-failed (:error r)
                                                              "サインインを検証できませんでした。"))
                                (update-state! assoc :session
                                               (signin-failed
                                                :token-exchange-not-wired
                                                "認可は取得できましたが、トークン交換が未配線です。"))))))))))
          (.catch (fn [e]
                    (update-state! assoc :session
                                   (signin-failed :signin-failed
                                                  (str "サインインを開始できませんでした: "
                                                       (.-message e))))))))))

;; ───────────────────────── boot ─────────────────────────

(defn ^:export init []
  ;; 画面のボタンから呼べるように、サインイン開始だけは window に出す。
  (set! (.-itonami js/window) #js {:beginSignIn beginSignIn})
  (.addEventListener js/window session-event-name
                     (fn [e] (on-session (.-detail e))))
  ;; native bridge が居るなら保存済みセッションを問い合わせる。ブラウザで
  ;; 開いたときは居ないので、未サインインのまま描画する。
  (if (provider/native-bridge-available?)
    (provider/request-session!)
    (update-state! assoc :session nil))
  (render!))
