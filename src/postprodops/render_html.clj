(ns postprodops.render-html
  "Build-time HTML renderer for the operator console.

  Drives the REAL OperationActor (`postprodops.operation/build` -> a
  compiled langgraph-clj StateGraph) over the REAL seeded store
  (`postprodops.store/seed-db`), through the REAL PostProdGovernor
  (`postprodops.governor/check`) and the REAL rollout phase gate
  (`postprodops.phase/gate`), and renders whatever those produced.
  Nothing on the page is written by hand:

    - every project row is read back out of the store after the run
      (`store/all-projects`, `store/coordination-log`, `store/ledger`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the phase table is derived from `postprodops.phase/phases`, and
      the governor configuration table from `postprodops.governor`'s
      own public vars (`confidence-floor`, `allowed-ops`,
      `always-escalate-ops`, `scope-excluded-terms`).

  Project provenance (the demo may not invent projects): every
  `:project-id` driven below is either seeded by `store/demo-data`
  (`project-1` `project-2` `project-3`) or is the deliberately absent
  `project-99` used by this repo's own `postprodops.sim` to exercise
  the unregistered-project HARD hold. No project name, customer or
  figure is typed here -- the names shown come from
  `store/demo-data` and the summaries from `postprodops.advisor`.

  Three of the runs below use a non-default Advisor implementation
  (the `:direct-actuation` and `:low-confidence` variants defined in
  this namespace, in the same shape `postprodops.sim` uses to exercise
  the `:effect-not-propose` gate). They are real `Advisor` reifications
  driven through the real graph, and the timeline names the variant on
  every row so the page never implies the default advisor produced
  them.

  Deterministic: no clock, no randomness, no network, no reliance on
  hash-map iteration order (every set is sorted before rendering).
  Re-running writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [kotoba.lang.text :as str]
            [langgraph.graph :as g]
            [postprodops.advisor :as advisor]
            [postprodops.governor :as governor]
            [postprodops.operation :as op]
            [postprodops.phase :as phase]
            [postprodops.store :as store]))

;; ----------------------------- advisor variants -----------------------------

