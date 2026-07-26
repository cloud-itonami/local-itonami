(ns local-itonami.app
  "WebView 内のブラウザエントリ。

  SSR が書いた `index.html` の上に載って、状態が変わるたびに同じ
  `local-itonami.view/screen` を再描画する。**view も判断も共有**していて、
  ここが持つのは DOM への反映と native bridge の受け口だけ。

  reagent を入れていないのは意図的: view は純 hiccup なので
  `html.core/->html` で文字列にして差し替えれば足り、SSR と完全に同じ経路を
  通る（描画差の原因を1つ減らす）。差分更新が要るほど大きい画面ではない。"
  (:require [html.core :as html]
            [local-itonami.access :as access]
            [local-itonami.provider :as provider]
            [local-itonami.shell-app :as shell-app]
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

(def config
  "app.kotoba.edn の :api / IdP 設定に対応。client-id は公開値（秘密ではない）。
  client secret は native 側にしか置かない。"
  {:authorize-endpoint "https://accounts.google.com/o/oauth2/v2/auth"
   :redirect-uri "jp.co.gftd.itonami:/oauth2redirect"
   :hd access/allowed-domain})

(defn ^:export beginSignIn
  "サインイン開始。PKCE を組んで authorization URL を作り、native の認証
  セッションに渡す。

  現状は `provider/request-authorization!` が `:not-implemented` を返すので、
  そのメッセージを画面に出して止まる — **できないことをできたように見せない**。"
  []
  (-> (provider/begin-async (assoc config :client-id (or (aget js/window "ITONAMI_OIDC_CLIENT_ID") "")))
      (.then (fn [{:keys [url pending]}]
               (swap! state assoc :pending-signin pending)
               (let [r (provider/request-authorization! url)]
                 (when-not (:ok? r)
                   (update-state! assoc :session {:status :denied
                                                  :reason (:error r)
                                                  :message (:message r)})))))
      (.catch (fn [e]
                (update-state! assoc :session
                               {:status :denied :reason :signin-failed
                                :message (str "サインインを開始できませんでした: " (.-message e))})))))

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
