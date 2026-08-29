---
name: fresh-agent-queue
description: Orchestrate multi-step work through a dependency-aware queue where the main agent only coordinates and every substantive work or validation attempt runs in a fresh subagent. Use for tasks that prioritize context isolation, bounded retries, independent testing, and compact progress tracking.
---

# Fresh Agent Queue

Keep the main agent as a thin orchestrator. It may clarify scope, maintain the compact queue ledger, dispatch agents, wait, report progress, and synthesize evidence. It must not inspect implementation details, edit artifacts, run substantive commands, solve queue items, or validate results itself. Queue all such work to fresh subagents.

## Non-negotiable invariants

- Represent every substantive unit as a queue item with an ID, objective, dependencies, acceptance criteria, status, attempt count, and evidence.
- Use a newly spawned subagent with `fork_turns: "none"` for every attempt. Never use a full-history fork, give a completed or failed worker another substantive queue item, or use `followup_task` to turn an old worker into a new-task worker. Never reuse an agent ID anywhere in the ledger.
- Treat investigation, implementation, testing, review, and repair as separate substantive work when each can change the result.
- Distinguish `implemented` from `verified`. Validation must be a distinct queue item performed by a fresh agent that did not create the artifact. A producer's claim that its own work passes is only implementation evidence, never verification.
- Keep the orchestrator's context to the queue/status ledger, concise worker outcomes, user constraints, and acceptance evidence. Do not retain workers' long reasoning traces.
- Do not broaden permissions through delegation. A subagent has only the authority granted by the user and the current task.

## Build the queue

Create the smallest dependency graph that covers the requested outcome. Prefer independently runnable items so available agent slots can be used in parallel, but record each item's write scope and serialize items whose write scopes overlap. Include an explicit validation item for each material deliverable or a clearly scoped integration validation item. A consumer depends on the producer's validation item, not merely on the producer, so it cannot start before the prerequisite is verified.

Each dispatch packet should contain only:

- queue item ID and one concrete objective;
- relevant paths or raw artifacts;
- completed dependency outputs needed for this item;
- acceptance criteria and required evidence;
- scope, safety, and authorization constraints;
- instruction to report a concise result, changed artifacts, commands run, and exact test evidence.

Do not include earlier agents' speculation, hidden conclusions, or a proposed answer unless the dependency output itself is required.

Use [references/acceptance-scenarios.json](references/acceptance-scenarios.json) as the maintained list of queue behaviors this skill must preserve. For file-backed coordination, use `scripts/queue-ledger.ps1`; its status output is intentionally compact.

The ledger schema is version 2. A validation failure is terminal for that queue item and uses status `failed`, even when the item has unused attempts. Add a distinct `kind: repair` item whose `repairs_validation` names the failed validation, then add a distinct validation item that both depends on and validates the repair item. The helper prevents the repair from starting before its named validation has failed, prevents direct revalidation of an artifact with an unresolved failed validation, and preserves any approval requirement on the repair. Do not reset, requeue, or repurpose the original validation item.

## Dispatch loop

1. Mark an item ready only when every dependency is independently verified. A validation item may start when its producer is implemented.
2. Spawn fresh agents for ready items until the lesser of the ledger concurrency limit and the available collaboration slots is reached. Reserve one slot for the orchestrator.
3. Record the unique agent ID and `fork_turns: "none"` at claim time. Count every started attempt, including crashed or interrupted attempts.
4. Wait for results without busy polling. While work is active, give the user short milestone updates and do not leave them without an update for more than 60 seconds.
5. Accept a work item only with concrete evidence matching its criteria: changed paths, command results, test counts, screenshots, or other inspectable output as appropriate.
6. Queue fresh-agent validation after production passes. If validation fails, leave that item `failed`, queue a fresh repair item linked with `repairs_validation`, and follow it with a separate validation item that validates the completed repair.
7. Finish only when all required items and their independent validation items have passed.

The orchestrator may use the ledger helper for administrative state changes. Running the helper is orchestration, not substantive task work.

## Failures, retries, and blocking

- Retry a transient or repairable non-validation failure only within the item's declared `max_attempts`; the default should be two attempts unless risk calls for fewer. Never retry a failed validation item: remediation and revalidation are new queue items with their own attempt budgets.
- Every retry uses a new agent and a clean dispatch packet containing the failed acceptance evidence, not the prior agent's full transcript.
- Do not automatically retry destructive or externally mutating actions. Stop and request any missing authorization.
- When attempts are exhausted, mark the item blocked and propagate that state to dependents. Name the failed criterion and the minimum user action or external change needed.
- Capacity exhaustion is `waiting`, not `blocked`; dispatch when a slot opens.
- If worker results conflict, queue a fresh adjudication item rather than deciding the technical question in the orchestrator.
- If collaboration tools are unavailable, report that the queue-only contract cannot be honored and stop. The main agent must not silently do the work itself.

## Self-test workflow

For changes to this skill, queue these items to fresh agents in order:

1. A producer updates the skill, helper, scenarios, or tests.
2. A fresh test agent runs `pwsh -NoProfile -File skills/fresh-agent-queue/tests/Test-QueueSkill.ps1` and the standard skill validator when available.
3. A fresh behavioral reviewer receives a realistic task plus this skill, but not the intended answer, and checks whether the main agent remains orchestration-only.
4. On failure, a fresh repair agent receives only the failing evidence and acceptance criteria; then a different fresh test agent reruns validation.

Report the exact commands and results as acceptance evidence. Never let the producer mark its own work validated.
