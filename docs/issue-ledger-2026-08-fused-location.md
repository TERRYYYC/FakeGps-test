---
feature_ids: [F001]
topics: [android, mock-location, fused, evidence, acceptance, provenance, lessons]
doc_kind: issue
created: 2026-08-07
status: in-progress
---

# Issue 与证据纪律台账 — 2026-08 fused location 轮次

本文件是**索引与边界声明**，不是结论的副本。每条都指向既有 canonical doc；canonical doc 说了
什么就以它为准，这里只补两件它们各自说不全的事：**这一轮的结论边界到哪为止**，以及**下次别再
踩的判据**。

刻意排除：review lease / projection / evidenceRef 一类**团队协作治理**教训不写在这里。它们不是
FakeGPS 的项目知识，应路由既有 Process Evolution 真相源。本文件只收 Android / 定位 / 证据方法学。

---

## 一、Issue 状态台账

### Issue #15 — PASS，但 PASS 的是被明确划定的那一块

verdict：**PASS / done**。下面三条是这个 PASS **不覆盖**的范围，任何引用 #15 的人先读这三条：

| 面 | 覆盖方式 | 边界 |
|---|---|---|
| listener 路径 | 真机行为覆盖 | 这是 PASS 的实证主体 |
| recenter | **依赖 operator attestation** | 不是自动化断言，是人工确认；换设备/换版本不自动继承 |
| LAST / CURRENT Task surface | **本机行为未触发** | 未被证伪，也未被证实——是"没测到"，不是"测过没问题" |

把 #15 转述成"fused 路径全面 PASS"就是越界。per-delivery 证据（PR #21）之所以要做，正是因为
install-time 证据让"安静地正常投递"和"停止投递"不可区分，逼得 #15 的 A/B matrix 一度 BLOCKED。
参见 `app/src/main/java/name/caiyao/fakegps/hook/DeliveryEvidencePolicy.java`。

### Issue #14 — bounded NOT-REPRODUCED，ROOT UNKNOWN

**只能这样写**：在当前这台设备、这个版本上，按已执行的复现路径**未复现**。

不能写的三种说法，以及为什么：

- ❌「已修复」——没有定位到根因，也就没有"修"这个动作。**ROOT UNKNOWN**。
- ❌「普遍 PASS」——样本是一台设备一个版本，外推不成立。
- ❌「不存在」——见下面第三条：absence 结论必须与探针范围匹配。

**`IMMEDIATE_0_30_REPLAY` 仍 NOT TESTED**：即时（0–30s）第三方 / bench replay 这条路径这一轮
根本没跑。它既没通过也没失败，是空白。下一轮谁接手 #14，第一件事是补这段，而不是重复已经
NOT-REPRODUCED 的那条路径（理由见第四条）。

相关：`docs/bug-report/fused-real-location-flapping/bug-report.md`。

### 本轮新发现，尚未处理

**`scripts/cellular_acceptance_matrix.py` 的 schemaVersion 已漂移** — master 上**既有**红灯，
不是本轮改动引入。

- `app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt:49` 声明 `SCHEMA_VERSION = 4`
  （PR #10 升上去的），而 `scripts/cellular_acceptance_matrix.py:224` 自 PR #3 起一直硬编码
  `"schemaVersion": 3`，从未跟进。
- `scripts/test_cellular_acceptance_matrix.py::test_python_payload_version_is_pinned_to_writer_contract`
  正在如实报红（`4 != 3`）——这个漂移探测器本身是好的，它抓到了真东西。
- 影响面：蜂窝验收 harness 一直在按**旧 schema** 发 payload。`PREVIOUS_SCHEMA_VERSION = 3` 还在，
  所以 reader 大概率仍接受，于是它**没有炸**——但它验的是兼容路径，不是当前出货路径。
- **本轮不修**：v3→v4 到底加了哪些字段没有核实，把 `3` 直接改成 `4` 只会让 payload 谎报自己的
  版本——比现在更糟。这需要单独定位再修，属另一条线。

---

## 二、证据方法学：这一轮实际付了学费的判据

### 1. absence 结论必须与探针范围匹配

"没查到"只在**探针能查到的范围内**成立。整机 mock baseline 必须用**全量 app-op 枚举**得出；
拿单包查询的结果外推成"整机没有 mock"是无效推理——你只证明了那一个包。

写 absence 结论时把探针范围一起写进去，否则这句话下次会被当成更强的断言引用。

### 2. Vector 是 in-memory dex，`/proc/<pid>/maps` 证明不了它没加载

`maps` 只列出**文件映射**。in-memory dex 不落文件，因此不出现在 `maps` 里。
「`maps` 里没有 → 模块未加载」是**探针与被测对象不匹配**，属于第 1 条的具体案例。

### 3. 同一台坏仪器重复读数，不是独立证据

同一条有缺陷的复现路径跑 N 次，得到的是 1 份证据，不是 N 份。#14 的
NOT-REPRODUCED 不会因为再跑几遍而变强。要提高置信度只能**换探针**（换设备、换版本、换触发
路径——比如那条还没跑的 `IMMEDIATE_0_30_REPLAY`）。

