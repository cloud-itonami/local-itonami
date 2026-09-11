(ns local-itonami.access-test
  "組織アクセス許可の契約テスト（Entra ID / Microsoft 365）。

  一番大事なのは通るケースではなく**通ってはいけないケース**。
  各項目はどれも『これが通ったら gftd の業務データが部外者に見える』に
  直結する。"
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [local-itonami.access :as access]))

(def ^:private tenant "11111111-2222-3333-4444-555555555555")
(def ^:private other-tenant "99999999-8888-7777-6666-555555555555")

(defn- profile
  "gftd テナントの正規メンバー profile を作り、overrides で1点だけ崩す。"
  [overrides]
  (merge {:identity/provider :microsoft
          :identity/provider-subject "sub-1"
          :identity/email "jun@gftd.co.jp"
          :identity/display-name "Jun Kawasaki"
          :identity/claims {:tid tenant
                            :iss (access/tenant-issuer tenant)
                            :oid "oid-1"
                            :acct 0
                            :preferred_username "jun@gftd.co.jp"}}
         overrides))

(defn- with-claims [profile-map & kvs]
  (update profile-map :identity/claims merge (apply hash-map kvs)))

(defmacro ^:private configured [& body]
  `(binding [access/*tenant-id* tenant] ~@body))

;; ───────────────────────── 通ってよい唯一のケース ─────────────────────────

(deftest admits-a-member-of-the-configured-entra-tenant
  (configured
   (let [s (access/session (profile {}))]
     (is (= :admitted (:status s)))
     (is (= "jun@gftd.co.jp" (:email s)))
     (is (= "gftd.co.jp" (:domain s)))))

  (testing "大文字・前後空白の tid も一致する"
    (binding [access/*tenant-id* (str "  " (.toUpperCase tenant) "  ")]
      (is (true? (access/admitted? (profile {})))))))

;; ───────────── 設定漏れが素通りにならない ─────────────

(deftest nobody-is-admitted-until-the-organization-is-configured
  (testing "テナント GUID 未設定なら誰も通らない"
    (is (false? (access/admitted? (profile {}))))
    (is (= :organization-not-configured (access/denial-reason (profile {})))))
  (testing "policy の既定は deny、allow rule は1本だけ"
    (is (= :deny (:authz.policy/default-decision access/policy)))
    (is (= 1 (count (:authz.policy/rules access/policy))))))

;; ───────────── 通ってはいけないケース ─────────────

(deftest admits-only-members-of-our-tenant
  (configured
   (testing "別テナントは通さない — tid が組織の正本"
     (is (false? (access/admitted?
                  (with-claims (profile {}) :tid other-tenant
                               :iss (access/tenant-issuer other-tenant)))))
     (is (= :different-organization
            (access/denial-reason
             (with-claims (profile {}) :tid other-tenant
                          :iss (access/tenant-issuer other-tenant))))))

   (testing "tid claim が無ければ通さない"
     (is (false? (access/admitted?
                  (assoc-in (profile {}) [:identity/claims :tid] nil))))
     (is (= :no-tenant-claim
            (access/denial-reason (assoc-in (profile {}) [:identity/claims :tid] nil)))))

   (testing "tid は正しいが iss が別テナントの issuer — 使い回しを塞ぐ"
     (is (false? (access/admitted?
                  (with-claims (profile {}) :iss (access/tenant-issuer other-tenant)))))
     (is (= :issuer-tenant-mismatch
            (access/denial-reason
             (with-claims (profile {}) :iss (access/tenant-issuer other-tenant))))))

   (testing "iss が Microsoft ですらない"
     (is (false? (access/admitted?
                  (with-claims (profile {}) :iss "https://evil.example/v2.0")))))

   (testing "B2B ゲストは我々の tid を持つが通さない"
     (let [guest (with-claims (profile {:identity/email "outsider@example.com"})
                              :acct 1 :preferred_username "outsider@example.com")]
       (is (false? (access/admitted? guest)))
       (is (= :guest-account (access/denial-reason guest)))))

   (testing "acct claim が無い場合、ドメイン不一致は guest とみなす — 判定不能を
             member 扱いに倒さない"
     (let [unknown (-> (profile {:identity/email "outsider@example.com"})
                       (update :identity/claims dissoc :acct)
                       (assoc-in [:identity/claims :preferred_username] "outsider@example.com"))]
       (is (false? (access/admitted? unknown)))))

   (testing "Google の identity は通さない — 組織は Microsoft テナント"
     (is (false? (access/admitted?
                  (assoc (profile {}) :identity/provider :google)))))

   (testing "似せたドメインは通さない"
     (doseq [d ["evil-gftd.co.jp" "gftd.co.jp.evil.com" "mail.gftd.co.jp"]]
       (is (false? (access/admitted?
                    (-> (profile {:identity/email (str "x@" d)})
                        (assoc-in [:identity/claims :preferred_username] (str "x@" d)))))
           (str d " が通ってしまった"))))))

(deftest email-verified-is-not-required-because-entra-does-not-emit-it
  (testing "Entra は email_verified を既定で出さない。要求すると常に deny に
            なるので、tid + issuer + member/guest を材料にしている"
    (configured
     (is (true? (access/admitted? (profile {})))
         "email_verified の無い正規メンバーが弾かれている"))))

;; ───────────── 拒否時に何を残すか ─────────────

(deftest denied-session-holds-no-identity
  (configured
   (let [s (access/session
            (with-claims (profile {:identity/email "outsider@example.com"}) :acct 1))]
     (is (= :denied (:status s)))
     (is (nil? (:email s)))
     (is (not (re-find #"outsider" (pr-str s)))))))

(deftest denial-message-never-hints-at-what-would-pass
  (doseq [[_ msg] access/denial-message]
    (is (not (re-find #"tid|claim|issuer|policy|rule|guest\b" msg))
        (str "内部条件名が利用者向けメッセージに漏れている: " msg))))

;; ───────────── ドメイン連動のアカウント発行 ─────────────

(deftest provisions-an-account-for-an-admitted-member
  (configured
   (let [p (access/provision (profile {}))]
     (is (true? (:provision? p)))
     (is (= "gftdcojp" (:org p)))
     (is (= "gftdcojp" (:repo p)))
     (is (= "oid-1" (:actor p)) "actor id は不変の oid でなければならない")
     (is (= "Jun Kawasaki" (:actor-name p)))
     (is (= :contributor (:role p)))
     (is (= tenant (get-in p [:provenance :tenant-id]))))))

(deftest provisioned-members-cannot-approve
  (testing "サインインできることと、:financial / :external-send を承認して
            よいことは別"
    (is (not (contains? access/member-capabilities :effect/approve)))
    (is (not (contains? access/member-capabilities :admin)))
    (is (= #{:queue/read :effect/propose :audit/read} access/member-capabilities))))

(deftest never-provisions-for-a-denied-profile
  (testing "組織未設定"
    (is (false? (:provision? (access/provision (profile {}))))))
  (configured
   (testing "別テナント"
     (let [p (access/provision (with-claims (profile {}) :tid other-tenant))]
       (is (false? (:provision? p)))
       (is (= :different-organization (:reason p)))))
   (testing "ゲスト"
     (is (false? (:provision? (access/provision
                               (with-claims (profile {:identity/email "o@example.com"})
                                            :acct 1))))))
   (testing "oid が無ければ発行しない — 安定した actor id が作れない"
     (let [p (access/provision (update (profile {}) :identity/claims dissoc :oid))]
       (is (false? (:provision? p)))
       (is (= :no-stable-user-id (:reason p)))))))

(deftest actor-id-is-stable-across-a-rename
  (configured
   (testing "メールが変わっても actor は同じ"
     (is (= (:actor (access/provision (profile {})))
            (:actor (access/provision
                     (-> (profile {:identity/email "jun.kawasaki@gftd.co.jp"})
                         (assoc-in [:identity/claims :preferred_username]
                                   "jun.kawasaki@gftd.co.jp")))))))))
