# RingKit Agent Workflow

This file is the durable, repository-level operating contract for coding agents working on RingKit. It is intentionally tracked so it is present in every branch and Git worktree.

System, developer, and explicit user instructions take precedence over this file. More-specific `AGENTS.md` files, if added below this directory, apply to their subtrees. `PLAN.md` governs product behavior; this file governs how agents work.

## Project

RingKit is an Android-only Flutter incoming-call plugin. It provides CallKit-style ringing, notifications, full-screen incoming-call UI, killed-state event delivery, optional Telecom integration, and opt-in fleet telemetry.

- No iOS implementation.
- No RTC/media stack; the host app owns call media.
- Native cold-process behavior must not depend on Dart being alive.

## Mandatory Session Startup

Before planning or editing:

1. Run `git status --short` and preserve all unrelated user changes.
2. Run `git worktree list --porcelain` and identify the primary worktree.
3. Read this file completely.
4. Locate and read the local engineering sources when present:
   - `CLAUDE.md`
   - `PLAN.md`, especially the sections relevant to the task
   - the active `SCOPE-*.md` and its handoff file
5. Find the first applicable unchecked scope item or determine that the request is a bounded bugfix outside the active scope. Record bounded bugfixes in the active scope progress log when they affect that scope, such as telemetry incident types.

`CLAUDE.md`, `PLAN.md`, `SCOPE-*.md`, and `evidence/` are intentionally gitignored and may exist only in the primary worktree. If they are absent from a linked worktree, read them from the primary worktree returned by `git worktree list`; do not silently proceed as though they do not exist.

If an internal source is genuinely unavailable, state that limitation before changing behavior. Do not reconstruct or publish ignored internal documents from memory.

## Source of Truth and Spec-First Rule

- The current `PLAN.md` is the behavioral specification. Do not hardcode its version in this file; read the header and changelog.
- Name the applicable PLAN section before implementation begins.
- If intended behavior contradicts PLAN, update PLAN first with a one-line changelog entry, then update code.
- PLAN state-transition and wire-contract tables are normative data. If they change, update their data files and generated tests in the same change; never hand-edit only the implementation.
- Scope documents control sequencing and completion. Tick completed boxes and append concise, evidence-backed progress entries.

## Work Style

- Lead with the target outcome, success criteria, constraints, and current evidence.
- Proceed autonomously through clear, safe, reversible inspect-edit-test work.
- Ask only when blocked by missing authority, credentials, destructive action, or a materially branching product decision.
- Diagnose read-only unless the user asks for a fix. A fix request authorizes scoped implementation and verification, not unrelated refactors.
- Prefer small, reviewable diffs. Reuse existing helpers and patterns before adding abstractions or dependencies.
- For multi-step changes, maintain a concise plan and keep at most one step in progress.
- Use test-driven development for bugfixes and behavior changes: reproduce with a failing test, implement the smallest fix, then run broader regression checks.
- Use independent review for major or concurrency-sensitive changes. Resolve Critical/High findings before completion; surface architectural watch items honestly.
- Never claim an OEM-specific bug is proven fixed from unit tests or a generic emulator alone.

## Toolchain and Dependency Boundaries

- Flutter stable 3.44 or newer; Dart 3.x.
- Android minSdk 26; compileSdk/targetSdk 36; Java 17.
- Use AGP Built-in Kotlin. Do not apply `org.jetbrains.kotlin.android` and do not add a `jvmToolchain` pin.
- Pigeon 27.x is pinned exactly. Regenerate every generated output on a Pigeon change; never edit generated `*.g.*` files by hand.
- `android/.../core/` may depend only on `androidx.core:core-ktx`, `kotlinx-coroutines-android`, and `androidx.core:core-telecom`; it must have zero Flutter imports.
- `firebase-messaging` remains `compileOnly` and confined to the FCM integration boundary.
- Do not add or upgrade dependencies without explicit PLAN/toolchain consideration and current primary documentation.

### Current documentation with Context7

Use Context7 whenever the task involves a library, framework, SDK, API, CLI, or cloud service, including setup, migration, configuration, or library-specific debugging.

1. Resolve the official library:
   `npx ctx7@latest library <official-name> "<full task question>"`
