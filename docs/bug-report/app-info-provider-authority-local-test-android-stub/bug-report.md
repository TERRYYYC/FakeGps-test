---
feature_ids: [F001]
topics: [android, unit-test, content-provider, authority, rebase]
doc_kind: bug-report
created: 2026-08-04
---

# AppInfoProvider authority local test 触发 Android stub 初始化

## 1. 报告人

砚砚在把 PR #10 rebase 到最新 master `6fe6915931408dff6e795c5a433c4538a21a118d` 后运行 selected-profile / provider-authority 交叉契约时发现。

## 2. 复现步骤

运行：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'name.caiyao.fakegps.data.AppInfoProviderAuthorityTest' \
  --rerun-tasks
```

期望：local JVM 测试验证当前 variant 的 application id 被用于 provider authority。

实际：测试读取 `AppInfoProvider.AUTHORITY` 时触发整个 class 的静态初始化；`APP_CONTENT_URI` 随即调用 Android stub 的 `Uri.parse`，抛出 `ExceptionInInitializerError` / `Method parse in android.net.Uri not mocked`。

## 3. 根因分析

产品实现的 authority 是纯字符串，但测试把契约挂在 Android `ContentProvider` 类的静态字段上。Java 第一次读取该字段会初始化类内所有静态字段，因此测试实际依赖了 Android runtime，而不是只检查 authority。仓内已有 `MainHookRefreshContractTest` 用 class bytes 检查生产 wiring，证明 local JVM 可以在不初始化 Android 类的前提下验证接线。

## 4. 修复方案

保留产品实现，不为测试修改 `Uri` 初始化方式。测试改为读取已编译的 `AppInfoProvider.class` bytes，并断言其中同时引用 `ProviderAuthority`、`forApplicationId`、当前 `BuildConfig.APPLICATION_ID` 与 `AUTHORITY`；独立的纯 `ProviderAuthorityTest` 继续验证 release / bench 两种 application id 的精确输出。

放弃给 Gradle 开启全局 Android stub 默认值：那会把更多误用 Android runtime 的 local tests 静默变成假绿。也不引入 Robolectric，因为本契约只需验证纯 helper 与生产 wiring，不值得增加测试 runtime。

## 5. 验证方式

- RED：原测试稳定以 `AppInfoProvider.<clinit>` → `Uri.parse` 失败，32 tests 中 1 failed。
- GREEN：同一 32-test 交叉集合全部通过。
- 全量：latest master integration 为 418 tests，0 failure/error/skipped；Debug/Release/`lintVitalRelease` 成功。
- 真机：使用同一生产 APK 运行完整 picker → selected Kyiv profile → System Mock → Maps → recovery → GNSS restore 链，exit 0。

[砚砚/gpt-5.6-sol🐾]
