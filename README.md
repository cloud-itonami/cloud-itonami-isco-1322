# cloud-itonami-isco-1322

Open Occupation Blueprint for **ISCO-08 1322**: Mining Managers.

This repository designs a forkable OSS business for a mining manager: a coordination robot performs shift scheduling, production data logging, and maintenance scheduling under a governor-gated actor, so the manager maintains independent operational records and safety auditing instead of renting a closed mine-management SaaS.

## Critical domain boundary: ADMINISTRATIVE SUPPORT, NOT OPERATIONAL AUTHORITY

**This actor supports a MINING MANAGER's administrative workflow — NOT extraction decisions, production targeting, or mine-safety authority.**

Scope: Manager-side administrative operations
- ✓ Shift and crew scheduling
- ✓ Production data logging and tracking
- ✓ Equipment maintenance coordination
- ✓ Safety concern flagging (always escalated to human review)

Out of scope: Operator/Management-exclusive decisions (hard-blocked, no override path)
- ✗ Extraction/blasting decisions (powder type, load size, timing, sequence)
- ✗ Production targeting or quota setting
- ✗ Mine-safety determinations (ventilation adequacy, gas levels, hazard classification)
- ✗ Equipment operation sequencing or authorization

This boundary is enforced as a hard invariant in the governor (`mining-managers.governor`): the operation vocabulary is a closed allowlist (`mining-managers.operation/supported`), and the operator-class ops (`:extract`, `:blast`, `:set-production-target`, `:mine-safety-auth`, `:equipment-sequence`, `:ventilation-auth`, `:ore-grade-assess`) are declared *reserved* so the refusal names the reason; anything else is refused as undeclared. No override path: `approve!` refuses a held thread.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a coordination robot performs shift scheduling,
production data entry, and maintenance scheduling under an actor that proposes
actions and an independent **Mining Manager Governor** that gates them.
The governor never dispatches operations itself; `:flag-safety-concern`
logging or `:high`-risk site dispatch require human sign-off. Extraction/blasting/
production-targeting/mine-safety decisions are operator/management-exclusive and 
permanently blocked.

## Core Contract

```text
shift request + production data + maintenance plan
        |
        v
Manager Advisor -> Manager Governor -> scheduling/logging/coordination, or human sign-off
        |
        v
coordination actions (gated) + operating records + audit ledger
```

No automated advice can dispatch an operation the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `1322`). Required capabilities:

