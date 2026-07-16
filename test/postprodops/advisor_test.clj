(ns postprodops.advisor-test
  "Unit tests of `postprodops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [postprodops.advisor :as adv]
            [postprodops.governor :as gov]
            [postprodops.store :as store]))

(def db (store/seed-db))

(deftest propose-production-record-shape
  (testing "production-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-production-record
                           :project-id "project-1"
                           :patch {:edit-pass "rough cut 2" :vfx-shot-status "in progress"}})]
      (is (= :log-production-record (:op p)))
      (is (= "project-1" (:project-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :project-id)))))

(deftest propose-production-schedule-shape
  (testing "production-operation scheduling proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-production-operation
                           :project-id "project-2"
                           :patch {:stage "color-grading" :date "2026-08-01"}})]
      (is (= :schedule-production-operation (:op p)))
      (is (= "project-2" (:project-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-delivery-coordination-shape
  (testing "delivery-coordination proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-delivery
                           :project-id "project-1"
                           :patch {:format "ProRes 4444" :delivery-date "2026-09-15"}})]
      (is (= :coordinate-delivery (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-content-concern-shape
  (testing "content-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-content-concern
                           :project-id "project-1"
                           :patch {:concern "possible rating-threshold issue in reel 3"}})]
      (is (= :flag-content-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-delivery :flag-content-concern]]
      (let [p (adv/infer db {:op op :project-id "project-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-delivery :flag-content-concern]]
      (let [p (adv/infer db {:op op :project-id "project-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every op's default (clean, in-scope) mock-advisor proposal must clear the governor's scope-exclusion check on its own generated text -- a proposal must never accidentally describe itself using the very terms that would make it permanently blocked. This is an especially acute risk in this domain because ordinary post-production vocabulary (\"cut\", \"rating\", \"grade\", \"clearance\") overlaps heavily with the excluded decision area's own vocabulary -- see `postprodops.governor`'s own phrasing-discipline note."
    (doseq [op [:log-production-record :schedule-production-operation
                :coordinate-delivery :flag-content-concern]]
      (let [p (adv/infer db {:op op :project-id "project-1"
                             :patch {:concern "possible rating-threshold issue noted" :edit-pass "rough cut 2"}})
            s (store/mem-store {"project-1" {:project-id "project-1" :name "Harbor Lights"
                                              :registered? true :verified? true}})
            verdict (gov/check {:project-id "project-1"} nil p s)]
        (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
            (str "op " op "'s own default proposal text must not self-trip scope-exclusion"))))))

(deftest out-of-scope-hook-trips-scope-exclusion
  (testing "the test-only :out-of-scope? hook produces text the governor correctly HARD-blocks"
    (let [p (adv/infer db {:op :log-production-record :project-id "project-1"
                           :out-of-scope? true :patch {}})
          s (store/mem-store {"project-1" {:project-id "project-1" :name "Harbor Lights"
                                            :registered? true :verified? true}})
          verdict (gov/check {:project-id "project-1"} nil p s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))