### 4. 缺日志 ≠ 失败，按固定顺序逐层排除

没看到预期日志时，**依次**核这五层，不要跳：

1. module 有没有加载
2. config 有没有下发
3. surface 有没有 hook 上
4. provider 有没有真的发起 request
5. 设备是不是**解锁态**

第 5 层最容易漏——锁屏态下很多定位消费者根本不请求，于是"没日志"完全正常。

这正是 per-delivery 证据要解决的问题：让**沉默有确定含义**。心跳存在时，"安静"意味着"在正常
投递"；心跳也没有，才是"停了"。

### 5. `fused[mock]=0` 单点证明不了真实位置泄漏

一个 `fused[mock]=0` 只是一个**读数**。判泄漏至少还要两样：

- **消费者请求**：有没有人真的在请求这条 surface？没人请求时的陈旧值不构成泄漏。
- **value lineage**：这个值是从哪来的？是 hook 前的原始输入，还是缓存，还是 hook 后的产物？

缺这两样就下"泄漏"结论，会把正常的缓存读数报成 P0。参见
`docs/bug-report/fused-real-location-flapping/bug-report.md`。

### 6. 遥测双轴的四条硬约束

两个轴（delivered / input）同时上报时：

- **互斥词表**：两轴不能共用 token。第一版让 interception 复用 delivery 词表，结果健康的拦截
  （真实值被档案位移）报出 `NOT_EQUAL`，而验收 harness 把 `NOT_EQUAL` 读成 snap-back——**结论
  被反转**。所以有了 `INPUT_*` 这套不可能与投递失败混淆的独立词表。
- **同时进入 gate**：只用 delivered 轴做边沿触发等于没触发——该轴按构造几乎恒定（出参就是拿
  比较基准那份快照构造的），真正在变的是 input 轴。
- **token 与 count 同行**：一行证据的 token 必须描述它所计数的**每一次**投递。拿当前 input 配
  累计 count，这一行就会声称 N 次投递共享了一个它们并不共享的状态。
- **稀疏边沿必须在同一 callback 内可见**：one-shot / 稀疏 surface 可能再也不投递了，没有下一次
  心跳来带出这条边沿。**延迟上报的边沿等于从未发生过**。

契约见 `app/src/test/java/name/caiyao/fakegps/hook/DeliveryEvidencePolicyTest.java`。

### 7. 证据异常不得影响投递

遥测出问题时，坏的是遥测，不是功能。证据链路上的任何异常都不能改变或中断实际的位置投递——
否则"为了看清楚"反而制造了要看的那个故障。

### 8. APK hash 必须与 exact source + Gradle runtime JDK 绑定

同一份 clean 源码在不同 Gradle runtime JDK 下产出**不同字节**（JDK 17 为 enum switch 多生成一个
`UnavailableValueResolver$1`，D8 把差异带进 `classes3.dex` / `classes11.dex`）。裸 sha256 因此
**不是**跨环境的源同一性证据，把它当证据会把合法的 JDK lowering 差异误判成"脏源码"。

这一轮之前它只是条**约定**，然后在 PR #21 被第二次违反。现已改为机制：
`scripts/apk_provenance.py` 是 APK 证据行的唯一合法产出口，缺 JDK 则不输出任何行并 exit 2。

顺带记一条**做工具时踩到的**：工具第一版读调用时刻的 git 状态，就以为绑定了 exact source。
实际上"在 commit A 构建、切到 B、再采集"会把 A 的字节标成 B——**读取时刻 ≠ 构建时刻**。
现在 `--build` 把构建夹在两次 source 读取之间，树移动就拒绝出行；不带 `--build` 只能记
`source_binding=asserted`。这条字段必填，因为对"实测还是假定"沉默会被读成实测。
完整设计与仍未关闭的部分见
`docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md#结构性根治2026-08-07-追加`。

⚠️ **Gradle runtime JDK 至今未固定**。漂移向量还在，只是现在每条证据都会记下自己落在哪一侧。

### 9. 模式切换后的 `0660/location=false` —— 二义性 finding，禁止先写成 bug

现象为真，**解释未定**。它可能是失效，也可能是既有传播语义的正常中间态（已运行的 Hook 目标
进程按 5–60 秒周期读取 mode，切换期间本就允许短暂重叠）。

在区分开之前，它只能记为 **finding**。先写成 bug 会把一个还没定性的观察固化成缺陷叙事，
后面所有人都顺着这个叙事找原因。**先定性，再命名。**

---

## 引用的 canonical docs

| 主题 | 真相源 |
|---|---|
| APK hash / JDK 漂移 | `docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md` |
| fused 真实位置抖动 | `docs/bug-report/fused-real-location-flapping/bug-report.md` |
| 主线集成验收证据 | `docs/acceptance/mock-location-main-integration-evidence.md` |
| Lab 历史证据（已退役） | `docs/acceptance/mock-location-v2-evidence.md` |
| per-delivery 证据契约 | `app/src/test/java/name/caiyao/fakegps/hook/DeliveryEvidencePolicyTest.java` |
| APK provenance 契约 | `scripts/test_apk_provenance.py` |
