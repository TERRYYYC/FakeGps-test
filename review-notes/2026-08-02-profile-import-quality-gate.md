---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - quality-gate
  - profile-archive
  - csv
  - xlsx
  - room-transaction
doc_kind: quality-gate
created: 2026-08-02
---

# Quality Gate: 收藏档案 CSV / Excel 导入

Branch: `feat/profile-import-csv-excel`
Base: `origin/master` at `c34bf666983da3634dfba5f2b4c3ce0147cde019`
Spec: `feature-specs/2026-08-02-profile-import-csv-excel.md`

## Outcome

Implementation Tasks 1–4 are complete and the isolated acceptance journey is green. The feature is
ready for fresh-context scanning and independent review. Stable-device acceptance and merge remain
intentionally outside this gate: the mission sequences them after review authorization.

## Vision and acceptance check

| Requirement | Evidence | Result |
|---|---|---|
| CSV and XLSX both import real archives | UTF-8 BOM CSV plus three real OOXML fixtures; Android AVD selected and imported both valid formats | pass |
| Preview before write | reducer and compiled UI tests; AVD showed row/add/duplicate census before confirmation | pass |
| Invalid input is all-or-nothing | malformed CSV/XLSX, formula, extra sheet, header/type/range tests; invalid parse exposes no candidate batch | pass |
| One atomic Room write | in-memory Room instrumentation forces a failure after the first insert and proves exact rollback | pass |
| Existing profiles and publication remain unchanged | instrumentation compares the complete pre-state and a publication-call seam stays at zero | pass |
| Duplicate policy is deterministic | canonical `id=0` equality; file and database duplicates are skipped; AVD re-import reported 0 added / 3 skipped | pass |
| Full configurable field contract | one 85-field codec shared by editor and importer; common validator covers Unicode, nulls, booleans, JSON and numeric bounds | pass |
| No adjacent hook behavior changed | import path has no republish call and no cadence, Mock Location or LSPosed change | pass |

## Architecture and stateful-object gate

- Architecture cell: collection UI → bounded parser/validator → repository transaction → existing
  Room `temp` database.
- Map delta: none. No new process, database, transport or publication owner was added.
- `CollectionViewModel` owns the ephemeral generation and preview. Stale completion and repeated
  confirmation are reducer-tested.
- `ProfileRepository.importAll()` exclusively owns the Room transaction and rechecks database
  duplicates inside it.
- The importer always emits entities with `id=0`; Room owns primary-key assignment.

## Evidence manifest

| Gate | Command / environment | Result |
|---|---|---|
| JVM regression | `./gradlew :app:testDebugUnitTest --rerun-tasks` | 345/345 pass |
| Android test compile | `./gradlew :app:compileDebugAndroidTestKotlin` | pass |
| Debug APK | `./gradlew :app:assembleDebug` | pass |
| Release + R8 | `./gradlew :app:assembleRelease` | pass; `minifyReleaseWithR8` executed |
| Release lint | `./gradlew :app:lintVitalRelease` | pass; combined final gate executed 110/110 tasks |
| Repository scripts | `python3 -m unittest discover -s scripts -p 'test*.py'` | 44/44 pass |
| Shell syntax | `bash -n scripts/test-hook.sh` | pass |
| Diff hygiene | `git diff --check` | pass |
| Atomic Room acceptance | `connectedDebugAndroidTest` filtered to `ProfileImportTransactionTest` on isolated `f001_ui_test` AVD | 2/2 pass |
| Debug lint audit | `./gradlew :app:lintDebug` | existing baseline: 20 errors / 158 warnings; no finding names any changed or new feature file |

The debug lint failure is not classified as a feature regression. Its first error is the unchanged
`HookProbe.kt:117` API-level call. Release lint, which is the repository's shipping gate, passes.

## TDD evidence

- Importer fixture tests first failed on missing parser types, then passed 11 cases.
- Two protocol tests proved non-canonical OOXML content types and unsupported cell types were
  initially accepted; both now fail closed and the importer suite passes 13 cases.
- Shared field validation boundary tests were red before extraction and green afterward.
- A discrete GSM BER test proved the reserved 8–98 gap was initially accepted; the shared
  validator now accepts only `0..7` or the platform's `99` unknown sentinel.
- Duplicate planner tests were red before the repository boundary and green afterward.
- UI generation/single-confirm tests and compiled action-ownership contract were red before the
  Compose journey and green afterward.
- The Room rollback test runs against an isolated in-memory database and proves the failed batch
  leaves no first-row residue.

## Isolated dogfood

Environment: headless `f001_ui_test` AVD on emulator-5556. The operator's attached Moto G54 and its
stable profile database were not installed to, cleared or mutated.

- CSV preview: 3 data rows, 2 additions, 1 file duplicate; confirmation added 2 and skipped 1.
- XLSX preview: 2 data rows, 2 additions, 0 duplicates; confirmation added both.
- CSV re-import: 0 additions, 3 duplicates; summary explicitly said the effective profile did not
  change.
- Unicode names rendered correctly in the collection list.

Evidence (temporary, deliberately outside the Git tree):

- `/tmp/cat-cafe-evidence/profile-import/profile-import-preview.png`
- `/tmp/cat-cafe-evidence/profile-import/profile-import-list.png`
- `/tmp/cat-cafe-evidence/profile-import/profile-import-dedup-success.png`
- `/tmp/cat-cafe-evidence/profile-import/profile-import-journey.mp4`

## Scope and artifact audit

- No `.pen` design source exists in this repository; the UI was validated in the running Android
  app instead.
- Repository-root artifact scan is clean; screenshots and recording remain in `/tmp`.
- No new dependency, permission, schema migration, web/API port or Redis connection was added.
- The XLSX reader has multiple fail-closed validation branches, not fallback recovery layers. Each
  rejects a distinct hostile format class (ZIP traversal/resource abuse, unsupported OOXML shape,
  formula/error/external XML); removing one would weaken the file boundary.

## Remaining sequenced work

1. Fresh-context finding-only scan of the committed diff.
2. DeepSeek V4 Flash independent review against the exact pushed HEAD, including its own malformed
   and boundary fixtures.
3. After approval, stable-device acceptance without uninstalling or clearing existing data.
4. Opus Feature Doc Truth and merge-gate supervision.

---

*[砚砚/GPT-5.6-Sol🐾]*
