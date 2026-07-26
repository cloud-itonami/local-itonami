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
            [local-itonami.discovery :as discovery]
            [local-itonami.onboarding :as onboarding]
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

(defn resolve-organization!
  "ドメインから組織テナントを引いて state と access に反映する。
  **資格情報も DNS も要らない** — これが domain 連動の1段目。"
  []
  (let [{:keys [url parse]} (onboarding/discovery-request (onboarding/domain-of-record))]
    (-> (provider/http-get-json url)
        (.then (fn [doc]
                 (let [r (parse (js->clj doc))]
                   (when (:ok? r)
                     (set! access/*tenant-id* (:tenant-id r)))
                   (update-state! assoc
                                  :discovery r
                                  :setup (onboarding/plan-for-this-app r))
                   r)))
        (.catch (fn [e]
                  (let [r {:ok? false :error :discovery-failed
                           :detail (.-message e)}]
                    (update-state! assoc :discovery r
                                   :setup (onboarding/plan-for-this-app r))
                    r))))))

(defn- client-id [] (or (aget js/window "ITONAMI_OIDC_CLIENT_ID") ""))

(defn- signin-failed [reason message]
  {:status :denied :reason reason :message message})

(defn- exchange-and-complete!
  "code → token → 署名検証(機構) → claims 検証 + 組織判定(判断)。

  署名検証は WebCrypto が非同期に行い、その **結果(boolean)** を
  `signin/complete` に渡す。判断層に Promise を持ち込まない。"
  [{:keys [code discovery pending]}]
  (let [{:keys [token-endpoint jwks-uri issuer]} discovery
        json-read #(js->clj (js/JSON.parse %) :keywordize-keys true)]
    (-> (provider/http-post-form
         token-endpoint
         (signin/token-request {:code code
                                :client-id (client-id)
                                :redirect-uri onboarding/redirect-uri
                                :code-verifier (:code-verifier pending)}))
        (.then (fn [{:keys [status body]}]
                 (when-not (= 200 status)
                   (throw (js/Error. (str "token endpoint が " status " を返しました"))))
                 (let [id-token (aget (js/JSON.parse body) "id_token")]
                   (when (str/blank? (str id-token))
                     (throw (js/Error. "ID token がありません")))
                   (-> (provider/http-get-json jwks-uri)
                       (.then (fn [jwks] {:id-token id-token :jwks jwks}))))))
        (.then (fn [{:keys [id-token jwks]}]
                 ;; header は署名検証前なので「どの鍵か」のヒントにしか使わない。
                 ;; 鍵そのものは JWKS(TLS で取得)から来る。
                 (let [{:keys [signing-input signature kid alg]}
                       (signin/signing-input-and-signature id-token json-read)
                       jwk (provider/jwk-for jwks kid)]
                   (if-not (and signing-input jwk)
                     {:id-token id-token :verified? false}
                     (-> (provider/verify-jwt-signature signing-input signature jwk alg)
                         (.then (fn [ok] {:id-token id-token :verified? ok})))))))
        (.then (fn [{:keys [id-token verified?]}]
                 (binding [access/*tenant-id* (:tenant-id discovery)]
                   (signin/complete
                    {:id-token id-token
                     :json-read json-read
                     :signature-verified? verified?
                     :issuer issuer
                     :audience (client-id)
                     :nonce (:nonce pending)})))))))

(defn ^:export beginSignIn
  "サインイン開始 → ASWebAuthenticationSession → callback 照合 → token 交換。"
  []
  (let [cid (client-id)]
    (cond
      (str/blank? cid)
      (update-state! assoc :session
                     (signin-failed :no-client-id "OIDC クライアント ID が未設定です。"))

      (not (:ok? (:discovery @state)))
      (update-state! assoc :session
                     (signin-failed :organization-not-configured
                                    "組織テナントを特定できていません。"))

      :else
      (let [{:keys [authorize-endpoint]} (:discovery @state)]
        (-> (provider/begin-async {:authorize-endpoint authorize-endpoint
                                   :client-id cid
                                   :redirect-uri onboarding/redirect-uri})
            (.then (fn [{:keys [url pending]}]
                     (swap! state assoc :pending-signin pending)
                     (provider/request-authorization! url)))
            (.then (fn [{:keys [ok? callback-url cancelled? error]}]
                     (cond
                       cancelled? (update-state! assoc :session nil)
                       (not ok?) (update-state! assoc :session
                                                (signin-failed :authorization-failed error))
                       :else
                       (let [q (provider/callback-url->query callback-url)
                             r (signin/redirect->code q (:pending-signin @state))]
                         (if-not (:ok? r)
                           (update-state! assoc :session
                                          (signin-failed (:error r)
                                                         "サインインを検証できませんでした。"))
                           (-> (exchange-and-complete!
                                {:code (:code r)
                                 :discovery (:discovery @state)
                                 :pending (:pending-signin @state)})
                               (.then #(update-state! assoc :session %))
                               (.catch (fn [e]
                                         (update-state! assoc :session
                                                        (signin-failed
                                                         :token-exchange-failed
                                                         (str "サインインを完了できませんでした: "
                                                              (.-message e)))))))))))) 
            (.catch (fn [e]
                      (update-state! assoc :session
                                     (signin-failed :signin-failed
                                                    (str "サインインを開始できませんでした: "
                                                         (.-message e)))))))))))

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
  ;; ドメイン連動: 起動と同時にテナントを引く。ここは資格情報を要さないので
  ;; サインイン前に完了できる。
  (resolve-organization!)
  (render!))
