# AGENTS.md

Ninebot-plus-plus（NPP）是九号开放生态客户端与工具链：以 NinePlus 为起点，覆盖 Android / iOS 客户端、self-hosted Platform 与真实车辆联调，并向 ninecli 整合与 BLE 解权限 / 重编程探索延伸。本文件是 Agent 协作与安全合同。

## 核心规则

- 车控（上电、熄火、开座桶、寻车）是危险操作：只在车辆停放安全、周围无人时执行，且需用户明确要求。
- 不要把 token、session、密码、设备密钥写进 git、文档、测试输出或对话。
- 真实车辆数据、Platform 凭证与 ninecli 凭据只在本机 / 受控服务器使用，不进公开仓。
- 改动前先读 `README.md` 与 `docs/android-architecture.md`；Android 构建与单测见 `android/README.md`。
- **出一版 App 必须升版本号**：`android/app/build.gradle.kts` 的 `versionName`（语义化版本）与 `versionCode`（整数 +1）同批提交；只改其中一项视为未完成。

## Issue 驱动开发

- 本仓对应 Plane 项目 `NPP`，仓内 `#N` 即 `NPP-N`。开工前必须有工作项编号；没有就先要，或用 `idd new` 建。
- 分支 `<type>/N-<slug>`；提交首行 Conventional Commits，trailer `Refs: #N`。完整规则见 personal-ops `policies/issue-driven-development.md`。
- 提交和 PR 里禁止 `closes/fixes/resolves #N`；完成状态按统一政策核验合入证据后回写。
- 完成后回报改动文件路径和提交号；未经允许不用 `--no-verify`，不直接提交到主干。

## 交付表述

最终元数据、交接和面向用户的文档、标题、提交说明、PR，必须从已接受的最终状态和权威 diff 推导，而不是从会话历史推导。被否方案和用户纠正只作为控制上下文，除非它们对应真实基线变化，或对安全、兼容、迁移、审计或必要说明有实质需要。当某概念只存在于本次工作会话时，不要用「无 X」「已移除 X」「非 X 版」或同类框架描述已接受结果。提交、发布或开 PR 时按全局 skill `no-negative-echo` 做完整门禁。

## 验证

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

危险车控用例按 `android/DEVICE_TEST_PLAN.md` 在真机人工执行，不在 CI 模拟。

## 范围边界

- 本仓管理客户端与联调工具链；Platform 服务端、ninecli 上游与 BLE 研究材料在各自仓 / 工作项中承接。
- 外部上游（九号云端、ninecli、MapLibre / 高德瓦片）的行为变化先记工作项，不在本文件写运行时事实。
