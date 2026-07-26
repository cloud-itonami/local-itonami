(ns local-itonami.signin-test
  "判断層の契約テスト。host capability は全部注入なので、実ネットワークも
  実 crypto も無しで、判断だけを決定論的に検証できる — それがこの分割の
  実利でもある。"
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [local-itonami.access :as access]
            [local-itonami.signin :as signin]))

(def ^:private tenant "11111111-2222-3333-4444-555555555555")

;; ───────────────────────── 注入する偽 capability ─────────────────────────

(defn- counting-bytes
  "呼び出しごとに違うバイト列を返す決定論的な偽 CSPRNG。

  最初は毎回同じバイトを返す実装にしていたが、それだと verifier と state と
  nonce が同一文字列になり、『verifier が URL に漏れていない』の検証が
  state との衝突で偽陽性になった（実際に一度落ちた）。フィクスチャが
  区別できないものは、テストも区別できない。"
  []
  (let [n (atom 0)]
    (fn [len] (let [i (swap! n inc)] (repeat len i)))))

(defn- fixed-sha [] (fn [_] (repeat 32 42)))
(defn- json-read [_] {})

(defn- begin-opts []
  {:authorize-endpoint "https://accounts.google.com/o/oauth2/v2/auth"
   :client-id "cid.apps.googleusercontent.com"
   :redirect-uri "jp.co.gftd.itonami:/oauth2redirect"
   :random-bytes-fn (counting-bytes)
   :sha256-fn (fixed-sha)})

;; ───────────────────────── capability 契約 ─────────────────────────

(deftest capability-contract-is-mechanism-only
  (testing "host が書く分量はこの map が上限"
    (is (= #{:random-bytes :sha256 :open-authorization-url :http-post-form
             :http-get-json :verify-jwt-signature
             :store-session :read-session :delete-session}
           (set (keys signin/capabilities)))))
  (testing "各 capability に signature・理由・実装先・実装場所がある"
    (doseq [[k v] signin/capabilities]
      (is (string? (:sig v)) (str k " に :sig が無い"))
      (is (string? (:why v)) (str k " に :why が無い"))
      (is (string? (:impl v)) (str k " に実装先が無い"))
      (is (contains? #{:cljs :native-exists :native-shared} (:where v))
          (str k " の :where が不正")))))