(defn- direct-actuation-advisor
  "An advisor that claims a DIRECT actuation instead of a proposal --
  the exact reification `postprodops.sim` uses to exercise the
  governor's `:effect-not-propose` gate end to end."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :effect :commit))))

(defn- low-confidence-advisor
  "An advisor whose proposals fall below `governor/confidence-floor`.
  The default mock advisor hard-codes confidences well above the floor,
  so the floor itself is otherwise unreachable from a demo run."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :confidence 0.42))))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :post-production-coordinator})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:phase` is the rollout phase injected into the actor context;
  `:advisor` selects which Advisor implementation the graph is built
  with (`:default` is `postprodops.advisor/mock-advisor`);
  `:approval`, when present, is the human decision handed back to the
  graph while it is paused at `:request-approval`."
  [{:tid "t01"
    :exercises "Production-record logging against a registered + verified project. Governor-clean and :log-production-record is in phase 3's :auto set -> auto-commit, no human involved."
    :phase 3
    :request {:op :log-production-record :project-id "project-1"
              :patch {:edit-pass "picture rough cut 2" :vfx-shot-status "in progress"}}}

   {:tid "t02"
    :exercises "Editing/VFX/grade/mix scheduling. Also auto-eligible at phase 3 -> auto-commit. Booking a bay is not a decision about the picture."
    :phase 3
    :request {:op :schedule-production-operation :project-id "project-1"
              :patch {:stage "vfx-review" :date "2026-08-01"}}}

   {:tid "t03"
    :exercises "Final-master delivery/handoff coordination -- format package and QC handoff dates only, never the creative sign-off or the classification of the master. Auto-eligible at phase 3."
    :phase 3
    :request {:op :coordinate-delivery :project-id "project-1"
              :patch {:format "ProRes 4444 + DCP" :delivery-date "2026-09-15"}}}

   {:tid "t04"
    :exercises "Content-concern flag. Deliberately absent from EVERY phase's :auto set and listed in the governor's own always-escalate-ops -- two independent layers agree it never auto-commits. The human coordinator approves."
    :phase 3
    :request {:op :flag-content-concern :project-id "project-1"
              :patch {:concern "scene 42 may approach the rating-threshold for the target certificate"
                      :confidence 0.92}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "A governor-clean content-concern flag the human VETOES. Distinct from a HARD hold: compliance cleared it, a person did not -- written to the ledger as :approval-rejected with basis :approver-rejected."
    :phase 3
    :request {:op :flag-content-concern :project-id "project-2"
              :patch {:concern "リール3の描写が対象区分の閾値に接近している可能性"
                      :confidence 0.88}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "The same logging op at phase 1 (assisted-logging). Writing is enabled but nothing is auto-eligible yet, so the phase gate escalates a governor-clean proposal to a human, who approves."
    :phase 1
    :request {:op :log-production-record :project-id "project-2"
              :patch {:mix-session "5.1 stem mix pass 1" :color-grade-pass 1}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Scheduling at phase 1, where that op may not write at all yet. HARD hold from the phase gate, not from a compliance violation -- the hold fact carries no :violations, only :phase-reason."
    :phase 1
    :request {:op :schedule-production-operation :project-id "project-2"
              :patch {:stage "final-mix" :date "2026-08-20"}}}

   {:tid "t08"
    :exercises "A proposal below the governor's confidence floor (advisor variant :low-confidence). The governor escalates on confidence alone; the human approves and it commits."
    :phase 3
    :advisor :low-confidence
    :request {:op :log-production-record :project-id "project-1"
              :patch {:vfx-shot-status "shot 118 comp pending"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t09"
    :exercises "A project that is not in the facility's own directory at all. The governor re-derives registration from the store, never from the request. HARD hold -- never reaches a human."
    :phase 3
    :request {:op :log-production-record :project-id "project-99"
              :patch {:edit-pass "unknown"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t10"
    :exercises "A project that IS registered but whose contract is not yet verified. Registration alone is not enough -- both flags are re-read off the store record. HARD hold."
    :phase 3
    :request {:op :coordinate-delivery :project-id "project-3"
              :patch {:format "ProRes 422 HQ" :delivery-date "2026-10-01"}}}

   {:tid "t11"
    :exercises "An advisor claiming a direct actuation rather than a proposal (advisor variant :direct-actuation, :effect :commit). Any effect other than :propose is a claim to write outside governance. HARD hold."
    :phase 3
    :advisor :direct-actuation
    :request {:op :schedule-production-operation :project-id "project-1"
              :patch {:stage "final-mix" :date "2026-09-02"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t12"
    :exercises "An advisor that has drifted into finalizing the creative final cut / issuing the content rating. Permanently outside this actor's charter, scanned across the proposal's own rationale. HARD hold, un-overridable."
    :phase 3
    :request {:op :log-production-record :project-id "project-1"
              :out-of-scope? true
              :patch {:edit-pass "picture lock candidate"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t13"
    :exercises "An op outside the closed four-op allowlist. The mock advisor has no generator for it and returns an empty proposal, so BOTH the effect allowlist and the op allowlist reject it. HARD hold."
    :phase 3
    :request {:op :finalize-final-cut :project-id "project-1"
              :patch {:decision "lock"}}}])

(defn- actor-for [db kind]
  (case kind
    :direct-actuation (op/build db {:advisor (direct-actuation-advisor)})
    :low-confidence   (op/build db {:advisor (low-confidence-advisor)})
    (op/build db)))

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actors {:keys [tid phase request approval advisor] :as scenario}]
  (let [actor (get actors (or advisor :default))
        ctx (assoc coordinator :phase phase)
        r1 (g/run* actor {:request request :context ctx} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor (one compiled graph per
  advisor variant, all bound to the SAME store), drives every scenario
  in order. Returns {:db store :runs [..]}."
  []
  (let [db (store/seed-db)
        actors {:default          (actor-for db :default)
                :direct-actuation (actor-for db :direct-actuation)
                :low-confidence   (actor-for db :low-confidence)}]
    {:db db :runs (mapv #(drive! actors %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries
  no value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"bad\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of values in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation
  order."
  [coll]
  (if (seq coll) (str/join " " (map code coll)) "—"))

(defn- kw-codes
  "Render a SET. Sorted, because a set has no order and an unsorted
  render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- kv-pairs
  "Render a map as `key value` pairs, sorted by key name -- never
  relying on map iteration order."
  [m]
  (if (seq m)
    (str/join " " (for [[k v] (sort-by (comp str key) m)]
                    (str (code k) " " (esc v))))
    "—"))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "Every `:governor-hold` fact on the append-only ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- stat [label value]
  (str "<div class=\"stat\"><span class=\"n\">" (esc value) "</span>"
       "<span class=\"l\">" (esc label) "</span></div>"))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger after "
               "driving " (count runs) " coordination requests through "
               (code "postprodops.operation/build") ".")
          (str "<div class=\"stats\">"
               (stat "requests driven" (count runs))
               (stat "ledger facts" (count led))
               (stat "commits" (n :committed))
               (stat "governor HARD holds" (n :governor-hold))
               (stat "human rejections" (n :approval-rejected))
               (stat "committed records" (count (store/coordination-log db)))
               "</div>"
               "<p class=\"muted\">Note: <code>:approval-granted</code> is emitted to the graph's "
               "in-memory <code>:audit</code> channel only — <code>postprodops.operation</code> "
               "never appends it to the store ledger, so it is not a fact this page counts. An "
               "approved request is visible as the <code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"bad\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>")
         " <span class=\"muted\">conf " (esc (:confidence verdict)) "</span>")
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"bad\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"bad\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one <code>langgraph.graph/run*</code> over the compiled actor. The "
             "governor column is the verdict map the governor itself returned; the human column "
             "is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ". A request that never paused was never offered to a "
             "person — a HARD hold does not reach one.")
        (table ["Thread" "Op" "Project" "Phase" "Advisor" "Governor" "Human" "Final"
                "What this exercises"]
               (for [{:keys [tid request phase advisor escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:project-id request))
                     (esc phase)
                     (code (or advisor :default))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is a <code>:governor-hold</code> fact on the append-only ledger. The rule "
               "name and the detail text are the governor's own " (code ":violations") " entries — "
               "this page carries no rule text of its own. A hold with no violation is a "
               "<em>phase-gate</em> hold: the governor was clean but the rollout phase does not yet "
               "let that op write, and the reason keyword is on the fact as "
               (code ":phase-reason") ".")
          (table ["Rule" "Op" "Project" "Phase" "Phase reason" "Confidence"
                  "Governor's own detail"]
                 (mapcat
                  (fn [h]
                    (if (seq (:violations h))
                      (for [v (:violations h)]
                        (tr (str "<span class=\"bad\">" (esc (:rule v)) "</span>")
                            (code (:op h)) (code (:project-id h))
                            (fmt (:phase h)) (fmt (:phase-reason h))
                            (fmt (:confidence h))
                            (esc (:detail v))))
                      [(tr "<span class=\"muted\">—</span>"
                           (code (:op h)) (code (:project-id h))
                           (fmt (:phase h))
                           (str "<span class=\"bad\">" (fmt (:phase-reason h)) "</span>")
                           (fmt (:confidence h))
                           "<span class=\"muted\">—</span>")]))
                  hs)))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Project" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:project-id r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

(defn- phase-section []
  (card (str "Rollout phase gate — default phase " phase/default-phase)
        (str "Derived from " (code "postprodops.phase/phases") ". A governor HOLD always stays a "
             "HOLD; an op that may write but is not auto-eligible escalates to a human even when "
             "the governor is clean. " (code ":flag-content-concern") " is absent from every "
             "phase's " (code ":auto") " set — a permanent structural fact, not a rollout "
             "milestone still to come.")
        (table (into ["Phase" "Label"]
                     (map #(str (name %) " — write / auto") (sort-by str governor/allowed-ops)))
               (for [p (sort (keys phase/phases))]
                 (let [{:keys [label writes auto]} (get phase/phases p)]
                   (apply tr
                          (code p)
                          (esc label)
                          (for [o (sort-by str governor/allowed-ops)]
                            (str (if (contains? writes o)
                                   "<span class=\"ok\">write</span>"
                                   "<span class=\"bad\">no</span>")
                                 " / "
                                 (if (contains? auto o)
                                   "<span class=\"ok\">auto</span>"
                                   "<span class=\"warn\">human</span>")))))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "postprodops.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops (closed allowlist)" (kw-codes governor/allowed-ops))
                (tr "always-escalate ops" (kw-codes governor/always-escalate-ops))
                (tr "scope-excluded terms"
                    (str (esc (count governor/scope-excluded-terms))
                         " phrases — every one a multi-word finalization ACTION, never a bare "
                         "noun, so ordinary post-production vocabulary (a rough "
                         "<em>cut</em>, a color <em>grade</em> pass, a "
                         "<em>rating</em>-threshold observation) never self-trips the gate"))])))

(defn- scope-terms-section []
  (card "Permanently excluded decision territory"
        (str "The literal scan list from " (code "postprodops.governor/scope-excluded-terms")
             ", in source order. A proposal whose op, summary, rationale, citations or draft "
             "value matches any of these is HARD-held regardless of confidence, regardless of "
             "how clean every other check is, and can never be approved by a human.")
        (str "<p class=\"terms\">"
             (str/join " " (map #(str "<span class=\"term\">" (esc %) "</span>")
                                governor/scope-excluded-terms))
             "</p>")))

(defn- last-fact-for [led project-id]
  (last (filter #(= project-id (:project-id %)) led)))

(defn- project-status [led project-id]
  (let [f (last-fact-for led project-id)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">last fact: committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"bad\">last fact: rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"bad\">last fact: HARD hold</span> "
           (codes (or (seq (:basis f)) (remove nil? [(:phase-reason f)]))))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- projects-section [db runs]
  (let [led (ledger-of db)
        seeded (set (map :project-id (store/all-projects db)))
        driven (distinct (map (comp :project-id :request) runs))]
    (card "Project directory"
          (str "Read back from " (code "postprodops.store/all-projects") " after the run. Both "
               (code ":registered?") " and " (code ":verified?") " must hold before ANY proposal "
               "for that project may commit or even escalate, and the governor re-reads them here "
               "rather than believing the request. Names are the store's own.")
          (str (table ["Project" "Name" "registered?" "verified?" "Ledger status"]
                      (for [p (store/all-projects db)]
                        (tr (code (:project-id p)) (esc (:name p))
                            (flag (:registered? p)) (flag (:verified? p))
                            (project-status led (:project-id p)))))
               (let [absent (remove seeded driven)]
                 (when (seq absent)
                   (str "<p class=\"muted\">Driven but absent from the directory: "
                        (codes absent)
                        " — not a row above because the store has no record of it. That absence "
                        "is exactly what the <code>:project-unverified</code> hold reports.</p>")))))))

(defn- coordination-section [db]
  (let [recs (store/coordination-log db)]
    (card "Committed coordination records"
          (str "The append-only committed-proposal history from "
               (code "postprodops.store/coordination-log") ". These are the drafts a coordinator "
               "keeps — nothing here renders a shot, dispatches a mix bay or delivers a master. "
               (code ":approved-by") " appears only on records a human actually released.")
          (if (seq recs)
            (table ["#" "Op" "Project" "Committed payload" "Approved by"]
                   (map-indexed
                    (fn [i r]
                      (tr (esc (inc i)) (code (:op r)) (code (:project-id r))
                          (kv-pairs (dissoc (:payload r) :project-id :approved-by))
                          (fmt (:approved-by (:payload r)))))
                    recs))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as " (code "postprodops.store/ledger")
             " returns it.")
        (table ["#" "Fact" "Op" "Project" "Actor" "Disposition" "Basis" "Confidence"]
               (map-indexed
                (fn [i f]
                  (tr (esc (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "bad"
                                  :approval-rejected "bad"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:project-id f)) (fmt (:actor f))
                      (fmt (:disposition f))
                      (codes (or (seq (:basis f)) (remove nil? [(:phase-reason f)])))
                      (fmt (:confidence f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(def ^:private page-css
  ;; Palette values are the デジタル庁デザインシステム (DADS) primitives
  ;; already vendored into this repo's docs/index.html, inlined here so
  ;; the generated console is a single self-contained file with no
  ;; network fetch at build time or view time.
  (str "*{box-sizing:border-box}"
       "body{margin:0;font:14px/1.6 -apple-system,BlinkMacSystemFont,'Helvetica Neue','Noto Sans JP',sans-serif;"
       "color:#1a1a1a;background:#f2f2f2}"
       ".bar{background:#00118f;color:#fff;padding:1.4rem 1.6rem}"
       ".bar h1{margin:0 0 .3rem;font-size:1.15rem;font-weight:700}"
       ".bar p{margin:0;font-size:.82rem;opacity:.85}"
       ".chip{display:inline-block;background:#fff;color:#00118f;border-radius:999px;"
       "padding:.1rem .6rem;font-size:.74rem;font-weight:700;margin-right:.4rem}"
       "main{max-width:1180px;margin:1.4rem auto 3rem;padding:0 1rem}"
       ".card{background:#fff;border:1px solid #e6e6e6;border-radius:8px;padding:1.1rem 1.3rem;"
       "margin-bottom:1.1rem}"
       ".card h2{margin:0 0 .4rem;font-size:1rem;font-weight:700}"
       ".muted{color:#767676;font-size:.82rem;margin:.2rem 0 .7rem}"
       "table{border-collapse:collapse;width:100%;font-size:.81rem}"
       "th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #e6e6e6;"
       "vertical-align:top}"
       "th{font-weight:700;color:#767676;font-size:.76rem;white-space:nowrap}"
       "tbody tr:last-child td{border-bottom:none}"
       "code{background:#f2f2f2;border-radius:3px;padding:.05rem .28rem;font-size:.76rem;"
       "font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
       ".ok{color:#115a36;font-weight:600}"
       ".warn{color:#8b3200;font-weight:600}"
       ".bad{color:#a90000;font-weight:700}"
       ".stats{display:flex;flex-wrap:wrap;gap:.7rem}"
       ".stat{border:1px solid #e6e6e6;border-radius:6px;padding:.6rem .9rem;min-width:8.5rem}"
       ".stat .n{display:block;font-size:1.5rem;font-weight:700;line-height:1.1}"
       ".stat .l{display:block;font-size:.74rem;color:#767676}"
       ".terms{margin:.2rem 0 0}"
       ".term{display:inline-block;background:#fdf2f2;color:#a90000;border:1px solid #f3d3d3;"
       "border-radius:4px;padding:.08rem .4rem;margin:0 .3rem .35rem 0;font-size:.76rem}"
       "footer{max-width:1180px;margin:0 auto 2.5rem;padding:0 1rem;color:#767676;font-size:.78rem}"))

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-5912 (postprodops)</title>"
       "<style>" page-css "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Motion picture, video &amp; television programme post-production — operator console</h1>"
       "<p><span class=\"chip\">ISIC 5912</span>"
       "<span class=\"chip\">postprodops</span>"
       "governor <code style=\"background:rgba(255,255,255,.15);color:#fff\">"
       "post-production-operations-governor</code> · actor "
       (esc (:actor-id coordinator)) " · role " (esc (:actor-role coordinator))
       " · coordination only — never a final-cut or content-rating decision"
       "</p></header>\n<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (scope-terms-section)
                          (projects-section db runs)
                          (coordination-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>postprodops.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>postprodops.operation</code> actor graph over the real "
       "<code>postprodops.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
