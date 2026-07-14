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

This boundary is enforced as a hard invariant in the governor (`mining-manager-governor`): any proposal flagged with operator-class ops (`:extract`, `:blast`, `:set-production-target`, `:mine-safety-auth`, etc.) is instantly blocked with no override path.

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
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

- `src/mining_managers/store.cljc` — `Store` protocol + `MemStore`:
  registered managers, registered mine-sites, committed records, an append-only audit ledger.
- `src/mining_managers/advisor.cljc` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a managerial operation from a
  request; `llm-advisor` wraps a `langchain.model/ChatModel` — either
  way the advisor only ever produces a `:propose`-effect proposal,
  never a committed record, and LLM parse failures always yield
  `confidence 0.0` (forces escalation, never fabricated confidence).
- `src/mining_managers/governor.cljc` — `MiningManagerGovernor/check`: a pure
  function, wired as its own `:govern` node. Hard invariants
  (unregistered manager, unregistered mine-site, a proposal whose `:effect`
  isn't `:propose`, or any operator-class op) always route to `:hold`.
  Escalation invariants (`:flag-safety-concern`, high-risk site operations, or low advisor
  confidence) always route to `:request-approval` — an `interrupt-before` node that the graph
  checkpoints and only resumes on explicit human approval (`actor/approve!`).
- `src/mining_managers/actor.cljc` — `build-graph`, `run-request!`,
  `approve!`: the `langgraph.graph/state-graph` wiring itself.

```bash
clojure -M:test
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
