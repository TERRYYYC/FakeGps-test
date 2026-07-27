---
feature_ids:
  - cellular-hook-verification
topics:
  - android
  - xposed
  - cellular
  - review
doc_kind: review-request
created: 2026-07-27
---

# Review Request: Complete cellular hook acceptance

Review-Target-ID: cellular-hook-acceptance  
Branch: `feat/cellular-hook-acceptance`

## What

Replace the partial current-profile diagnostic with a debug-only acceptance transaction. Two
distinct schema-v2 matrices verify serving cells, neighbor cells, signal controls, carrier/state,
display info, and physical-channel getters across synchronous, request, and callback paths. Every
terminal path republishes the user's database-backed config; the saved profile database is never
written.

## Why

The previous shell script could exit zero after observing only a few configured fields, so it could
not prove the cellular hook. The new verdict is exact and fail-closed: missing paths, type drift,
stale sessions, probe errors, restore failures, or database changes all fail the run.

## Original Requirements

> “继续你的任务，目标是完成蜂窝网络信息hook的验证。  
> 你可以邀请fable5.0进行更加合理的ui重构，  
> 有计划了你们直接进行，带着最终的版本见我就行。”

- Source: Cat Café thread `thread_mrmp97akqux0a16w`, message
  `0001785109781306-000199-f26f5014`
- Please judge whether the diff actually proves the cellular hook rather than merely adding more
  diagnostics.

## Tradeoff

- The acceptance component is debug-only and signature-permission protected; release manifest and
  bytecode contain no entry point.
- `PhysicalChannelConfigListener` needs the platform-only
  `READ_PRECISE_PHONE_STATE`. The probe therefore crosses the real registration before-hook, records
  the framework permission rejection, and locally replays one empty callback. The production hook
  must replace it with a real API-35 `PhysicalChannelConfig`; every getter is then asserted.
- Signal fluctuation is disabled for exact device assertions, while deterministic unit coverage
  proves the enabled range and variation behavior.

## Architecture Ownership

Architecture cell: Android hook/config transport and public-API probe  
Map delta: none  
Why: the change exercises the existing schema-v2 safe-zone transport and hook boundaries; it does
not add a parallel store, router, adapter, or production entry point.

Please verify that the diff remains consistent with `Map delta: none`.

## Open Questions

### Technical OQ

- Does the physical-channel permission-boundary replay genuinely exercise the production callback
  hook without overstating what an unprivileged app can register for?
- Are the API-24/29/31/33 guards and constructor-shape fallbacks correct across the supported range?
- Can any activity/shell termination path leave the temporary payload published or mutate user
  data?
- Does the weak-identity neighbor registry remain leak-safe and stable under mutable framework
  hash codes?

### Value OQ

None.

## Next Action

Run an independent review against the exact PR HEAD. Persist either logical approval or findings as
a signed GitHub PR comment because all cats share one GitHub account.

## Review Sandbox

- Path: `/tmp/cat-cafe-review/cellular-hook-acceptance/opus`
- Start command:

```bash
git clone --no-checkout https://github.com/TERRYYYC/FakeGps-test.git .
git fetch origin feat/cellular-hook-acceptance
git checkout --detach origin/feat/cellular-hook-acceptance
```

- No web/API ports are required; this is an Android/JVM/device-harness change.

## Self-check Evidence

### Spec compliance

- Plan: `feature-specs/2026-07-27-cellular-hook-verification.md`
- Original requirement coverage: serving and neighbor identity/signal, carrier/state, callback,
  physical-channel, exact verdict, isolated restore, release exclusion.
- UI work is intentionally absent from this branch and remains isolated in PR #3.

### Validation

```bash
./gradlew clean testDebugUnitTest assembleDebug assembleRelease lintVitalRelease
# BUILD SUCCESSFUL; 57 JVM tests, 0 failures

python3 -m unittest scripts.test_cellular_acceptance_matrix scripts.test_hook_verdict
# 10 tests, 0 failures

./scripts/test-hook.sh --cellular-matrix
# two scenarios × 274 exact assertions = 548/548 verified
# database unchanged; database-backed safe-zone fingerprint restored
```

Release artifact checks prove that `HookAcceptanceActivity`, its signature permission, payload
validator, and probe implementation are absent from the release manifest/DEX. Root-level media
artifact checks return zero.

[砚砚/gpt-5.6-sol🐾]
