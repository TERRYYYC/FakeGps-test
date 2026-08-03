---
feature_ids: [F001]
topics: [android, mock-location, main-app, profiles, lifecycle, acceptance]
doc_kind: quality-gate-report
created: 2026-08-03
---

# Mock Location 主 App 集成 — Evidence Manifest

## Provenance

- Repository: `https://github.com/TERRYYYC/FakeGps-test.git`
- Branch: `feat/mock-provider-main-integration`
- Accepted code commit: `19a5fd2edef2628c50b5d2c44158b0ea51aa4334`
- Device: moto g54 5G `ZY22JHW9M4`, Android 15
- Debug main APK: `app/build/outputs/apk/debug/app-debug.apk`
- Debug APK SHA-256: `fe04eb60ccd06e3729928b2e48bc6bccc6f0d6ba8d70ba3bba2da12cde4f8836`
- Release APK SHA-256: `8b17ef20db03fd82078cb0bb9d8afb0e45e94eb1ad0be891f7166fd8352614a4`
- Private screenshot: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/maps-main-kyiv.png`
- Screenshot SHA-256: `bed5bb83275566d4e687e82f6f9af0feefa5fe616babb3bebbc9aba603c16e9c`
- Settings OFF screenshot: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/settings-main-system-mock-off.png` (`08bb96ae13d77e3e48ece5def00649663474a9bef9b5f41b59cb905f4e1d6d0b`)
- 15-second switch recording: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/settings-main-toggle.mp4` (`bb359df2d29db50e67a5b00aea12965448aa47f97544bc891071653b307f9df1`)

截图留在设备备份目录而不进入 Git。画面显示 Google Maps 蓝点位于 Kyiv 的 Independence Square / Maidan Nezalezhnosti 附近，与主 App 生效中档案 `50.4501,30.5234` 一致。

## 对 co-creator 四项纠正的闭环

| 纠正 | 产品终态 | 验收证据 |
|---|---|---|
| Lab 没有合入主 App | 删除独立 `mockProvider` build type；service/controller/gateway 进入 `src/main`，设置页成为唯一入口 | debug/release 均含非导出 `MockProviderService`；不再生成 Lab APK |
| 数据应来自主 App 档案，开关决定 Hook/Mock | schema v4 发布 `locationDeliveryMode`；System Mock 每 tick 解析 `ConfigPrefsSync` 的同一份生效档案；System Mock 时 Hook 仅清空位置字段，cell/Wi-Fi 保留 | JVM 契约覆盖 v2/v3 兼容、档案解析、位置旁路与非位置字段保留；真机用 `ProfileRepository` 保存 Kyiv 后经正常 transport 输出 |
| 虚拟位置不能停 | provider 变更前写 durable cleanup marker；显式 Stop 无条件 remove；验收直接读 provider truth | UI 关闭后 `gps provider:` 恢复 `identity=1000/android[GnssService]`；普通 Hook 启动无 service/失败，marker 恢复路径有单测 |
| 地址改为基辅 | 地图默认、示例与隔离验收档案统一为 `50.4501,30.5234` | gps、fused 和 Maps 蓝点三路一致 |

## Exact-code 真机结果

验收从真实 GNSS 且参考 App 独占 mock app-op 开始，只安装 `.bench` debug main APK，并仅重置 `.bench` 的隔离数据：

```text
PROVIDER_REAL owner=GnssService
PROVIDER_MOCK owner=name.caiyao.fakegps.bench coordinate=50.4501,30.5234
isForeground=true ... types=0x00000008
last location=Location[gps 50.450100,30.523400 ... mock]
last location=Location[fused 50.450100,30.523400 ... mock]
MAPS_FOREGROUND ... com.google.android.apps.maps/com.google.android.maps.MapsActivity
ACCEPTANCE_ACTIVE_PHASE_COMPLETE
PROVIDER_REAL owner=GnssService
ACCEPTANCE_STOP_PHASE_COMPLETE
RESTORE bench=deny reference=allow provider=real status=0
REFERENCE_APP_FOREGROUND ... com.adevinta.leku.LocationPickerActivity
ACCEPTANCE_RESTORE_PHASE_COMPLETE
```

恢复后又单独启动一次 `.bench` 的默认 Hook 模式：mock app-op 仍由参考 App 独占、`MockProviderService` 不存在、gps provider 仍是真实 GNSS、日志无启动失败。最后再次 force-stop `.bench` 并打开参考 App。

Settings OFF 图与 15 秒录屏直接覆盖用户入口、同一生效档案坐标和开关动作；Maps 图覆盖运行时下游效果。录屏结束时脚本再次确认 `.bench` 仍安装、开关为 OFF、provider 为 GNSS，并恢复参考 App。

## Fresh verification

| Gate | Result |
|---|---|
| `./gradlew testDebugUnitTest --rerun-tasks` | 378 tests；0 failure/error/skipped |
| `./gradlew assembleDebug assembleRelease --rerun-tasks` | BUILD SUCCESSFUL；release `lintVital` 通过 |
| `python3 scripts/test_mock_provider_main_integration.py` | 6/6 pass |
| `bash -n scripts/mock_provider_acceptance.sh` | pass |
| `git diff --check` | pass |
| APK manifest inspection | debug `.bench` / release main identity 正确；两者保留 Xposed metadata、动态 provider authority 与 `foregroundServiceType=location` |
| `scripts/mock_provider_acceptance.sh ZY22JHW9M4` | active / Maps / Stop / restore 全阶段完成，exit 0 |
| `./gradlew lintDebug --rerun-tasks` | inherited baseline：20 errors / 148 warnings；20 个 error 全部位于未改动的 `HookProbe.kt`、`MainActivity.java`、`TempDao.java` 与 `res/values/strings.xml`，本 diff 零 lint error；release `lintVital` 通过 |

## Quality Gate 审计

- Vision / delivery completeness：四项 operator 纠正已逐项映射到产品入口、同源档案、真实 Stop 与 Kyiv；本次产物是可扩展的主 App 实现，不再需要把 Lab 重写一遍。
- Close gate：当前只申请 code review，不关闭整个 F001；follow-up-tail scan 零命中，无未满足 AC 被包装为“后续”。
- Architecture ownership：`Android application / location delivery`；`Map delta: none`，因为仓库无 ownership registry 且组件均在既有 `:app` 内。新增 gateway/service 是该 cell 内实现边界，不引入外部服务或第二份状态存储。
- Fallback audit：仓库无自动脚本。`EffectiveMockLocationResolver` 的多处 early-return 分别校验 latitude、longitude、accuracy 与 schema；它们是独立输入边界，不是层叠补锅。Hook 默认值只用于 v2/v3 升级兼容，不能删除；platform `runCatching` 只把 Android 启动失败转换成用户可见状态。
- Design check：仓库无 `.pen`。Compose 设置入口以 Settings OFF 截图、15 秒开关录屏和 Maps 下游截图验收。
- Artifact hygiene：Git 工作树与 `origin/master...HEAD` 均无仓库根目录媒体；设备图片/视频仅在正式 backup evidence 目录。
- Capability tips / Cat Café architecture scripts：该 Android 仓库无对应 surface，not applicable。

### Dogfood-Your-Slice

Scope verdict：✅ 必做。

真实路径：隔离 `.bench` 经 `ProfileRepository` 保存 Kyiv → 正常 `ConfigPrefsSync` 发布 → 设置页打开 System Mock → gps/fused/Maps 验证 → 设置页关闭 → provider identity 验证 → 恢复参考 App。

Dogfood 当轮发现并修复：

1. 旧 harness 只看 PID/app-op，漏掉 system_server 孤儿 provider → 改为 provider-truth 断言并引入 durable cleanup marker。
2. `.bench` manifest authority 与硬编码 `UriMatcher` 不一致 → authority 改为由 `BuildConfig.APPLICATION_ID` 构造并加 JVM test。
3. 普通 Hook 启动若无 app-op 会产生伪失败 → 只在 cleanup marker 为 true 时恢复清理，并增加 startup-plan test 与真机 no-op 验证。
4. debug 数据准备在 app-op 切换前做无意义 cleanup → 准备步骤仅重置隔离数据/marker，最终运行日志不再有伪失败。

## 已知边界

- System Mock 位置保留 Android mock marker；本功能不尝试隐藏 `Location.isMock()`。
- API 24–30 legacy registration 有纯代码契约和 review 覆盖；本轮真机是 API 35。
- 已运行的 Hook 目标进程按既有 5–60 秒可配置周期读取 mode。开关关闭会立即移除系统 provider；目标 Hook 在下一次刷新读取 Hook 模式，这是现有 transport 的传播语义，不伪装成同步切换。
- debug acceptance Activity 受 signature-level `android.permission.DUMP` 保护且不进入 release；它只操作 `.bench` 数据。
