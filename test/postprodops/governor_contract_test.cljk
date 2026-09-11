(ns postprodops.governor-contract-test
  "Integration tests: full OperationActor graph exercising the governor's
  hard checks, escalation logic, and audit trail."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [postprodops.advisor :as advisor]
            [postprodops.store :as store]
            [postprodops.operation :as op]))

(defn exec-request [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn resume-approval [actor tid status]
  (g/run* actor {:approval {:status status :by "coordinator"}} {:thread-id tid :resume? true}))

(deftest production-record-logging-full-flow
  (testing "clean production-record proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1" :phase 3}
          result (exec-request actor "t1"
                               {:op :log-production-record :project-id "project-1" :patch {:edit-pass "rough cut 2" :vfx-shot-status "final"}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/ledger db)) 0)
          "commit must append audit facts to ledger")
      (is (> (count (store/coordination-log db)) 0)
          "commit must append record to coordination-log"))))

(deftest schedule-production-operation-full-flow
  (testing "clean production-operation scheduling proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1b" :phase 3}
          result (exec-request actor "t1b"
                               {:op :schedule-production-operation :project-id "project-1" :patch {:stage "color-grading" :date "2026-08-01"}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/coordination-log db)) 0)
          "commit must append record to coordination-log"))))

(deftest coordinate-delivery-full-flow
  (testing "clean delivery-coordination proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1c" :phase 3}
          result (exec-request actor "t1c"
                               {:op :coordinate-delivery :project-id "project-1" :patch {:format "ProRes 4444" :delivery-date "2026-09-15"}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/coordination-log db)) 0)
          "commit must append record to coordination-log"))))

(deftest content-concern-always-escalates
  (testing ":flag-content-concern escalates for human approval, regardless of phase/confidence"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2" :phase 3}
          result (exec-request actor "t2"
                               {:op :flag-content-concern :project-id "project-1"
                                :patch {:concern "possible rating-threshold issue" :confidence 0.99}}
                               ctx)]
      (is (some? result))
      ;; At this point the actor is paused for approval, not yet committed
      (is (= 0 (count (store/coordination-log db)))
          "content concern must not auto-commit, must wait for approval")
      ;; Now approve it
      (resume-approval actor "t2" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, record must be committed"))))

(deftest unregistered-project-hard-hold
  (testing "unregistered project -> permanent HARD hold, never escalates"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-3" :phase 3}]
      (exec-request actor "t3"
                     {:op :log-production-record :project-id "unknown-project"
                      :patch {:edit-pass "draft"}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "HARD hold must never commit"))))

(deftest unverified-project-hard-hold
  (testing "registered but unverified project -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-4" :phase 3}
          result (exec-request actor "t4"
                               {:op :log-production-record :project-id "project-3"
                                :patch {:edit-pass "draft"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "unverified project must HARD hold"))))

(deftest effect-not-propose-hard-hold
  (testing "proposal with :effect :commit (not :propose) -> hard hold"
    (let [db (store/seed-db)
          bad-advisor (reify advisor/Advisor
                        (-advise [_ _ req]
                          (assoc (advisor/infer nil req) :effect :commit)))
          actor (op/build db {:advisor bad-advisor})
          ctx {:actor-id "test-5" :phase 3}
          result (exec-request actor "t5"
                               {:op :log-production-record :project-id "project-1"
                                :patch {:edit-pass "rough cut 2"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "non-:propose effect must HARD hold"))))

(deftest scope-excluded-content-hard-hold
  (testing "proposal drifting into final-cut/rating-clearance scope -> permanent hard hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-6" :phase 3}
          result (exec-request actor "t6"
                               {:op :log-production-record :project-id "project-1"
                                :out-of-scope? true  ; triggers scope pollution in advisor
                                :patch {}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "scope-excluded content must HARD hold"))))

(deftest phase-1-approval-gate
  (testing "phase 1 approved request -> commits after human approval"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-7" :phase 1}]
      (exec-request actor "t7"
                     {:op :log-production-record :project-id "project-1"
                      :patch {:edit-pass "rough cut 2"}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "phase 1 must not auto-commit, requires approval")
      (resume-approval actor "t7" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, must commit")
      (is (some #(= :committed (:t %)) (store/ledger db))
          "committed fact must be logged after approval"))))

(deftest audit-trail-completeness
  (testing "every decision leaves immutable audit facts"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-8" :phase 3}]
      (exec-request actor "t8a"
                     {:op :log-production-record :project-id "project-1" :patch {:edit-pass "rough cut 2"}}
                     ctx)
      (exec-request actor "t8b"
                     {:op :log-production-record :project-id "unknown" :patch {:edit-pass "rough cut 2"}}
                     ctx)
      (let [ledger (store/ledger db)]
        (is (> (count ledger) 0))
        (is (some #(= :committed (:t %)) ledger)
            "successful commits must be logged")
        (is (some #(= :governor-hold (:t %)) ledger)
            "HARD holds must be logged")))))
