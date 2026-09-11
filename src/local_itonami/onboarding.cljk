(ns local-itonami.onboarding
  "ワンクリック登録の**計画**を作る。ドメイン1つから、必要な手続きを

  ⚠ **SUPERSEDED（2026-07-26、ADR-2607262300）**: cloud-itonami が自前で
  identity を提供するようになったため、この ns は live な経路から外れた。
  現行のサインインは `local-itonami.org-signin` +
  `cloud-itonami.edge.auth-endpoints`（`/api/auth/*`）。ここに残して
  あるのは実測で得た知見（Entra の tid/issuer 判定、ドメインからの
  テナント discovery、ワンクリック登録で実際に必要だった手続き）で、
  Entra 連携を再開する日が来たときの出発点になる。**新しいコードから
  この ns を require しない。**

  すべて data として出す。

  ## 実測してわかった、実際に必要な手続き

  `gftd.co.jp` について実測した結果（2026-07-26）:

  | 手続き | 必要か | 理由 |
  |---|---|---|
  | テナント特定 | **自動** | ドメインの OIDC discovery だけで確定。資格情報も DNS も不要 |
  | GoDaddy の DNS 変更 | **不要** | ドメインは既にテナントで検証済み（M365 が同ドメインで稼働中）。OIDC アプリ登録に DNS レコードは要らない |
  | Entra アプリ登録 | **要る** | client id と redirect URI。Graph の管理者権限が要る唯一の段 |
  | cloud-itonami テナント登録 | 自動 | `access/provision` がサインイン時に発行する |

  **GoDaddy の DNS を触る必要はありません。** レジストラが GoDaddy なのは
  事実だが、この経路で DNS レコードを足す理由が無い — ドメイン検証は M365
  導入時に済んでおり、native アプリの redirect は custom scheme
  (`jp.co.gftd.itonami:/oauth2redirect`) なので Web ドメインを要さない。
  `kotoba-lang/godaddy-dns` は在るが、ここでは呼ばない。**要らない変更を
  『念のため』で本番 DNS に入れない** — MX が載っているゾーンなので、
  不要な書き込みは実害の可能性だけを増やす。

  ## 『ワンクリック』が実際に何段になるか

  ドメインを入れて押す → 1段目（discovery）は完全自動で終わる。2段目
  （アプリ登録）は **Entra の管理者同意が要る**ので、押した人が管理者なら
  そのまま完了し、そうでなければ管理者に渡す `plan` が出る。

  ここを『1クリックで全部終わる』と書かないのは、**テナントに新しいアプリを
  登録する権限は、本来ワンクリックで委譲されるべきものではない**から。
  隠すのではなく、何が要るかを出す。"
  (:require [kotoba.lang.text :as str]
            [local-itonami.access :as access]
            [local-itonami.discovery :as discovery]))

(def redirect-uri
  "native アプリの redirect。custom scheme なので Web ドメインも DNS も
  要らない（`app.kotoba.edn` の `:macos/oauth-callback-scheme` と一致）。"
  "jp.co.gftd.itonami:/oauth2redirect")

(def app-display-name "itonami (local client)")

(defn entra-app-registration
  "Entra アプリ登録リクエスト（Microsoft Graph `POST /applications` の body）。

  `signInAudience` は **`AzureADMyOrg`** — 単一テナント。`AzureADMultipleOrgs`
  にすると任意のテナントのユーザーがサインイン画面を通れてしまい、拒否が
  ID token 検証まで遅れる（`access` は tid で弾くので破綻はしないが、
  そもそも入口を広げる理由が無い）。

  `publicClient` を使い client secret を持たない — native アプリに secret を
  同梱しても秘密にならないので、PKCE で代替する（既に実装済み）。"
  [{:keys [tenant-id]}]
  {:method :post
   :url "https://graph.microsoft.com/v1.0/applications"
   :requires-admin-consent? true
   :scopes ["Application.ReadWrite.All"]
   :tenant-id tenant-id
   :body {:displayName app-display-name
          :signInAudience "AzureADMyOrg"
          :publicClient {:redirectUris [redirect-uri]}
          :isFallbackPublicClient true
          :requiredResourceAccess
          [{:resourceAppId "00000003-0000-0000-c000-000000000000" ; Microsoft Graph
            :resourceAccess
            ;; openid / profile / email / User.Read だけ。delegated のみで、
            ;; アプリ権限(application permission)は求めない — このクライアントが
            ;; 必要なのは『誰がサインインしたか』だけで、テナントのデータを
            ;; 読む権限は要らない。
            [{:id "37f7f235-527c-4136-accd-4a02d197296e" :type "Scope"}  ; openid
             {:id "14dad69e-099b-42c9-810b-d002981feec1" :type "Scope"}  ; profile
             {:id "64a6cdd6-aab1-4aaf-94b8-3cc8405e90d0" :type "Scope"}  ; email
             {:id "e1fe6dd8-ba31-4d61-89e7-88639da4683d" :type "Scope"}]}]} ; User.Read
   ;; オプション claim。acct が無いと guest 判定がドメイン一致に頼るしかない
   ;; ので、登録時に明示的に足す（access/guest? の docstring 参照）。
   :optional-claims {:idToken [{:name "acct" :essential false}
                               {:name "email" :essential false}
                               {:name "xms_pdl" :essential false}]}})