2. Select the best official/high-reputation `/org/project` match.
3. Fetch relevant documentation:
   `npx ctx7@latest docs <library-id> "<full task question>"`

Use no more than three Context7 commands per question. Never include credentials, API keys, tokens, device identifiers, or other secrets in queries. For version-specific work, prefer the versioned library ID returned by Context7. If Context7 reports quota exhaustion, report it and suggest `npx ctx7@latest login` or `CONTEXT7_API_KEY`; do not silently replace current documentation with memory. If the command fails because of network/DNS restrictions, retry through the permitted unsandboxed execution path.

## Architecture Boundaries

- `android/.../core/`: repository, state machine, transition table, event log, sweeps, receivers, and native core. No Flutter imports.
- `android/.../ui/`: notification factory, incoming activity, and ringtone/vibration policy.
- `android/.../services/`: ringing, ongoing-call, and short-lived services.
- `android/.../adapter/`: the only Flutter-aware native layer, including plugin/Pigeon/headless integration.
- `android/.../telecom/`: feature-flagged Android Telecom integration.
- `android/.../fcm/`: optional native Firebase message integration.
- `android/.../telemetry/`: opt-in transport and scheduling; never part of `core/`.
- `lib/src/`: Dart facade, models, event buffering, and acknowledgement behavior.
- `pigeons/messages.dart`: single cross-platform schema source.

## Never-Regress Invariants

Treat these as load-bearing. Preserve their named regression coverage.

1. The incoming full-screen PendingIntent remains attached except for the single documented notification-only FGS-owner exception.
2. FGS notification timeout APIs are not the timeout safety mechanism; repository reconciliation sweeps remain on every native entry path.
3. Native persistence stays in `noBackupFilesDir` with install-ID fencing; plugin core state never moves to SharedPreferences.
4. Terminal tombstones and pre-tombstones prevent duplicate or out-of-order messages from re-ringing.
5. Only the designated Activity-attached Flutter engine acknowledges events. Background isolates cannot ack normal events; headless deliveries hold leases.
6. Every service calls `startForeground(...)` as the first effective statement in `onStartCommand`, rebuilds from repository state for a null intent, and returns `START_NOT_STICKY`.
7. Ringtone playback uses a verified ladder: `MediaPlayer` primary, Android `Ringtone` for content-URI/OEM access failures, then the bundled raw fallback. Apply `USAGE_NOTIFICATION_RINGTONE` and `CONTENT_TYPE_SONIFICATION` before data source/prepare. Vibration uses the API 26-32 AudioAttributes overload and API 33+ VibrationAttributes. `matchesCallFilter` is API 34+ only.
8. Never change `AudioManager.mode`, register screen-off/volume receivers, use custom RemoteViews, or request mic/camera/notification-policy permissions. Components remain unexported except the documented Mode-2 receiver protected by the GMS signature permission.
9. Call-action receiver PendingIntents retain `FLAG_RECEIVER_FOREGROUND`.
10. Rejected or losing state transitions have zero side effects beyond trace logging.
11. Telecom mode calls `addCall` before posting the notification.
12. Mode-2 integration preserves `tools:replace="android:enabled"` and `-dontwarn com.google.firebase.messaging.**`; Mode-2 degradation remains Mode-2-only.
13. Deadline math uses `elapsedRealtime` plus persisted boot count. Wall time is display-only.
14. Telemetry is fully inert when configuration is null. Ring logging is asynchronous, core opens no sockets, uploads never use FCM/high priority, API keys/tokens are never logged, default uploads are PII-redacted, incident dedup/budgets remain enforced, and full buffers upload only on explicit pull.

## Supabase and Telemetry Work

- Use the Supabase workflow/tools for every Supabase database, Auth, Edge Function, Storage, RLS, migration, or log task.
- Prefer Supabase MCP/project evidence over assumptions about deployed state.
- Keep secrets out of terminal output, Context7 queries, commits, logs, fixtures, and screenshots.
- Schema/function/RLS changes require migrations and focused tests. Follow the repository's existing multi-app key separation and service-role boundaries.
- Any new incident type must be checked against classification, deduplication, upload redaction, budget behavior, tests, and the active telemetry scope progress log.

## Definition of Done

