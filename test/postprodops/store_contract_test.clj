(ns postprodops.store-contract-test
  "Contract tests for `postprodops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [postprodops.store :as store]))

(deftest mem-store-project-lookup
  (testing "MemStore can store and retrieve projects by ID (string keys)"
    (let [projects {"p1" {:project-id "p1" :name "Alpha" :registered? true :verified? true}}
          s (store/mem-store projects)]
      (is (some? (store/project s "p1")))
      (is (nil? (store/project s "p99"))))))

(deftest mem-store-all-projects
  (testing "MemStore returns all projects in sorted order"
    (let [projects {"p2" {:project-id "p2" :name "Bravo"}
                    "p1" {:project-id "p1" :name "Alpha"}
                    "p3" {:project-id "p3" :name "Charlie"}}
          s (store/mem-store projects)
          all-p (store/all-projects s)]
      (is (= 3 (count all-p)))
      (is (= "p1" (:project-id (first all-p))))
      (is (= "p3" (:project-id (last all-p)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-production-record :project-id "p1" :value {:edit-pass "rough cut 2"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-projects
  (testing "MemStore with-projects replaces the project directory"
    (let [s (store/mem-store {})
          new-projects {"p1" {:project-id "p1" :name "Alpha"}}]
      (is (= 0 (count (store/all-projects s))))
      (store/with-projects s new-projects)
      (is (= 1 (count (store/all-projects s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo projects"
    (let [s (store/seed-db)]
      (is (> (count (store/all-projects s)) 0))
      (is (some? (store/project s "project-1")))
      (is (some? (store/project s "project-2")))
      (is (some? (store/project s "project-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for project-id"
    (let [demo (store/demo-data)
          projects (:projects demo)]
      (doseq [[k v] projects]
        (is (string? k) "keys must be strings")
        (is (string? (:project-id v)) "project-id must be string")
        (is (= k (:project-id v)) "key must match project-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
