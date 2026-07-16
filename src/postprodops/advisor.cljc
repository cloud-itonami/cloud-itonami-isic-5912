(ns postprodops.advisor
  "PostProdAdvisor -- the *contained intelligence node* for the
  ISIC-5912 motion-picture/video/television post-production
  operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: production-record logging (edit-pass/VFX-shot/mix-session
  data), production-operation scheduling (editing/VFX/color-grading/
  mix scheduling), content-concern flagging (rating-classification
  threshold or sensitive-scene-edit concern), and final-master
  delivery/handoff coordination. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a direct actuation --
  every proposal's `:effect` is always `:propose`. Every output is
  censored downstream by `postprodops.governor` before anything
  touches the SSoT.

  This advisor NEVER drafts a finalized creative final-cut decision
  (what the finished picture actually is, whether it locks as cut) or a
  content-rating/clearance decision (which classification/certificate
  the finished programme receives) -- those are permanently out of
  scope for this actor, not merely un-implemented. `postprodops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :project-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-production-record
  "Draft a production-record log entry: edit-pass status, VFX-shot
  status, sound-mix-session notes, color-grading-pass number. Pure
  back-office logging of observed production facts -- never a
  creative final-cut judgment."
  [_db {:keys [project-id patch]}]
  {:op         :log-production-record
   :project-id project-id
   :summary    (str project-id " の制作記録（編集パス/VFXショット/ミックスセッション）を記録: " (pr-str (keys patch)))
   :rationale  "編集パス・VFXショットステータス・サウンドミックスセッションなどの制作事実の記録のみ。創作上の編集判断は行わない。"
   :cites      [project-id]
   :effect     :propose
   :value      (merge {:project-id project-id} patch)
   :confidence 0.94})

(defn- propose-production-schedule
  "Draft an editing/VFX/color-grading/mix scheduling proposal (a
  calendar/bay-booking entry, never a direct render dispatch or a
  sign-off on the edited content itself)."
  [_db {:keys [project-id patch]}]
  {:op         :schedule-production-operation
   :project-id project-id
   :summary    (str project-id " の制作工程（編集/VFX/カラーグレーディング/ミックス）スケジュールを提案: " (pr-str (keys patch)))
   :rationale  "編集・VFX・カラーグレーディング・サウンドミックス工程の日程調整のみ。仕上がりの編集内容そのものを決めるものではない。"
   :cites      [project-id]
   :effect     :propose
   :value      (merge {:project-id project-id} patch)
   :confidence 0.89})

(defn- propose-delivery-coordination
  "Draft a final-master delivery/handoff coordination proposal (QC
  checklist, delivery-format package, distribution-codec handoff
  scheduling only -- never the creative sign-off or content-rating
  clearance of the delivered master itself)."
  [_db {:keys [project-id patch]}]
  {:op         :coordinate-delivery
   :project-id project-id
   :summary    (str project-id " の最終マスター納品/ハンドオフ調整を提案: " (pr-str (keys patch)))
   :rationale  "最終マスターの納品フォーマット・QCチェックリスト・配信用パッケージの受け渡し日程調整のみを行う。作品内容や区分の判断は行わない。"
   :cites      [project-id]
   :effect     :propose
   :value      (merge {:project-id project-id} patch)
   :confidence 0.91})

(defn- propose-content-concern
  "Surface a rating-classification-threshold or sensitive-scene-edit
  concern (observed during editing/VFX/color/mix review) for HUMAN
  triage. This op ALWAYS escalates in `postprodops.governor` -- never
  auto-committed at any phase -- regardless of how confident the
  advisor is that the concern is real."
  [_db {:keys [project-id patch]}]
  {:op         :flag-content-concern
   :project-id project-id
   :summary    (str project-id " のコンテンツ懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "レーティング区分の閾値や機微な描写の編集に関する観察事実の報告。常に人間の確認・判断が必要。"
   :cites      [project-id]
   :effect     :propose
   :value      (merge {:project-id project-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-production-record (propose-production-record _db request)
                   :schedule-production-operation (propose-production-schedule _db request)
                   :coordinate-delivery (propose-delivery-coordination _db request)
                   :flag-content-concern (propose-content-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the final cut decision and issued a content rating decision")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :project-id (:project-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
