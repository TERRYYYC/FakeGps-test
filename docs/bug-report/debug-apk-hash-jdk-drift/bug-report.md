---
feature_ids: [F001]
topics: [android, build, provenance, jdk, reproducibility]
doc_kind: bug-report
created: 2026-08-03
status: resolved
resolved: 2026-08-03
---

# Debug APK hash 跨 JDK 漂移被误判为脏源码

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | PR #10 R3 的作者 evidence 记录 debug APK `0aa312f2…f9cbc`；Fable5 从 exact HEAD 四次构建均得到 `83e725aa…ebc4`，因而怀疑作者真机安装的是脏工作区或残留产物。期望是 artifact provenance 能区分源码与构建环境输入。 |
| **2. 证据** | 作者 worktree 在 clean exact HEAD `343fa455…` 上用 Android Studio JBR 21.0.10 重建仍得到 `0aa312f2…f9cbc`；同一 worktree 改用 Homebrew OpenJDK 17.0.20 重建即得到 `83e725aa…ebc4`。对应 Gradle daemon 分别记录 `javaVersion=21` 与 `javaVersion=17`。 |
| **3. 根因** | `sourceCompatibility` / `targetCompatibility = 17` 只约束 classfile 目标，不固定执行 javac/Gradle 的 JDK。JDK 17 对 `UnavailableValueResolver` 的 enum switch 额外生成 `$1` synthetic class，JDK 21 不生成；D8 因此改变 `classes3.dex` 与 `classes11.dex`。旧 evidence 把 source SHA 当成 debug APK hash 的全部输入，遗漏 JDK provenance。 |
| **4. 诊断策略** | 先从 clean exact HEAD 重建证伪“残留 build”，再对两个 APK 做逐 entry SHA、签名证书与 DEX class-tree 比较，最后只改变 `JAVA_HOME` 做单变量重建。 |
| **5. 超时策略** | 若单变量 JDK 重建不能复现两种 hash，则继续比较 AGP/SDK/debug keystore；在输入闭包明确前不改写原始 dogfood artifact。 |
| **6. 预警策略** | 只替换成某一 reviewer hash、或继续声称跨环境唯一 hash，会抹掉真实安装产物并让下一次跨 JDK review 再次误报。 |
| **7. 用户可见交互修正** | 无产品 UI 变化；evidence 同时记录作者 JBR 21 与 reviewer JDK 17 产物，hash 必须和构建 JDK 一起引用。 |
| **8. 验收** | JBR 21 clean build 稳定为 `0aa312f2…f9cbc`；JDK 17 clean build 稳定为 `83e725aa…ebc4`；两 APK 同签名、同资源，只有两个 DEX entry 不同；文档不再宣称 debug hash 仅由 exact source 决定。 |

## 报告人

Fable5 在 PR #10 R3 独立复审中发现 hash 不一致；Sol 负责复现、APK 内容对比与构建输入逆向追踪。

## 复现步骤

1. checkout PR #10 exact HEAD `343fa455bfd4c7f420e371276a062175bc0462cf` 并保持 tracked worktree clean。
2. 用 Android Studio JBR 21.0.10 执行 `:app:clean :app:assembleDebug --rerun-tasks`，得到 `0aa312f2…f9cbc`。
3. 只把 `JAVA_HOME` 改为 Homebrew OpenJDK 17.0.20，重复同一命令，得到 `83e725aa…ebc4`。
4. 比较 APK entries：签名证书、资源和 16 个 DEX 相同；`classes3.dex` / `classes11.dex` 不同，JDK 17 产物含 `UnavailableValueResolver$1`。

## 根因分析

R3 没有引入脏源码或残留 build。差异来自未纳入旧 provenance 的 Gradle runtime JDK：Java 17 target compatibility 不等于 Java 17 compiler/toolchain pin。javac 的合法 lowering 差异继续传播到 D8，因此同一源码可以产生两个功能等价但字节不同的 debug APK。

## 修复方案

- 保留作者真机实际安装的 JBR 21 hash，避免篡改既有验收链。
- 新增 reviewer 真机实际安装的 JDK 17 hash。
- 每个 hash 都显式绑定 exact source 与 Gradle runtime JDK；删除“debug hash 跨环境唯一”的断言。
- 本轮不把 JDK toolchain pin 混入已获代码 APPROVE 的功能 PR；若未来需要单一 canonical artifact，应在独立 build-system change 中固定 toolchain 并重新 review。

## 验证方式

- 两种 JDK 均从 `:app:clean` 开始，单变量重建分别复现两种 hash。
- `apksigner verify --print-certs`：两者证书 SHA-256 均为 `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41`。
- APK 逐 entry SHA：仅 `classes3.dex` 与 `classes11.dex` 不同。
- Fable5 已用 JDK 17 产物完成 R3 全链真机验收 exit 0；作者此前用 JBR 21 产物完成两次全链 exit 0。
