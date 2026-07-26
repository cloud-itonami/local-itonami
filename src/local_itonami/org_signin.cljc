(ns local-itonami.org-signin
  "組織サインインの**クライアント側の状態機械**。portable、副作用なし。

  cloud-itonami が自前で identity を提供するようになったので（ADR-2607262300）、
  このアプリのサインインは Entra ID の OIDC ではなく、cloud-itonami の
  `/api/auth/*` を通る:

    POST /api/auth/discover        アドレス -> 組織
    POST /api/auth/password        初期パスワード -> パスキー登録の引換券
    POST /api/auth/enroll-passkey  引換券 + パスキー -> DID を本人に結ぶ
    POST /api/webauthn/login       2回目以降

  ## ここが持たない判断

  **『このアドレスはサインインしてよいか』はサーバが決める。** ここで
  ドメインを検査して弾くと、同じ規則が2箇所に書かれて必ずずれる（そして
  緩い側が穴になる）。このアプリが持つのは『今どの段にいるか』だけ。

  `local-itonami.signin` / `access` / `discovery` / `onboarding`（Entra 前提）は
  これに置き換わった。詳細はそれぞれの ns docstring を参照。

  ## 初期パスワードは1回で消える

  サーバ側の契約（`cloud-itonami.account`）: 初期パスワードは引換券で、
  最初のサインインで消費され、その場でパスキーに引き換わる。UI もそう表示
  する — ここを普通の『パスワード』と書くと、利用者はブラウザに保存し、
  次回使えないことに驚く。"
  (:require [clojure.string :as str]))

(def steps
  "`:email` -> `:password` -> `:passkey` -> `:done`

  `:password` を飛ばして `:passkey` に入ることもある（既にパスキーがある
  アカウント）。段は一本道ではない。"
  [:email :password :passkey :passkey-elsewhere :done])

(defn initial []
  {:org-signin/step :email
   :org-signin/email nil
   :org-signin/org nil
   :org-signin/tenant nil
   :org-signin/domain nil
   :org-signin/enrollment-token nil
   :org-signin/did nil
   :org-signin/message nil
   :org-signin/tone nil
   :org-signin/busy? false})

(defn- msg [s m tone]
  (assoc s :org-signin/message m :org-signin/tone tone :org-signin/busy? false))

(defn busy
  "通信中。**ボタンを押せなくするのは見た目の都合ではない** — 二重送信で
  引換券が2枚出ると、片方が宙に浮いたまま TTL 切れまで残る。"
  [s] (assoc s :org-signin/busy? true :org-signin/message nil :org-signin/tone nil))

;; ───────────────────────── 1. 組織の判別 ─────────────────────────

(defn submit-email
  "アドレスを受け取る。形だけ見る — **実在も所属もサーバが答える**。"
  [s email]
  (let [e (some-> email str str/trim str/lower-case)]
    (if (or (str/blank? (str e)) (not (str/includes? (str e) "@")))
      (msg s "メールアドレスを入力してください。" :error)
      (busy (assoc s :org-signin/email e)))))

(defn discovered
  "`/api/auth/discover` の応答を畳み込む。"
  [s {:keys [ok org tenant domain message]}]
  (if-not ok
    (msg s (or message "このアドレスではサインインできません。") :error)
    (-> s
        (assoc :org-signin/org org
               :org-signin/tenant tenant
               :org-signin/domain domain
               :org-signin/step :password)
        (msg nil nil))))

;; ───────────────────────── 2. 初期パスワード ─────────────────────────

(defn password-result
  "`/api/auth/password` の応答を畳み込む。

  **成功しても `:done` にしない。** 引換券を受け取っただけで、まだ誰も
  本人だと結ばれていない。パスキーを作って初めて完了する。"
  [s {:keys [ok enrollmentToken message error]}]
  (if-not ok
    (msg s (or error "サインインできませんでした。") :error)
    (-> s
        (assoc :org-signin/enrollment-token enrollmentToken
               :org-signin/step :passkey)
        (msg (or message "パスキーを作成してください。") :ok))))

;; ───────────────────────── 3. パスキー ─────────────────────────

(defn enrolled
  "`/api/auth/enroll-passkey` の応答を畳み込む。"
  [s {:keys [ok did address org error]}]
  (if-not ok
    (msg s (or error "パスキーを登録できませんでした。") :error)
    (-> s
        (assoc :org-signin/did did
               :org-signin/email (or address (:org-signin/email s))
               :org-signin/org (or org (:org-signin/org s))
               :org-signin/step :done
               ;; 引換券は使い切った。**手元にも残さない** — 残すと再送で
               ;; 2枚目のパスキーを作れる経路が画面側に生まれる。
               :org-signin/enrollment-token nil)
        (msg nil nil))))

(defn cancelled
  "利用者が生体認証を取り消した。**失敗として扱わない。**"
  [s]
  (msg s "パスキーの作成を取り消しました。もう一度お試しください。" nil))

(defn passkey-origin-url
  "パスキー登録を完了させるためにブラウザで開く URL。"
  [s]
  (str "https://itonami.cloud/signin/"
       (when-let [e (:org-signin/email s)] (str "?email=" e))))

(defn rp-mismatch
  "**この画面ではパスキーを作れない**（WebAuthn の RP ID 制約）。

  実測 2026-07-26、`http://localhost:8781` から:

    SecurityError :: The relying party ID is not a registrable domain
    suffix of, nor equal to the current domain.

  パスキーは RP ID（`itonami.cloud`）に紐づき、**その ID を名乗れるのは
  同じドメインから配信されたページだけ**。このアプリは native では
  `kotoba-webbundle://`、開発時は `localhost` から配信されるので、
  どちらも該当しない。仕様上の制約で、JS で回避できるものではない。

  したがって WebView の中でパスキーを作るのは**構造的に不可能**で、
  正しい経路はシステムブラウザ（ASWebAuthenticationSession）で
  `https://itonami.cloud/signin/` を開くこと。

  これを『接続できませんでした』と表示していたのは誤りだった —
  原因を隠し、利用者を通信の調査に向かわせる。"
  [s]
  (-> s
      (assoc :org-signin/step :passkey-elsewhere)
      (msg "この画面ではパスキーを作成できません。ブラウザで続けてください。" nil)))

(defn failed
  "通信そのものの失敗。入力の誤りと混ぜない。"
  [s]
  (msg s "itonami.cloud に接続できませんでした。" :error))

;; ───────────────────────── 参照 ─────────────────────────

(defn signed-in? [s] (= :done (:org-signin/step s)))

(defn identity-line
  "完了時に出す1行。**DID は出さない** — 利用者にとって意味が無く、画面に
  出すと『これを控えるべき値』に見える。"
  [s]
  (when (signed-in? s)
    (str (:org-signin/email s)
         (when-let [o (:org-signin/org s)] (str " · " o)))))
