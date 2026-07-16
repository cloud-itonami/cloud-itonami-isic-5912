(ns postprodops.governor
  "PostProdGovernor -- the independent compliance layer that earns
  the PostProdAdvisor the right to commit. The advisor has no notion
  of whether a project is actually registered and verified under
  contract, whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently
  drifted into a permanently out-of-scope decision area, so this MUST be
  a separate system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (edit-pass/VFX-shot/mix-session production-record logging,
  editing/VFX/color-grading/mix scheduling, content-concern flagging,
  final-master delivery/handoff coordination). It NEVER performs or
  authorizes:
    - finalizing a creative final-cut decision (what the finished
      picture actually is, whether it locks as cut)
    - finalizing a content-rating/clearance decision (which
      classification/certificate the finished programme receives)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Project unverified       -- the target project record (the
                                   post-production facility's own
                                   record of an edit/VFX/mix/grade
                                   project under contract) must exist
                                   AND be independently confirmed
                                   `:registered?`/`:verified?` in the
                                   store before ANY proposal for it may
                                   commit or even escalate. Never trusts
                                   a proposal's own claim about the
                                   project -- re-derived from the
                                   project's own store record, the same
                                   'ground truth, not self-report'
                                   discipline every sibling actor's
                                   governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST
                                   be `:propose`. Any other effect
                                   value is, by construction, a
                                   claim to directly actuate/commit
                                   outside governance -- HARD block,
                                   not merely low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or draft value touches
                                   finalizing-a-creative-final-cut-
                                   decision / finalizing-a-content-
                                   rating-clearance-decision territory
                                   is a HARD, PERMANENT block -- this
                                   actor's charter excludes that
                                   territory structurally, not as a
                                   rollout milestone. Evaluated
                                   UNCONDITIONALLY on every
                                   proposal. An op outside the
                                   closed four-op allowlist is the
                                   SAME failure mode (an advisor
                                   proposing something it was never
                                   authorized to propose) and is
                                   folded into this same check.

  NOTE (scope-exclusion-term phrasing discipline -- ADR-2607152500 /
  fleet-wide self-tripping caution, especially acute in this domain):
  post-production vocabulary is FULL of the bare noun \"cut\" (rough
  cut, editing cut, next cut, scene cut) and the bare noun \"rating\"/
  \"grade\"/\"clearance\" in ordinary, legitimate, in-scope proposal
  text (color-GRADE pass, rating-threshold review, delivery-format
  clearance checklist). `scope-excluded-terms` below therefore NEVER
  lists a bare noun -- every entry is phrased as the finalization/
  execution ACTION over the excluded decision (\"finalize the final
  cut\", \"final cut decision\", \"content rating decision\"), so a
  legitimate `:log-production-record` mentioning a routine editing cut,
  or a legitimate `:flag-content-concern` describing a rating-threshold
  observation, never self-trips this gate. Exercised directly by
  `postprodops.advisor-test/default-mock-advisor-proposals-never-self-
  trip-scope-exclusion`.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-content-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `postprodops.phase` independently agrees: `:flag-content-concern` is
  never a member of any phase's `:auto` set either -- two layers, not
  one."
  (:require [clojure.string :as str]
            [postprodops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-production-record :schedule-production-operation
    :flag-content-concern :coordinate-delivery})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-content-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- finalizing a creative
  final-cut decision, or finalizing a content-rating/clearance
  decision. Scanned across the proposal's op/summary/rationale/cites/
  value, never trusting the advisor's own framing of its intent.

  Deliberately phrased as multi-word finalization/execution ACTION
  phrases, never a bare noun (see the governor docstring's own
  phrasing-discipline note) -- \"cut\"/\"rating\"/\"grade\"/\"clearance\"
  alone are ordinary, legitimate post-production vocabulary."
  ["final cut decision" "final-cut decision" "finalize the final cut" "finalize final cut"
   "lock the final cut" "lock final cut" "picture lock decision" "confirm the final cut"
   "approve the final cut" "sign off on the final cut" "creative final-cut decision"
   "creative final cut decision"
   "最終編集の確定" "ファイナルカットの確定" "本編集の確定" "ピクチャーロックの確定"
   "content rating decision" "content-rating decision" "rating classification decision"
   "issue the content rating" "certify the content rating" "content clearance decision"
   "content-clearance decision" "rating clearance decision"
   "レーティング区分の確定" "コンテンツ審査の確定" "考証の確定" "配信区分の確定"])

;; ----------------------------- checks -----------------------------

(defn- project-unverified-violations
  "The target project must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:project-id` claim without a store lookup."
  [{:keys [project-id]} st]
  (let [r (store/project st project-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :project-unverified
        :detail (str project-id " は未登録または未検証のプロジェクト -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches finalizing-a-creative-final-cut-decision/
  finalizing-a-content-rating-clearance-decision territory, regardless
  of confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "創作上の最終編集(ファイナルカット)確定判断/コンテンツのレーティング区分・審査確定判断の領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a PostProdAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [project-id (or (:project-id proposal) (:project-id request))
        hard (into []
                   (concat (project-unverified-violations {:project-id project-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :project-id (:project-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
