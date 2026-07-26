(ns local-itonami.access-test
  "ドメイン制限の契約テスト。

  ここで一番大事なのは**通るケースではなく、通ってはいけないケース**。
  `admits-only-verified-gftd-workspace-identities` の各項目はどれも
  『これが通ったら gftd の業務データが部外者に見える』に直結する。"
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [local-itonami.access :as access]))

(defn- profile
  "Google Workspace 経路の正規 profile を作り、overrides で1点だけ崩す。"
  [overrides]
  (merge {:identity/provider :google
          :identity/provider-subject "sub-1"
          :identity/email "jun@gftd.co.jp"
          :identity/email-verified? true
          :identity/display-name "Jun Kawasaki"
          :identity/claims {:hd "gftd.co.jp"}}
         overrides))

;; ───────────────── 通ってよい唯一のケース ─────────────────

(deftest admits-a-verified-gftd-workspace-identity
  (let [s (access/session (profile {}))]
    (is (= :admitted (:status s)))
    (is (= "jun@gftd.co.jp" (:email s)))
    (is (= "gftd.co.jp" (:domain s))))
  (testing "大文字・前後空白は正規化される"
    (is (true? (access/admitted?
                (profile {:identity/email "  Jun@GFTD.co.JP  "
                          :identity/claims {:hd "GFTD.co.jp"}}))))))

;; ───────────────── 通ってはいけないケース ─────────────────

(deftest admits-only-verified-gftd-workspace-identities
  (testing "未検証メールは通さない — 自己申告のアドレスは証拠ではない"
    (is (false? (access/admitted? (profile {:identity/email-verified? false}))))
    (is (= :email-not-verified (access/denial-reason (profile {:identity/email-verified? false})))))

  (testing "email-verified? が欠落/nil でも通さない(true? で判定している)"
    (is (false? (access/admitted? (profile {:identity/email-verified? nil}))))
    (is (false? (access/admitted? (dissoc (profile {}) :identity/email-verified?)))))

  (testing "hd claim が無ければ通さない — email だけ騙れば入れる穴を塞ぐ"
    (is (false? (access/admitted? (profile {:identity/claims {}}))))
    (is (= :no-hosted-domain-claim
           (access/denial-reason (profile {:identity/claims {}})))))

  (testing "hd と email の domain が食い違えば通さない"
    (is (false? (access/admitted? (profile {:identity/claims {:hd "example.com"}}))))
    (is (= :hosted-domain-mismatch
           (access/denial-reason (profile {:identity/claims {:hd "example.com"}})))))

  (testing "他ドメインは通さない"
    (doseq [e ["someone@gmail.com" "a@example.com" "b@gftd.group"]]
      (is (false? (access/admitted?
                   (profile {:identity/email e :identity/claims {:hd "gmail.com"}})))
          (str e " が通ってしまった"))))

  (testing "似せたドメインは通さない — 部分一致/接尾辞一致は典型的な迂回路"
    (doseq [d ["evil-gftd.co.jp" "gftd.co.jp.evil.com" "xgftd.co.jp"
               "gftd.co.jp2" "notgftd.co.jp"]]
      (is (false? (access/admitted?
                   (profile {:identity/email (str "x@" d)
                             :identity/claims {:hd d}})))
          (str d " が通ってしまった"))))

  (testing "サブドメインも通さない — 許可は完全一致のみ"
    (doseq [d ["mail.gftd.co.jp" "sub.gftd.co.jp"]]
      (is (false? (access/admitted?
                   (profile {:identity/email (str "x@" d)
                             :identity/claims {:hd d}})))
          (str d " が通ってしまった"))))

  (testing "メールが無い/壊れている経路は通さない"
    (doseq [e [nil "" "not-an-email" "@gftd.co.jp" "jun@" "jun@@gftd.co.jp"]]
      (is (false? (access/admitted?
                   (profile {:identity/email e})))
          (str (pr-str e) " が通ってしまった")))))

(deftest deny-by-default
  (testing "profile が空でも例外にならず deny になる"
    (is (false? (access/admitted? {})))
    (is (= :denied (:status (access/session {})))))
  (testing "policy の既定は deny"
    (is (= :deny (:authz.policy/default-decision access/policy))))
  (testing "allow rule は1本だけ — 増えていたら意図的な変更か確認する"
    (is (= 1 (count (:authz.policy/rules access/policy))))))

;; ───────────────── 拒否時に何を残すか ─────────────────

(deftest denied-session-holds-no-identity
  (testing "拒否した相手の email や claim を保持しない"
    (let [s (access/session (profile {:identity/email "someone@gmail.com"
                                      :identity/claims {:hd "gmail.com"}}))]
      (is (= :denied (:status s)))
      (is (nil? (:email s)))
      (is (nil? (:display-name s)))
      (is (not (contains? s :identity/claims)))
      (is (not (re-find #"gmail" (pr-str s)))
          "拒否理由の表示に相手のアドレスが混ざっている"))))

(deftest denial-message-never-hints-at-what-would-pass
  (testing "メッセージは次の行動を示すが、通過条件の総当たり手がかりは出さない"
    (doseq [[_ msg] access/denial-message]
      (is (not (re-find #"hd|claim|verified|policy|rule" msg))
          (str "内部条件名が利用者向けメッセージに漏れている: " msg)))))

;; ───────────────── 監査に載る形か ─────────────────

(deftest decision-is-a-well-formed-authorization-decision
  (let [d (access/decide (profile {}))]
    (is (= :allow (:authz.decision/decision d)))
    (is (= "local-itonami.access" (:authz.decision/by d)))
    (is (= "local-itonami/domain-admission" (:authz.decision/policy-ref d)))
    (is (some? (:authz.decision/effect-trace d))))

  (testing "deny には必ず reason がある(authorization.core が強制する)"
    (let [d (access/decide (profile {:identity/email "x@example.com"}))]
      (is (= :deny (:authz.decision/decision d)))
      (is (some? (:authz.decision/reason d)))))

  (testing "request-id は決定的 — 同じ principal なら同じ id"
    (is (= (:authz.decision/request-id (access/decide (profile {})))
           (:authz.decision/request-id (access/decide (profile {}))))))

  (testing "呼び出し側が相関 id を渡せる"
    (is (= "corr-1" (:authz.decision/request-id
                     (access/decide (profile {}) "corr-1"))))))
