(ns postprodops.store
  "SSoT for the ISIC-5912 motion-picture/video/television post-production
  COORDINATION actor, behind a `Store` protocol so the backend is a swap,
  not a rewrite -- the same seam every `cloud-itonami-isic-*` actor in
  this fleet uses.

  This actor coordinates the back-office operations of a post-production
  facility: editing/VFX-shot/sound-mix-session production-record logging,
  editing/VFX/color-grading/mix scheduling proposals, final-master
  delivery/handoff coordination, and content-concern flagging
  (rating-classification threshold or sensitive-scene-edit concerns
  raised by an editor, colorist, or the advisor). It never touches
  finalizing a creative final-cut decision (what the picture actually
  is, whether it locks as cut) or a content-rating/clearance decision
  (which classification/certificate the finished programme receives) --
  see `postprodops.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `projects` directory keyed by `:project-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified project record (the post-production facility's
  own record of an edit/VFX/mix/grade project under contract) must exist
  before ANY proposal for that project may ever commit or escalate --
  `postprodops.governor`'s `project-unverified-violations` re-derives
  this from the project's own `:registered?`/`:verified?` fields, never
  from proposal self-report, the SAME 'ground truth, not self-report'
  discipline every sibling actor's own governor uses.

  The ledger stays append-only: which project a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (project [s project-id] "Registered post-production project record, or nil.
    Project map: {:project-id .. :name .. :registered? bool :verified? bool}.")
  (all-projects [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-projects [s projects] "replace/seed the project directory (map project-id->project)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained project directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:projects
   {"project-1" {:project-id "project-1" :name "Season 3 Episode 4 \"Harbor Lights\" (post contract signed, verified)"
                  :registered? true :verified? true}
    "project-2" {:project-id "project-2" :name "Feature Film \"Northbound\" (post contract signed, verified)"
                  :registered? true :verified? true}
    "project-3" {:project-id "project-3" :name "Documentary \"River Echoes\" (contract pending verification)"
                  :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (project [_ project-id] (get-in @a [:projects project-id]))
  (all-projects [_] (sort-by :project-id (vals (:projects @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-projects [s projects] (when (seq projects) (swap! a assoc :projects projects)) s))

(defn seed-db
  "A MemStore seeded with the demo project directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `projects` map (project-id string ->
  project map) -- the primary test/dev entry point. `projects` may be empty
  (an unregistered-everywhere store)."
  [projects]
  (->MemStore (atom {:projects (or projects {}) :ledger [] :coordination-log []})))
