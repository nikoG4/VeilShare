VeilShare — Claude Code Agent Instructions

This repository is worked on with Claude Code using a local Qwen 3.5 9B model.

The repository, tests, Git history, checkpoints, and worklog are the source of truth.
Do not rely on conversation memory when the repository can answer the question.

1. Always Recover Context First

At the beginning of every new or resumed session, run:

git status
git status --short
git log --oneline -20

Then read, in this order:

CLAUDE.md

docs/agent/LOCAL_AGENT_WORKLOG.md

latest files under docs/checkpoints/

relevant design/security docs for the current task

current code/tests involved in the task

If the session was interrupted, do not restart the task from scratch.

Recover:

DONE
IN PROGRESS
TODO

from Git, files, tests, and the worklog.

Priority of truth:

working tree / code
> tests
> Git history
> worklog
> documentation
> conversation memory

Never discard unfinished work just to get back to a clean tree.

2. Local Qwen Execution Rules

This is a local model. Avoid long uninterrupted reasoning and giant changes.

Use this execution pattern:

READ
→ UNDERSTAND
→ LOCATE TEST
→ CHANGE MINIMALLY
→ RUN TARGETED TEST
→ RECORD
→ NEXT

Do not use:

GUESS
→ LARGE REFACTOR
→ HOPE

Rules:

Work on one concrete implementation block at a time.

Prefer several short reasoning/tool cycles over one long reasoning cycle.

Do not hold many unresolved decisions in memory.

Do not plan an entire multi-hour implementation in one reasoning pass.

After writing at most 1–2 source files, run the smallest relevant test/build.

After completing each coherent block, update the worklog.

If a task has more than ~3 implementation steps, split it into smaller sequential blocks.

Do not reread files already summarized in the worklog unless needed.

Search the repository instead of guessing names, APIs, modules, or Gradle tasks.

Do not stop after the first green build if the current objective still has clear remaining work.

Useful searches:

rg "LocalAppController"
rg "VaultHandle"
rg "CatalogDurable"
rg "ReferenceCode"
rg "SessionRegistry"
rg "PresenceRegistry"
rg "signaling"

For Gradle tasks, discover instead of inventing:

.\gradlew.bat tasks
.\gradlew.bat :module:tasks --all

3. Persistent Memory

Use:

docs/agent/LOCAL_AGENT_WORKLOG.md

as persistent operational memory.

If it does not exist, create it.

Keep it factual and compact.

Suggested structure:

# Local Agent Worklog

## Repository
branch:
current HEAD:
working tree:

## Latest Stable Baseline

## Current Objective

## Security Invariants

## Completed

## In Progress

## Pending

## Bugs Found

## Tests / Commands Verified

## Decisions

## Blockers

## Next Action

Update it after every meaningful block.

Do not write hidden reasoning or long internal deliberation into the worklog.
Store only facts, decisions, commands, results, and the exact next action.

4. Interrupted Session Recovery

If Claude Code, the local model, or the API stream stops:

On restart:

git status
git status --short
git log --oneline -20
git diff
git diff --stat
git diff --cached

Then read:

CLAUDE.md
docs/agent/LOCAL_AGENT_WORKLOG.md

Inspect any modified or untracked files before touching them.

Determine:

DONE
IN PROGRESS
TODO

Continue from the first genuinely incomplete task.

Do not redo completed green work.

Do not ask the user where the session stopped if Git/worklog can answer it.

5. Context and Compaction

Keep the active context small.

The filesystem is long-term memory.
The model context is only for the current task.

When the session becomes large, use /compact.

Preserve during compaction:

current Git HEAD;

working-tree state;

current objective;

DONE / IN PROGRESS / TODO;

modified/created files;

latest test/build commands and results;

current failing test and first real error, if any;

security invariants;

exact next action.

Discard during compaction:

resolved exploratory searches;

repeated documentation summaries;

obsolete alternatives;

verbose build output;

already-fixed errors.

Recommended compact instruction:

/compact preserve current task, Git state, changed files, latest tests, security invariants, worklog status, and exact next action

Do not wait until the context is nearly exhausted.

6. Checkpoint Policy

Prefer durable progress over one giant end-of-session change.

After every coherent green block:

run targeted tests;

update LOCAL_AGENT_WORKLOG.md;

inspect git diff;

if the block is complete and coherent, create a local checkpoint commit.

Several small local commits are preferable to one huge commit.

Never push unless explicitly requested.

Suggested commit style:

feat: ...
fix: ...
test: ...
docs: ...
chore: ...

7. Git Safety

Allowed:

inspect status/log/diff;

create local commits;

create branches if needed and justified.

Never run without explicit user instruction:

git reset --hard
git clean -fd
git clean -xfd
git checkout .
git restore .
git push --force
git push

Never destroy uncommitted work.

Before committing:

git status --short
git diff
git diff --cached

Check that no unrelated files are included.

8. Testing Strategy

After a small change:

targeted test

After a completed module/phase:

module regression

Before declaring completion:

full relevant regression

Do not run the entire suite after every tiny edit.
Do not accumulate many changes without testing.

If a test fails:

read the first real error;

identify the exact failing code;

locate the nearest test;

reproduce minimally;

fix the root cause;

rerun the targeted test;

rerun the relevant regression;

update the worklog.

Never make tests green by:

deleting assertions;

deleting tests;

broadly suppressing errors;

swallowing exceptions;

adding arbitrary sleeps/timeouts without understanding the cause.

9. Build Failures

If a build breaks after a toolchain/dependency change, first suspect compatibility:

Gradle
AGP
Kotlin
Compose
JDK
dependency metadata

Do not rewrite architecture to solve a version mismatch.

Change one coherent version group at a time when practical.

10. VeilShare Frozen Core