A feature or bugfix is complete only when all applicable items are satisfied:

1. The relevant PLAN section was identified; PLAN was updated first if behavior changed.
2. A named regression test covers each touched R-rule (`rXX_...` naming where applicable).
3. Targeted tests pass, followed by the relevant full regression suites.
4. `flutter analyze --fatal-infos` is clean and Dart tests pass.
5. Android/Robolectric tests pass.
6. Integration or release-mode checks pass when the changed path affects native packaging, shrinking, services, resources, or device behavior.
7. The real physical-device scenario is run through ADB for device/OEM/background/notification/audio behavior. Emulator testing is supplementary, not a substitute.
8. Evidence is saved under `evidence/<phase>/<feature>.md` with the device/model/API, exact scenario, relevant `RingKit` logcat excerpt, relevant `dumpsys` output, and a one-line PASS/FAIL verdict.
9. Bug investigations attach `diagnose()` output. Degradation work proves that `ShowCallResult`/`diagnose()` matches visible behavior.
10. Review findings are resolved or clearly reported, the diff is clean, and no unrelated user files are included.

If physical hardware is unavailable, stop before claiming OEM verification or pushing a change whose workflow requires device evidence. Report the exact missing gate.

## Verification Commands

Run the smallest targeted check first, then broaden as required:

```bash
flutter analyze --fatal-infos
flutter test

# Build the example once if its Gradle wrapper/generated Android files are absent.
(cd example && flutter build apk --debug)
(cd example/android && ./gradlew :ringkit:testDebugUnitTest)

# Device/emulator integration.
(cd example && flutter test integration_test -d <device-id>)

# Native packaging/resource/R8 sanity when applicable.
(cd example && flutter build apk --release)
```

Also run `git diff --check`. Read every command's output and exit status before reporting success. Existing warnings must be distinguished from new failures; do not hide either.

## Physical-Device Evidence Minimum

For show/ring changes, evidence must establish all of the following on the target device:

- package was backgrounded/killed in the intended scenario;
- ringer/DND/ring-volume state was recorded;
- incoming CallStyle/notification and ringing FGS are present in `dumpsys`;
- RingKit trace shows policy, audio-focus result, playback engine/state, and any fallback;
- audible ringtone behavior is observed on the physical device;
- accept/decline/cancel/timeout leaves no ghost service, notification, or active call;
- `diagnose()` reports the same degradation or healthy state that was observed.

Useful probes include:

```bash
adb devices -l
adb shell dumpsys audio
adb shell dumpsys notification --noredact
adb shell dumpsys activity services <package>
adb logcat -s RingKit:V
```

Use the repository's explicit simulation receiver or real FCM harness as documented by the active scope/evidence cookbook. Do not place tokens or credentials in evidence files.

## Git, Worktrees, and Repository Hygiene

- Preserve unrelated dirty or untracked files. Never reset, clean, checkout over, or delete user work to make a task easier.
- Use an isolated Git worktree for feature work when it improves safety. Before using it, read ignored internal documents from the primary worktree.
- Keep `AGENTS.md` tracked so every worktree receives this startup contract. Keep PLAN/CLAUDE/SCOPE/evidence local and ignored unless the user explicitly changes that policy.
- Commits are small and cohesive. Allowed subject prefixes are `core:`, `ui:`, `svc:`, `dart:`, `fcm:`, `telecom:`, `test:`, and `plan:`.
- Never add Claude/Codex attribution trailers.
- Never combine a toolchain bump with feature behavior.
- Never commit with failing or skipped required tests.
- CI is billed. Do not push device-sensitive changes until required physical-device evidence exists, unless the user explicitly overrides that gate after the missing evidence is clearly reported.
- Do not commit or push unless the user requested it. Verify local, upstream, and remote SHAs after a push.
- Public package documentation must not expose ignored internal PLAN/SCOPE/evidence content or internal section-number references.

## Completion Report

Lead with the result. Include:

- changed files and behavior;
- tests and device/emulator evidence actually run;
- physical devices and API levels used;
- commit/branch/remote state when applicable;
- remaining risks, warnings, skipped gates, or required follow-ups.

Never use “fixed,” “verified,” “complete,” or “ready to merge” beyond what the collected evidence proves.