- :robotics
- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section): a real [`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
human-in-the-loop interrupt/resume via checkpointing.

```text
:intake -> :advise -> :govern -> :decide -+-> :commit                          (:ok? true)
                                           +-> :escalate -> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold                            (:hard? true)
```

Until 2026-09-11 this section described a graph that did not exist: `actor.kotoba`
required nothing from langgraph, `run-request!` was a stub that re-implemented
advise/govern/decide in a threading macro and stopped before writing, and
`approve!` set `:committed` on whatever map it was handed -- including a held
one. Measured through it, a clean request, a safety concern and its approval
all left the store with 0 records and an empty ledger. The graph below is the
one that runs now; `clojure -M:sim` is how to watch it refuse.

### Proposal operations (`mining-managers.operation/supported`, all `:effect :propose`)

- `:schedule-shift` -- shift and crew scheduling for a registered mine-site
- `:log-production-report` -- production data logging (tonnage, grade as reported, hours)
- `:coordinate-maintenance` -- equipment maintenance coordination (work order, window, crew)
- `:flag-safety-concern` -- surface a safety concern; **ALWAYS escalates to human review**

### Hard invariants (ALWAYS `:hold`, never overridable)

1. **Manager provenance** -- the request's manager must be registered.
2. **Site provenance** -- the request must name a mine-site, and that site must be
   registered with a `:risk-level` of `:low`, `:medium` or `:high`. Until 2026-09-11
   the site was optional in practice: a request that named none was admitted, which
   bypassed invariant 7 below; and a site registered `:risk-level :extreme` (or with
   none, or with the string `"high"`) read as safe.
3. **No direct actuation** -- proposal `:effect` must be `:propose` (never `:commit` or `:dispatch`).
4. **No operator-class operations** -- `:extract`, `:blast`, `:set-production-target`,
   `:mine-safety-auth`, `:equipment-sequence`, `:ventilation-auth` and `:ore-grade-assess`
   are *reserved* (`mining-managers.operation/reserved`): permanently rejected with the
   reason, never escalated, because no human can delegate operator/management authority
   to the actor.
5. **Declared vocabulary** -- an `:op` outside `mining-managers.operation/supported` is
   rejected as `:undeclared-operation`. This is what makes rule 4 a boundary rather than a
   list of examples: until 2026-09-11 the governor was a denylist of seven names, and
   `:authorize-blast`, `:detonate` and `:override-ventilation` against a registered
   manager and site were all `ok? true`.
6. **Well-formed envelope** -- a proposal whose `:op` is not a keyword, or whose
   `:confidence` is present but not a number in `[0.0, 1.0]`, is rejected rather than
   compared against (measured before the fix: `:confidence 1.5` was admitted,
   `:confidence "high"` threw `ClassCastException` out of the governor).

### Escalation invariants (ALWAYS human sign-off, per the robotics premise)

7. **`:flag-safety-concern` ALWAYS escalates** -- declared `:escalates? true` on the
   operation itself.
8. **Any operation at a `:risk-level :high` site** escalates.
9. **Low confidence** (< `confidence-floor` = 0.6) escalates -- LLM parse failures or
   uncertain advisors.

An escalation is written to the audit ledger as `:disposition :escalate` *before* the
graph interrupts, so a request that waits for a human -- or is never approved -- has a
trace. When a human resumes the thread, the resulting commit entry carries
`:approved-by :human`; an automatic commit carries `:approved-by :actor`. `approve!`
refuses (returns `{:status :refused ..}`, runs nothing) unless the thread's checkpoint is
`:interrupted` -- a held thread cannot be approved into a write. Every ledger entry is
hash-chained (`mining-managers.ledger/verify`).

### Files

- `src/mining_managers/operation.kotoba` -- the closed vocabulary: `supported` and `reserved`.
- `src/mining_managers/facts.kotoba` -- well-formedness of manager record, site record,
  request and proposal envelope; violations compose with the governor's own.
- `src/mining_managers/store.kotoba` -- `Store` protocol + `MemStore`: registered managers,
  registered mine-sites, committed records, and the hash-chained audit ledger.
- `src/mining_managers/ledger.kotoba` -- entry construction, `chain-hash`, `verify`.
- `src/mining_managers/phase.kotoba` -- verdict -> `:hold` / `:request-approval` / `:commit`,
  and what each phase may do. Hard outranks escalate.
- `src/mining_managers/advisor.kotoba` -- `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a managerial operation from a request; `llm-advisor`
  wraps a `langchain.model/ChatModel` -- either way the advisor only ever produces a
  `:propose`-effect proposal, never a committed record, and LLM parse failures always
  yield `confidence 0.0` (forces escalation, never fabricated confidence).
- `src/mining_managers/governor.kotoba` -- `check`: a pure function, wired as its own
  `:govern` node, delegating vocabulary to `operation`, well-formedness to `facts`.
- `src/mining_managers/actor.kotoba` -- `build-graph`, `run-request!`, `approve!`: the
  `langgraph.graph/state-graph` wiring itself.
- `src/mining_managers/sim.kotoba` -- the governed-scenario harness: 17 requests through
  the wired graph, 14 of which must be refused. Exits non-zero if none is.

### Running it

```bash
clojure -M:test   # 79 tests / 375 assertions across 8 namespaces; refuses (exit 2) below that floor
clojure -M:sim    # the scenario table; exit 1 if it demonstrates no refusal
clojure -M:lint   # clj-kondo over the .kotoba sources by explicit file list; exit 2 if it found none
```

The three entry points are `run_tests.kotoba`, `run_sim.kotoba` and `run_lint.kotoba`,
loading the sources by path through `load_sources.kotoba`. They are paths rather than
`-m` namespaces because the 2026-09-10 rename moved every source to `.kotoba`, which
`require` does not resolve -- on `acfd967` the previous aliases ran 0 tests and linted 0
files, and both exited 0.

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