Treat the secure local core as frozen unless a real regression proves a bug.

Known stable principles:

DESKTOP CORE: FROZEN
ANDROID CORE: FROZEN
LOCAL PRODUCT RELEASE READINESS: COMPLETE

Do not casually modify:

core-crypto;

persistence primitives;

catalog format;

journal format;

VBL1 format;

transactional commit semantics;

REAL/DECOY behavior.

Any core change must answer:

What exact reproducible bug requires this?

If there is no concrete bug, do not change the core.

11. Security Invariants

These are contracts:

AUTHENTICATED CATALOG = LOGICAL SOURCE OF TRUTH

CORRUPTION != CLEANUP AUTHORIZATION

REFERENCED != ORPHAN

UNKNOWN != SAFE_TO_DELETE

AMBIGUOUS != SAFE_TO_DELETE

PRE-COMMIT CANCELLATION != PUBLISHED IMPORT

POST-COMMIT CANCELLATION != ROLLBACK

When evidence is insufficient:

PRESERVE BYTES

Never weaken these invariants for convenience.

12. Import Rules

Production import is streaming.

Do not introduce:

readBytes()
readAllBytes()

into the production import pipeline.

Logical commit point is historically:

CatalogDurable

After cancellation or exception near commit:

reload authenticated catalog
→ trust durable authoritative state

Never assume:

exception = rollback

13. Delete Rules

Delete must continue through the existing transactional use case.

Do not directly manipulate:

blobs;

journal;

catalog files.

After an exception near delete commit:

reload authenticated catalog

If the entry is absent in the authenticated durable catalog, treat it as logically deleted.

14. REAL / DECOY Privacy

Never expose user-facing labels such as:

REAL
DECOY
real vault
fake vault
bóveda real
bóveda falsa
PIN real
PIN señuelo

The unlock experience must remain generic.

For sharing/network identity:

sharing identity for context A
!=
sharing identity for context B

Do not introduce network-visible identifiers that correlate both contexts.

Do not use a shared Android ID, hardware ID, installation UUID, or stable device fingerprint for both contexts.

15. Sharing V1 Current Direction

Current Sharing V1 direction is intentionally simple.

V1 transport:

Android/Desktop
    ↓
Ktor WebSocket relay
    ↓
blind signaling server

Not V1:

WebRTC;

STUN/TURN;

direct P2P;

byte-range resume.

Reference codes:

random
high entropy
rotatable
revocable
not derived from identity

Server-visible metadata must stay minimal.

Filename, MIME, private metadata, and file details belong inside the future authenticated E2E payload.

No per-chunk ACK in V1.

Disconnect during transfer:

restart full transfer

16. Sharing Crypto Separation

Never reuse vault keys for network sessions.

Do not reuse:

VMK
KEK
FileKey
catalog key
slot key

Network/sharing keys are separate.

Future handshake design may use standard primitives, but do not invent custom cryptography.

If implementing handshake crypto later:

follow documented protocol;

use established primitives/libraries;

add test vectors;

bind protocol version/session/transfer/transcript;

protect against replay;

separate traffic keys from identity keys.

17. Receiver Import Boundary

Future network receive path must be:

decrypted network stream
→ ImportSource adapter
→ existing ImportCoordinator
→ normal durable catalog commit

Never bypass ImportCoordinator.

Never write directly from sharing code to:

BlobStore;

catalog;

journal.

18. Metadata Privacy

Server-side signaling structures must not contain:

filename;

MIME;

plaintext digest unless explicitly justified;

FileKey;

VMK;

vault type;

REAL/DECOY labels;

local contact alias.

The server may inevitably observe:

connection timing;

opaque routing identifiers;

reference-code registration/lookup;

frame sizes;

traffic volume.

Document leakage honestly.
Do not claim metadata invisibility when traffic analysis can reveal coarse information.

19. Scope Discipline

Do not broaden the task automatically.

If the current objective is protocol foundation, do not jump into:

file transfer;

UI;

WebRTC;

Android lifecycle networking;

production handshake crypto.

Finish the current phase first.

If an optional item is blocked:

mark BLOCKED
→ continue independent required items

20. Autonomy

Continue automatically while there is a clear, safe next task.

Do not ask:

Do you want me to continue?
Should I run the tests?
Should I proceed to the next phase?

The default answer is yes.

Stop only if:

a real secret/credential is required;

physical hardware is required and cannot be simulated;

a product decision is genuinely ambiguous;

continuing could destroy user data;

the current objective is complete;

there is a real blocker with no independent work remaining.

21. Secret and Artifact Hygiene

Before a checkpoint, scan for accidental secrets and generated artifacts.

Look for:

BEGIN PRIVATE KEY
api_key
token
password
keystore
.env
local.properties
secret

Interpret results; test fixtures may contain synthetic values.

Never commit:

local.properties;

local SDK paths;

build outputs;

plaintext temp files;

emulator exports;

real credentials;

signing passwords;

production keystores.

22. Final Completion Gate

Before declaring a phase complete:

targeted tests green;

relevant regression green;

docs/worklog updated;

git status reviewed;

no unexpected untracked files;

no security invariant weakened;

local commit created if the block is coherent;

no push.

Final report should include:

current HEAD;

working-tree state;

work completed;

bugs found/fixed;

files changed;

tests and exact commands;

known limitations;

manual QA still pending;

local commit hash if created;

exact next action.

23. Most Important Rule

Do not optimize for one uninterrupted heroic session.

Optimize for:

short task
→ small change
→ test
→ worklog
→ local checkpoint
→ next task

If the local API stream dies, the repository must already contain enough durable state to resume with minimal loss.

The goal is not to avoid every interruption.

The goal is to make interruptions cheap and recoverable.