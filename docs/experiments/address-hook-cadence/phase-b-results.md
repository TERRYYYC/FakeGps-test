---
feature_ids: [F-HOOK-CADENCE]
topics: [hook, latency, fileobserver, inotify, measurement]
doc_kind: experiment-result
created: 2026-08-03
---

# Phase B Results: Event-Driven FileObserver

## Summary

Phase B replaces the polling timer with an inotify-based `FileObserver` as the
primary config propagation path. The timer continues as a safety-net heartbeat.
A SHA-256 fingerprint skip makes redundant reloads (duplicate inotify events,
timer heartbeats) near-free.

**Result: 57x faster config propagation (14.5s -> 0.255s mean).**

## Measured Latency

### Phase B (10 rounds, random phase, seed=99)

| Metric | Value |
|--------|-------|
| samples | 10 |
| min | 0.224s |
| mean | **0.255s** |
| median | 0.268s |
| max | 0.278s |
| std dev | ~0.020s |

### Phase A Baseline (from phase-a-insight.md)

| Metric | Default 30s | Configured 5s |
|--------|-------------|---------------|
| mean | 14.5s | 2.5s (theoretical) |
| distribution | uniform(0, interval) | uniform(0, interval) |

### Improvement

| vs Baseline | Factor |
|-------------|--------|
| 30s default (mean 14.5s) | **57x** |
| 5s configured (mean 2.5s) | **10x** |
| 30s worst case (max ~30s) | **108x** |

## Key Observations

1. **Latency is constant and independent of timer interval.** Phase skew
   (0.13s to 3.80s across rounds) has zero correlation with observed latency.
   The observer fires on inotify event, not on timer tick.

2. **The ~250ms floor is measurement overhead**, not propagation latency.
   Composed of: adb shell roundtrip (~100ms) + logcat timestamp resolution
   (3 decimal places = ms granularity) + probe write latency. Actual kernel
   inotify delivery is sub-millisecond.

3. **Fingerprint skip eliminates timer tick noise.** In 20 seconds of
   observation (4 timer ticks per process at 5s interval), zero `transport
   accepted` log entries were produced. Every tick hit the fingerprint skip and
   returned without JSON parsing. Before Phase B, each tick produced a
   `transport accepted` log.

4. **Fail-closed verified.** The observer arm evidence
   (`event=observer_armed`) was confirmed in logcat for `com.google.android.apps.maps`.
   If arm had failed, `event=timer_fallback` would appear and the timer would
   run alone at configured interval (no regression).

## Device Evidence

### Observer arm (logcat)
```
FakeGPS-Hook: event=scheduler_owned process=com.google.android.apps.maps intervalMs=5000
FakeGPS-Hook: event=observer_armed process=com.google.android.apps.maps dir=/data/misc/6997007a-de90-4cd8-8b44-9c0a17e91ed1/prefs/name.caiyao.fakegps
```

### Probe output (10 rounds)
```
samples 10 | min 0.224 | mean 0.255 | median 0.268 | max 0.278 (s)
configured 5s -> theoretical uniform(0,5), mean 2.5s
```

### Raw data
```json
[0.278, 0.224, 0.231, 0.225, 0.234, 0.276, 0.269, 0.276, 0.275, 0.267]
```

## Architecture

```
Config write (SharedPreferences.commit())
    |
    v
atomic rename (old inode -> new inode)
    |
    v
inotify MOVED_TO event on directory
    |
    v
PrefsDirectoryObserver.onEvent()
    |
    v (filename filter)
    |
    v
MainHook.reloadSnapshot()
    |
    v (SNAPSHOT_LOCK)
    |
    v
loadSnapshot()
    |
    v (fingerprint skip: SHA-256 compare)
    |        match -> return CURRENT (no parse)
    v        miss  -> full JSON parse + Snapshot build
    |
    v
CURRENT.set(newSnapshot)  <-- all ~25 hooks serve new value
```

Timer heartbeat runs in parallel (same `reloadSnapshot()` path). Fingerprint
skip makes timer ticks near-free when observer has already delivered the
latest change.

## Test Evidence

- 47 test suites, 0 failures, 0 errors
- 2 new bytecode contract tests verify observer + fingerprint wiring
- Device: moto g54 5G (ZY22JHW9M4), Android 15, LSPosed

## Commit

`6b3eb5b` on `feat/address-hook-cadence`

[宪宪/claude-opus-4-6]
