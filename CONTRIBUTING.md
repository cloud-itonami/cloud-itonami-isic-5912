# Contributing to cloud-itonami-isic-5912

Contributions should preserve the actor's scope: back-office coordination only,
with CRITICAL exclusions of finalizing a creative final-cut decision and
finalizing a content-rating/clearance decision (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Finalize a creative final-cut decision (what the finished picture actually is, whether it locks as cut).
- Finalize a content-rating/clearance decision (which classification/certificate the finished programme receives).

Contributions that cross these boundaries will be rejected.
