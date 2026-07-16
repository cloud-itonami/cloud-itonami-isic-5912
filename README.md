# cloud-itonami-isic-5912

**Motion picture, video and television programme post-production activities** — ISIC Rev.4 class 5912.

A coordination-only actor for motion-picture/video/television post-production back-office operations (editing, VFX, sound mixing, color grading), behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-production-record, schedule-production-operation, coordinate-delivery, flag-content-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Project verified** — target project (an edit/VFX/mix/grade project under contract) must exist AND be registered/verified in the store before any proposal for it may commit or escalate.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — finalizing a creative final-cut decision (what the finished picture actually is, whether it locks as cut), and finalizing a content-rating/clearance decision (which classification/certificate the finished programme receives), are permanently blocked, regardless of confidence or op.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: production-record logging only (approval-gated)
  - Phase 2: + production-operation scheduling, delivery coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (content concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL scope exclusions

This actor coordinates the back-office operations of a post-production facility:
edit-pass/VFX-shot/sound-mix-session production-record logging,
editing/VFX/color-grading/mix scheduling proposals, final-master
delivery/handoff coordination (QC checklist, delivery-format package,
distribution-codec handoff scheduling), and content-concern flagging
(rating-classification-threshold or sensitive-scene-edit concerns).

**This actor does NOT:**
- Finalize a creative final-cut decision (what the finished picture actually is, whether it locks as cut).
- Finalize a content-rating/clearance decision (which classification/certificate the finished programme receives).

Every proposal is `:effect :propose` only. `:flag-content-concern` always
escalates to a human, at every phase, regardless of confidence — this
actor never self-clears a content-rating or sensitive-content concern it
raises. Ordinary post-production vocabulary ("cut", "rating", "grade",
"clearance" used as routine editing/color/delivery terms) is never
itself scope-excluded — only multi-word phrases naming the finalization
ACTION over the excluded decision are (see `postprodops.governor`'s own
phrasing-discipline note, and `postprodops.advisor-test`'s dedicated
`default-mock-advisor-proposals-never-self-trip-scope-exclusion`
regression test).

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/postprodops/governor_test.clj` — unit tests of governor hard checks and scope exclusion
- `test/postprodops/advisor_test.clj` — advisor proposal shape and consistency
- `test/postprodops/phase_test.clj` — rollout phase logic
- `test/postprodops/governor_contract_test.clj` — full graph integration, audit trail
- `test/postprodops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `postprodops.store` — SSoT (MemStore, String-keyed project directory, append-only ledger)
- `postprodops.advisor` — contained intelligence node (mock + real-LLM seam)
- `postprodops.governor` — independent compliance layer
- `postprodops.phase` — staged rollout (0→3)
- `postprodops.operation` — langgraph-clj StateGraph
- `postprodops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-facing/personal-services) fleet. See ADR-2607121000, ADR-2607152500, and the ISIC-5912 coverage ADR for design decisions.