(deftest host-language-work-is-exactly-one-capability
  (testing "kotoba-shell は WKWebView なので、大半は cljs で書ける"
    (let [by (signin/capabilities-by-where)]
      (is (= #{:random-bytes :sha256 :http-post-form :http-get-json
               :verify-jwt-signature}
             (:cljs by))
          "cljs で書ける capability の集合が変わった")
      (is (= #{:store-session :read-session :delete-session}
             (:native-exists by))
          "AppDelegate.swift に既にあるのは Keychain の3つ")
      (is (= #{:open-authorization-url} (:native-shared by))
          "native だが kotoba-lang/shell が全アプリ向けに実装済み")
      (is (nil? (:native-new by))
          "このアプリが自分で書く Swift はゼロ — 増えるなら、その capability が
           本当に機構か(判断でないか)を疑う")))

  (testing "署名検証は cljs 側。WebCrypto が RS256/ES256 を持つので native に
            落とす理由が無い(当初 SecKeyVerifySignature と書いたのは誤り)"
    (is (= :cljs (get-in signin/capabilities [:verify-jwt-signature :where])))))

;; ───────────────────────── begin ─────────────────────────

(deftest begin-builds-a-pkce-authorization-request
  (let [{:keys [url pending]} (signin/begin (begin-opts))]
    (is (str/includes? url "code_challenge_method=S256"))
    (is (str/includes? url "response_type=code"))
    (is (str/includes? url "scope=openid"))
    (is (every? pending [:code-verifier :state :nonce]))
    (testing "code verifier は URL に出ない — 出たら PKCE の意味が無い"
      (is (not (str/includes? url (:code-verifier pending)))))))

(deftest begin-is-pure
  (testing "同じ注入なら同じ出力 — 乱数を内部に持っていない証拠"
    (is (= (signin/begin (begin-opts)) (signin/begin (begin-opts))))))

;; ───────────────────────── redirect 照合 ─────────────────────────

(deftest redirect-verification-rejects-every-bad-shape
  (let [{:keys [pending]} (signin/begin (begin-opts))
        good (:state pending)]
    (testing "state 不一致は拒否 — host に任せると忘れた host が CSRF を通す"
      (is (= :state-mismatch (:error (signin/redirect->code {:code "c" :state "other"} pending))))
      (is (= :state-mismatch (:error (signin/redirect->code {:code "c"} pending)))))
    (testing "IdP がエラーを返したら拒否"
      (is (= :authorization-error
             (:error (signin/redirect->code {:error "access_denied" :state good} pending)))))
    (testing "code が無ければ拒否"
      (is (= :no-code (:error (signin/redirect->code {:state good} pending)))))
    (testing "正しいときだけ code を返す"
      (is (= {:ok? true :code "c"} (signin/redirect->code {:code "c" :state good} pending))))
    (testing "文字列キーのクエリでも同じ"
      (is (:ok? (signin/redirect->code {"code" "c" "state" good} pending))))))

(deftest token-request-carries-the-verifier-not-the-challenge
  (let [{:keys [pending]} (signin/begin (begin-opts))
        body (signin/token-request {:code "c" :client-id "cid" :client-secret "sec"
                                    :redirect-uri "jp.co.gftd.itonami:/cb"
                                    :code-verifier (:code-verifier pending)})]
    (is (str/includes? body "grant_type=authorization_code"))
    (is (str/includes? body "code_verifier="))))

;; ───────── 署名が検証できない ID token の claims は一切見ない ─────────

(deftest an-unverified-id-token-is-never-trusted
  (let [decoded-claims (atom nil)
        ;; 署名は常に失敗するが、claims は完璧な gftd の値
        result (signin/complete
                {:id-token "aGRy.cGF5.c2ln"
                 :json-read (fn [_] (reset! decoded-claims true)
                              {:iss "https://accounts.google.com" :aud "cid"
                               :sub "1" :email "jun@gftd.co.jp" :email_verified true
                               :hd "gftd.co.jp" :exp 9999999999 :iat 1})
                 :verify-signature-fn (constantly false)
                 :issuer "https://accounts.google.com" :audience "cid" :now 1000})]
    (is (= :denied (:status result)))
    (is (= :bad-id-token-signature (:reason result))
        "署名検証に失敗したのに claims を見て許可した")))

(deftest signature-verification-must-return-true-not-truthy
  (testing "truthy な戻り値(非 boolean)を許可扱いしない — host の実装ミスを拾う"
    (doseq [v [1 "true" :yes [] {}]]
      (is (= :bad-id-token-signature
             (:reason (signin/complete
                       {:id-token "a.b.c"
                        :json-read (fn [_] {:iss "i" :aud "a" :sub "1"
                                            :email "jun@gftd.co.jp" :email_verified true
                                            :hd "gftd.co.jp" :exp 9999999999})
                        :verify-signature-fn (constantly v)
                        :issuer "i" :audience "a" :now 1000})))
          (str (pr-str v) " が署名 OK として扱われた")))))

(deftest malformed-id-token-is-denied-not-thrown
  (doseq [t ["" "not-a-jwt" "only.two"]]
    (let [r (signin/complete {:id-token t :json-read json-read
                              :verify-signature-fn (constantly true)
                              :issuer "i" :audience "a" :now 1})]
      (is (= :denied (:status r)) (str (pr-str t) " で denied にならなかった")))))

;; ───────── 署名 OK でも claims 検証とドメイン判定を必ず通る ─────────

(defn- complete-with [claims]
  (binding [access/*tenant-id* tenant]
    (signin/complete {:id-token "a.b.c"
                      :json-read (constantly claims)
                      :verify-signature-fn (constantly true)
                      :issuer (access/tenant-issuer tenant)
                      :audience "cid" :nonce "n1" :now 1000})))

(def ^:private good-claims
  "Entra ID v2.0 の ID token claims。`hd` も `email_verified` も無い —
  Entra はどちらも出さない。組織の正本は `tid`。"
  {:iss (str "https://login.microsoftonline.com/" tenant "/v2.0")
   :aud "cid" :nonce "n1"
   :sub "pairwise-sub" :oid "oid-1" :tid tenant :acct 0
   :preferred_username "jun@gftd.co.jp" :email "jun@gftd.co.jp"
   :name "Jun Kawasaki" :exp 9999999999 :iat 1})

(deftest a-fully-valid-entra-tenant-token-is-admitted
  (let [r (complete-with good-claims)]
    (is (= :admitted (:status r)))
    (is (= "jun@gftd.co.jp" (:email r)))))

(deftest claims-validation-still-applies
  (testing "issuer 不一致"
    (is (= :invalid-id-token-claims
           (:reason (complete-with (assoc good-claims :iss "https://evil.example/v2.0"))))))
  (testing "audience 不一致 — 別クライアント向けのトークンの使い回しを拒否"
    (is (= :invalid-id-token-claims
           (:reason (complete-with (assoc good-claims :aud "other-client"))))))
  (testing "期限切れ"
    (is (= :invalid-id-token-claims
           (:reason (complete-with (assoc good-claims :exp 2))))))
  (testing "nonce 不一致 — リプレイを拒否"
    (is (= :invalid-id-token-claims
           (:reason (complete-with (assoc good-claims :nonce "other")))))))

(deftest organization-admission-still-applies-after-a-valid-token
  (testing "署名も claims も正しいが別テナント"
    (let [r (complete-with (assoc good-claims :tid "99999999-0000-0000-0000-000000000000"))]
      (is (= :denied (:status r)))))
  (testing "テナント内の B2B ゲスト"
    (let [r (complete-with (assoc good-claims :acct 1
                                  :preferred_username "outsider@example.com"
                                  :email "outsider@example.com"))]
      (is (= :denied (:status r)))
      (is (nil? (:email r)) "拒否したのに相手の identity を持っている")))
  (testing "tid claim が無い"
    (is (= :denied (:status (complete-with (dissoc good-claims :tid)))))))

(deftest oid-not-sub-becomes-the-account-identity
  (testing "sub はアプリごとに pairwise で変わるので actor id に使えない"
    (binding [access/*tenant-id* tenant]
      (let [p (access/provision
               {:identity/provider :microsoft
                :identity/email "jun@gftd.co.jp"
                :identity/claims (select-keys good-claims
                                              [:iss :aud :tid :oid :acct :preferred_username])})]
        (is (= "oid-1" (:actor p)))))))