(def cli-equivalent
  "同じことを Azure CLI でやる場合。`m365-archive/bin/setup-auth.sh` が既に
  `az ad app create` を使っており、そこに tenant GUID も入っている —
  つまり**この手続きの前例はこのワークスペースに既にある**。"
  (str "az ad app create --display-name '" app-display-name "'"
       " --sign-in-audience AzureADMyOrg"
       " --is-fallback-public-client true"
       " --public-client-redirect-uris '" redirect-uri "'"))

(defn steps
  "ドメイン → 登録手順。`discovery` は解決済みの結果を渡す
  （この ns は HTTP を持たない）。

  返す `:steps` は順序どおりで、各段に `:automatic?` が付く。
  自動でない段を『自動』と表示しないための区別。"
  [{:keys [domain discovery-result]}]
  (let [d (or discovery-result {})
        resolved? (true? (:ok? d))]
    {:domain domain
     :resolved? resolved?
     :tenant-id (:tenant-id d)
     :steps
     [{:id :resolve-tenant
       :label "組織テナントの特定"
       :automatic? true
       :done? resolved?
       :detail (if resolved?
                 (str "テナント " (:tenant-id d) " を特定しました。")
                 "ドメインからテナントを特定できませんでした。")
       :evidence (select-keys d [:issuer :authorize-endpoint :token-endpoint :jwks-uri])}

      {:id :dns
       :label "DNS レコード"
       :automatic? true
       :done? true
       :detail (str "変更は不要です。" domain " は既にテナントで検証済みで、"
                    "native アプリの redirect は custom scheme のため "
                    "GoDaddy 側の変更は要りません。")}

      {:id :register-app
       :label "Entra アプリ登録"
       :automatic? false
       :done? false
       :requires "Entra の管理者権限（Application.ReadWrite.All）"
       :detail "テナントに新しいアプリを登録します。管理者同意が要ります。"
       :request (when resolved? (entra-app-registration d))
       :cli cli-equivalent}

      {:id :provision-account
       :label "cloud-itonami アカウント"
       :automatic? true
       :done? false
       :detail (str "サインイン時に自動発行されます（"
                    (:org access/cloud-itonami-org) "/"
                    (:repo access/cloud-itonami-org) " のメンバーとして）。")}]}))

(defn next-action
  "まだ終わっていない最初の段。UI が『次に何をすればいいか』を1つだけ
  出せるようにする。"
  [plan]
  (first (remove :done? (:steps plan))))

(defn one-click?
  "残りが自動の段だけなら true。**管理者権限が要る段が残っていれば false** —
  ワンクリックで終わらないものを終わるように見せない。"
  [plan]
  (every? #(or (:done? %) (:automatic? %)) (:steps plan)))

(defn discovery-request
  "1段目に必要な HTTP リクエスト。host の `http-get-json` に渡す。"
  [domain]
  (discovery/resolve-domain domain))

(defn domain-of-record
  "この配布物が登録しようとしているドメイン。`access/allowed-domain` と
  必ず同じ — 別々に設定できると『A のテナントを引いて B を許可する』が
  作れてしまう。"
  []
  access/allowed-domain)

(defn plan-for-this-app
  "このアプリの登録計画。ドメインは `access/allowed-domain` 固定。"
  [discovery-result]
  (steps {:domain (domain-of-record) :discovery-result discovery-result}))
