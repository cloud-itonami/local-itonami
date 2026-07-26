(ns local-itonami.discovery
  "ドメインから Entra テナントを引く。**資格情報を一切必要としない。**

  これが『domain 連動』の実体。`gftd.co.jp` という文字列だけで

    https://login.microsoftonline.com/gftd.co.jp/v2.0/.well-known/openid-configuration

  を引くと、Microsoft がそのドメインを所有するテナントの issuer を返す
  （実測 2026-07-26: `.../e9b32269-81a5-4d3a-bf94-048ba6770c99/v2.0`。
  `gftdcojp/m365-archive/bin/setup-auth.sh` が持つ GUID と一致）。

  つまり **`local-itonami.access/*tenant-id*` は設定値ではなく導出値にできる**。
  設定値のままだと『誰も選んでいないテナントを信頼した状態で出荷する』事故が
  ありえたが、ドメインから引けば入力は `allowed-domain` 1つで済む。

  ## それでも tid をそのまま信じない

  discovery 文書は **TLS で `login.microsoftonline.com` から取る**のが前提。
  この ns は HTTP を持たない（capability 注入）ので、**呼び出し側が
  https の Microsoft ホストを使ったことを保証する責任がある** — そこを
  すり替えられると任意のテナントを『我々の組織』にできてしまう。
  `discovery-url` を必ず使い、URL を組み立て直さないこと。

  さらに issuer から取り出した tid は
  `authorization_endpoint` / `token_endpoint` / `jwks_uri` の全部に同じ GUID が
  現れることを検査してから採用する（1つだけ差し替えられた文書を弾く）。"
  (:require [clojure.string :as str]))

(def login-host "https://login.microsoftonline.com")

(defn discovery-url
  "ドメインの OIDC discovery URL。**この関数以外で URL を組み立てない** —
  ホストをすり替えられると任意のテナントを我々の組織にできる。"
  [domain]
  (str login-host "/" (str/lower-case (str/trim (str domain)))
       "/v2.0/.well-known/openid-configuration"))

(def ^:private guid-re
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn- tid-of
  "文字列から Entra テナント GUID を1つ取り出す。"
  [s]
  (some-> (re-find guid-re (str s)) str/lower-case))

(defn- microsoft-url? [u]
  (str/starts-with? (str u) (str login-host "/")))

(defn parse
  "discovery 文書（すでに JSON をパースした map、キーは文字列でも keyword でも
  可）→ `{:ok? :tenant-id :issuer :authorize-endpoint :token-endpoint :jwks-uri}`。

  **一貫性検査を通らなければ `:ok? false`。** 検査するのは:

  1. issuer / authorization_endpoint / token_endpoint / jwks_uri が
     すべて `https://login.microsoftonline.com/` 配下であること
  2. その4つすべてに**同じ tid** が現れること — 1つだけ別テナントに
     差し替えられた文書を弾く
  3. issuer がその tid の v2.0 issuer と完全一致すること"
  [doc]
  (let [g (fn [k] (or (get doc k) (get doc (name k)) (get doc (keyword k))))
        issuer (g :issuer)
        authorize (g :authorization_endpoint)
        token (g :token_endpoint)
        jwks (g :jwks_uri)
        urls [issuer authorize token jwks]
        tids (map tid-of urls)
        tid (first tids)]
    (cond
      (some nil? urls)
      {:ok? false :error :incomplete-discovery-document}

      (not (every? microsoft-url? urls))
      {:ok? false :error :non-microsoft-endpoint}

      (nil? tid)
      {:ok? false :error :no-tenant-id}

      (not (apply = tids))
      {:ok? false :error :inconsistent-tenant-id}

      (not= issuer (str login-host "/" tid "/v2.0"))
      {:ok? false :error :issuer-mismatch}

      :else
      {:ok? true
       :tenant-id tid
       :issuer issuer
       :authorize-endpoint authorize
       :token-endpoint token
       :jwks-uri jwks})))

(defn resolve-domain
  "`[domain http-get-json-fn] -> Promise|value` の薄いラッパ。

  この ns は HTTP を持たないので `http-get-json` は capability
  （`local-itonami.provider/http-get-json`）。返り値の形は capability に従う
  ので、cljs では Promise、JVM では値になる — だからここでは `parse` を
  呼ぶだけの `discovery-url` を返す形にせず、呼び出し側が繋ぐ。"
  [domain]
  {:url (discovery-url domain) :parse parse})
