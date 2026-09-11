(ns postprodops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean production-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a production-operation scheduling
  request and a final-master delivery coordination request (both
  auto-commit clean at phase 3), then a content-concern flag (ALWAYS
  escalates, at any phase -- approve, then commit), then HARD-hold
  scenarios: an unregistered project, a project registered but not yet
  verified, a proposal whose own `:effect` is not `:propose`, and a
  proposal that has drifted into the permanently-excluded creative-
  final-cut-decision/content-rating-clearance-decision scope."
  (:require [langgraph.graph :as g]
            [postprodops.advisor :as advisor]
            [postprodops.store :as store]
            [postprodops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "post-production-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :post-production-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :post-production-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-production-record project-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-production-record :project-id "project-1"
                                  :patch {:edit-pass "picture rough cut 2" :vfx-shot-status "in progress"}} coordinator-phase-1)]
      (println r)
      (println "-- human post-production coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-production-record project-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-production-record :project-id "project-1"
                                  :patch {:mix-session "5.1 stem mix pass 1" :color-grade-pass 1}} coordinator-phase-3))

    (println "\n== schedule-production-operation project-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-production-operation :project-id "project-1"
                                  :patch {:stage "vfx-review" :date "2026-08-01"}} coordinator-phase-3))

    (println "\n== coordinate-delivery project-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-delivery :project-id "project-1"
                                  :patch {:format "ProRes 4444 + DCP" :delivery-date "2026-09-15"}} coordinator-phase-3))

    (println "\n== flag-content-concern project-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-content-concern :project-id "project-1"
                                 :patch {:concern "scene 42 may approach the rating-threshold for the target certificate" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human post-production coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-production-record project-99 (unregistered project -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-production-record :project-id "project-99"
                                  :patch {:edit-pass "unknown"}} coordinator-phase-3))

    (println "\n== log-production-record project-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-production-record :project-id "project-3"
                                  :patch {:edit-pass "draft"}} coordinator-phase-3))

    (println "\n== schedule-production-operation project-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-production-operation :project-id "project-1"
                                           :patch {:stage "final-mix"}} coordinator-phase-3)))

    (println "\n== log-production-record project-1, advisor drifts into final-cut/rating scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-production-record :project-id "project-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
