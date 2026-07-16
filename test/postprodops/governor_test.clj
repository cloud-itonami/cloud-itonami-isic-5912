(ns postprodops.governor-test
  "Pure unit tests of `postprodops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [postprodops.governor :as gov]
            [postprodops.store :as store]))

(def project-1 {:project-id "project-1" :name "Harbor Lights" :registered? true :verified? true})
(def project-3 {:project-id "project-3" :name "River Echoes" :registered? true :verified? false})

(defn- clean-proposal [op project-id]
  {:op op :project-id project-id :summary "s" :rationale "routine post-production coordination"
   :cites [project-id] :effect :propose :value {} :confidence 0.85})

(deftest project-unregistered-is-hard
  (testing "no project record at all -> HARD hold"
    (let [s (store/mem-store {"project-1" project-1})
          verdict (gov/check {} nil (clean-proposal :log-production-record "unknown-project") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:project-unverified} (map :rule (:violations verdict)))))))

(deftest project-unverified-is-hard
  (testing "project registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"project-3" project-3})
          verdict (gov/check {} nil (clean-proposal :log-production-record "project-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:project-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"project-1" project-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-production-operation "project-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"project-1" project-1})
          verdict (gov/check {} nil (clean-proposal :finalize-cut "project-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest final-cut-decision-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches finalizing the creative final-cut decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"project-1" project-1})
          poisoned (assoc (clean-proposal :log-production-record "project-1")
                          :rationale "finalized the final cut decision on reel 4's ending"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest content-rating-decision-content-is-hard
  (testing "a proposal touching a content-rating/clearance decision is HARD-blocked, same as final-cut"
    (let [s (store/mem-store {"project-1" project-1})
          poisoned (assoc (clean-proposal :log-production-record "project-1")
                          :rationale "issued a content rating decision for the theatrical release"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest picture-lock-decision-content-is-hard
  (testing "a proposal touching a picture-lock decision embedded in the draft value is HARD-blocked"
    (let [s (store/mem-store {"project-1" project-1})
          poisoned (assoc (clean-proposal :schedule-production-operation "project-1")
                          :value {:decision "picture lock decision approved for reel 6"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest rating-clearance-decision-content-is-hard
  (testing "a proposal touching a rating-clearance decision is HARD-blocked"
    (let [s (store/mem-store {"project-1" project-1})
          poisoned (assoc (clean-proposal :coordinate-delivery "project-1")
                          :summary "issued a rating clearance decision to the distributor")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-content-concern-is-not-scope-excluded
  (testing "flagging an observed rating-threshold/sensitive-scene concern (not a clearance decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"project-1" project-1})
          concern (assoc (clean-proposal :flag-content-concern "project-1")
                         :value {:concern "scene 42 depicts intense violence that may approach the rating threshold for the target certificate; flagging for human classification review"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (rating-threshold/sensitive-scene risk) is exactly what this op exists to surface"))))

(deftest legitimate-delivery-coordination-is-not-scope-excluded
  (testing "a clean delivery-coordination proposal that merely mentions it does not finalize the cut or the rating never trips scope-exclusion"
    (let [s (store/mem-store {"project-1" project-1})
          clean (assoc (clean-proposal :coordinate-delivery "project-1")
                       :rationale "adjusts only the delivery-package handoff schedule; does not decide the edit or the classification")
          verdict (gov/check {} nil clean s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "scheduling language must not accidentally self-trip the final-cut/rating-clearance block"))))

(deftest legitimate-production-record-mentioning-rough-cut-is-not-scope-excluded
  (testing "logging a routine editing cut (rough cut, scene cut) never trips scope-exclusion -- ordinary post-production vocabulary must not self-block"
    (let [s (store/mem-store {"project-1" project-1})
          clean (assoc (clean-proposal :log-production-record "project-1")
                       :value {:edit-pass "rough cut 3, scene cut tightened in reel 2"})
          verdict (gov/check {} nil clean s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "the bare word \"cut\" (rough cut, scene cut) is ordinary editing vocabulary, not a final-cut decision"))))
