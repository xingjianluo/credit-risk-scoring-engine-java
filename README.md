# 可解释交易风险评分与可疑活动审查引擎（本地可运行）

基于 Spring Boot 3.4 / Java 21 的本地服务，使用**内存账本**与**本地事件模拟器**
处理交易流水，保证在**重复提交、乱序到达、并发提交**下评分与审查结论一致且全程可追溯。
无需数据库或外部中间件，`mvn test` 即可完整验证。

---

## 1. 评分模型（多因子规则 + 账户历史基线）

### 1.1 处理流水线

```
交易提交
  └─ 收集去重(transactionId) ── 同批全部合法候选先入内存账本
        └─ 按 eventTime 排序逐笔处理
              ├─ 合法性校验（失败：拒绝留痕，可区分原因码，不静默丢弃）
              ├─ 账户历史基线（只取 eventTime 严格更早的已入账流水）
              ├─ 多因子规则求值（当前激活的规则版本）
              ├─ 汇总非负贡献 → 版本阈值定级
              ├─ 结论落账（不可变，含基线快照、版本号、逐因子证据）
              └─ 达到 HIGH/CRITICAL 自动开案（按交易幂等，只开一案）
```

### 1.2 风险因子（每个因子贡献非负，评分对风险因素单调）

| 规则码 | 因子 | 触发条件（阈值随版本） | 默认分值 |
|---|---|---|---|
| `LARGE_AMOUNT` | 大额交易 | 金额 ≥ 版本大额阈值（v1=10000，v2=5000） | 20 |
| `NEW_COUNTERPARTY` | 新交易对手 | 对手不在账户历史对手集合（含首笔） | 15 |
| `HIGH_VELOCITY` | 短时高频 | 评分时刻前 10 分钟同账户笔数 ≥ 阈值（v1=3，v2=2） | 20 |
| `OFFSHORE` | 离岸高风险地区 | 对手国家/地区命中高风险集合 KY/PA/VG/KH/MM | v1=20，v2=25 |
| `NIGHT_TIME` | 夜间交易 | eventTime 落在 00:00–05:00 UTC | v1=10，v2=15 |
| `UNUSUAL_AMOUNT` | 金额偏离基线 | 历史 ≥2 笔且金额 ≥ 历史均值 × 倍数（v1=5，v2=3） | 25 |

- **单调性**：每个因子只加不减，风险因素从无到有只会让总分上升、等级不下降。
- **可解释性**：评分结果 `RiskScoreResult` 中保存每个命中因子的 `规则码 / 因子名 / 贡献分 / 证据文本`，
  以及评分所用的基线快照 `BaselineSnapshot`（历史笔数、均值、窗口计数、已知对手集合）。
- **定级阈值**（随版本）：MEDIUM ≥ 30；HIGH ≥ `highThreshold`；CRITICAL ≥ `criticalThreshold`。
  v1：60 / 85；v2：50 / 75。

### 1.3 账户历史基线与乱序一致性

基线只统计 **eventTime 严格早于当前流水**的同账户流水，并显式排除自身。
服务在评分前先把同批全部合法候选收入账本、再按 eventTime 排序逐笔评分，因此：

- 每笔流水看到的历史只由“事件时间”决定，**与到达顺序无关**；
- 顺序 / 乱序（确定性洗牌）/ 并发（CyclicBarrier 同时释放）三种到达方式，
  逐笔评分、拒绝原因、重复计数、开案数完全一致（见 `ArrivalConsistencyTest`）。

### 1.4 非法流水的可区分拒绝原因

| 原因码 | 含义 |
|---|---|
| `MISSING_TRANSACTION_ID` / `MISSING_ACCOUNT_ID` | 交易/账户 ID 缺失 |
| `MISSING_AMOUNT` / `NON_POSITIVE_AMOUNT` | 金额缺失 / 金额 ≤ 0 |
| `MISSING_CURRENCY` / `UNSUPPORTED_CURRENCY` | 币种缺失 / 不在 CNY,USD,EUR,HKD |
| `MISSING_EVENT_TIME` / `FUTURE_EVENT_TIME` | 时间缺失 / 超过当前时钟 5 分钟 |
| `MISSING_COUNTERPARTY` | 交易对手缺失 |

拒绝记录 `RejectionRecord`（原因码 + 明细 + 时间 + 指纹）持久化在内存账本中，
**绝不静默丢弃**；同一 transactionId 的重复非法提交返回同一拒绝记录。

---

## 2. 规则版本化管理与兼容规则

- 规则与阈值封装在不可变的 `RuleVersion`（版本号、生效时间、HIGH/CRITICAL 阈值、参数表、兼容版本列表）。
- 内置版本：
  - **v1**（2026-01-01）：大额 10000、高频窗口阈值 3、离岸 20、夜间 10、偏离 5 倍，HIGH/CRITICAL=60/85。
  - **v2**（2026-06-01，`compatibleWith=[v1]`）：大额 5000、高频阈值 2、离岸 25、夜间 15、偏离 3 倍，HIGH/CRITICAL=50/75。
  - 默认激活 **v2**。
- **只追加、不覆盖**：`publish` 拒绝重复版本号；`activate` 切换当前版本；旧版本永远可 `get` 读取。
- **历史不可变**：评分结论固定记录 `ruleVersion`；规则更新只影响之后的新流水，历史结论原样可读。
- **可复现回溯**：可用任意历史版本对流水 `recomputeWithVersion` 重算（不改动历史结论），
  基线相同、差异只来自规则版本（见 `RuleVersionSwitchTest`）。

**本轮扩展的兼容范围**：账户级归集与批量处置为纯增量能力——
历史评分结论与既有单笔案件的审计信息不被修改；原有单笔/模拟/版本管理入口与
规则版本（v1/v2）行为保持不变；既有 12 个用例全部不回归。

---

## 3. 可疑活动审查状态机

评分等级达到 **HIGH（含）以上**自动生成审查案件；同一交易至多一个案件
（`txToCase` 幂等表 + 每案件 synchronized 处置锁）。

```
                 START_INVESTIGATION (ANALYST)
   OPEN ───────────────────────────────────────► INVESTIGATING
     │                                              │
     │ RESOLVE (SENIOR)                             │ RESOLVE (SENIOR)
     ▼                                              ▼
   RESOLVED ◄───────────────────────────────────────┘
     │  REOPEN (SENIOR)
     ▼
   REOPENED ──START_INVESTIGATION(ANALYST)──► INVESTIGATING ──RESOLVE(SENIOR)──► RESOLVED

   任意状态: ASSIGN (ANALYST) 仅分派，状态不变（幂等）
```

- 非法迁移抛 `ILLEGAL_CASE_TRANSITION`（HTTP 409）；权限不足抛 `CASE_PERMISSION_DENIED`（HTTP 403），
  **两类拒绝原因可区分**（先校验权限再校验迁移）。
- 每次处置都追加不可变 `CaseEvent`（from/to/审查人/角色/备注/时间），全程可审计。
- 并发处置在案件对象上串行；被拒绝的处置不改状态、不留事件（仅返回可区分错误）。

---

## 4. 账户级可疑活动归集（运营集中跟进）

同一账户在**可配置时间窗口**内出现多笔 HIGH（含）以上流水时，除每笔流水照旧保留
独立评分结论与单笔案件外，引擎把这些流水**归集到该账户唯一的账户级审查案件**上，
方便运营以账户维度集中跟进。

- **触发规则**：以当前流水 `eventTime` 为右端点的滑动窗口
  （`app.aggregation.window`，默认 `PT30M`）内，同账户 HIGH+ 流水笔数达到
  `app.aggregation.threshold`（默认 `3`）时开账户级案件（`ACCT-CASE-xxxxxx`），
  并把窗口内全部高风险流水及其单笔案件关联上去；后续窗口内的新高风险流水继续追加关联。
- **幂等与一致性**：
  - 归集判定与关联按账户串行（每账户一把锁），命中与关联都按 `transactionId` 去重，
    **同批重复流水不重复归集、不重复开案**；
  - 归集只依赖已评分结论（eventTime + 等级），评分层保证同批按 eventTime 顺序评估，
    因此**顺序/乱序/并发三种到达方式下归集结果与案件计数一致**
    （`BatchReport.accountCasesOpened`，见 `AccountAggregationTest`）；
  - 并发提交同一账户多笔高风险流水，恰好开一个账户案件。
- **历史不可变**：归集只追加“账户案件 ↔ 流水/单笔案件”的关联关系与审计事件，
  **不修改任何历史评分结论与单笔案件的审计链**。

查询入口：`GET /api/account-cases`、`/api/account-cases/{caseId}`、
`/api/account-cases/by-account/{accountId}`。

---

## 5. 案件批量处置（同一账户一批案件统一推进）

`POST /api/cases/dispositions/batch` 对同一账户下的一批案件统一提交一个处置动作
（如统一推进到 INVESTIGATING 或 RESOLVED）：

```json
{"batchId":"B-1001","accountId":"A001","caseIds":["CASE-000001","CASE-000002"],
 "action":"RESOLVE","reviewer":"op-1","role":"SENIOR","note":"批量结案"}
```

- **幂等**：`batchId` 是批次幂等键；同一批次重复提交返回首次处理报告
  （`duplicate=true`），**不产生重复处置记录与重复审计事件**。
- **整批原子**：先对全部案件做存在性 / 账户归属 / 权限 / 状态迁移校验，
  **任一不满足则整批拒绝**（`BATCH_DISPOSITION_REJECTED`，纯权限问题 403、其余 409），
  响应逐条给出可区分原因码（`CASE_NOT_FOUND` / `ACCOUNT_MISMATCH` /
  `CASE_PERMISSION_DENIED` / `ILLEGAL_CASE_TRANSITION`），
  整批不做任何状态变更、不留处置事件；全部通过才统一应用。
- **并发等价串行**：批次键串行化幂等；案件按 caseId 排序依次加锁（无死锁），
  并发批次之间、批量与单笔处置之间的最终状态与审计链同某种串行执行一致。
- **规则复用**：迁移合法性与角色要求完全复用既有状态机，
  **原有合法流转路径与终态保护（RESOLVED 仅可 REOPEN）不变**；
  批量处置的审计事件携带 `batchId` 以便追溯。

---

## 6. 统一受理入口异常契约

同一笔有问题或字段不完整的流水，无论走**单笔提交、批量提交、本地模拟回放还是
并发批量**，得到的处理结果、可区分拒绝原因与留痕完全一致：

| 入口 | 契约 |
|---|---|
| `POST /api/risk/transactions` | 非法流水返回 200 + `accepted=false` + 拒绝原因码（不抛 500） |
| `POST /api/risk/transactions/batch` | 逐条返回结果；异常条目只影响自身，**不影响同批正常流水的评分与计数** |
| `POST /api/risk/simulate` | 三种到达方式共用同一管线；非法 `mode` 返回 400 `INVALID_ARRIVAL_MODE` |
| 任意入口 | 空流水 / 空 ID：拒绝留痕（按指纹幂等去重），**不静默丢弃、不抛系统级错误** |

框架级保护：JSON 无法解析 → 400 `MALFORMED_REQUEST_BODY`；请求缺字段 → 400 +
具体 errorCode；未预期错误 → 兜底的 500 `INTERNAL_ERROR`（只返回安全提示，堆栈记日志）。
---

## 7. 一致性与并发设计要点

| 场景 | 保证机制 |
|---|---|
| 重复提交 | transactionId 幂等；每 ID 常驻锁串行；重复返回 `duplicate=true` 的首次结论，不重复计分/开案 |
| 乱序到达 | 先收集全部候选再按 eventTime 评分；基线只依赖事件时间 |
| 并发提交 | 同交易锁 + `ConcurrentHashMap.putIfAbsent` 账本；案件 `computeIfAbsent` + 案件级处置锁 |
| 计数最终一致 | 唯一交易/接受/拒绝/重复/开案/账户归集案计数由幂等原语推导，三种到达方式断言相同 |
| 账户级归集 | 每账户一把锁；命中与关联按 transactionId 去重；只追加关联不改历史结论 |
| 批量处置 | batchId 幂等回放；先全量校验后统一应用（整批原子）；案件按 caseId 排序加锁 |
| 入口异常契约 | 所有入口共用同一校验-评分管线；输入异常映射 4xx + errorCode，兜底 500 不透明 |

---

## 8. HTTP 接口（本地）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/risk/transactions` | 提交单条流水（评分或拒绝原因，重复返回 duplicate） |
| POST | `/api/risk/transactions/batch` | 批量提交流水（逐条结果，异常条目不影响同批其它流水） |
| GET  | `/api/risk/transactions/{id}` | 查询首次结论（200 评分 / 422 拒绝原因 / 404 不存在） |
| GET  | `/api/risk/scores` · `/api/risk/rejections` | 全部评分 / 全部拒绝留痕 |
| POST | `/api/risk/simulate` | 三模式模拟：`{"runId":"r1","mode":"OUT_OF_ORDER","transactions":[...]}` |
| GET  | `/api/rules/versions` · `/versions/current` | 版本列表 / 当前版本 |
| POST | `/api/rules/versions` · `/versions/{v}/activate` | 发布版本 / 切换版本 |
| GET  | `/api/cases` · `/api/cases/{id}` · `/api/cases/{id}/events` | 案件查询与审计链 |
| POST | `/api/cases/dispositions` | 单笔案件处置（状态机校验权限与迁移） |
| POST | `/api/cases/dispositions/batch` | 批量处置（batchId 幂等、整批原子、逐条可区分原因） |
| GET  | `/api/account-cases` · `/api/account-cases/{id}` · `/api/account-cases/by-account/{accountId}` | 账户级归集案件查询 |

`server.port=0` 表示随机端口；日志中可见实际端口。

---

## 9. 本地验证方法

```bash
# 1) 全量单测（32 个，覆盖重复/乱序/并发/版本切换/状态机/拒绝原因/
#    账户级归集/批量处置/入口异常契约）
mvn test

# 2) 启动时自动跑一遍三模式演示（计数一致性打印在日志 [DEMO]/[SIM-END]）
mvn spring-boot:run -Dspring-boot.run.arguments="--app.demo.seed=true"

# 3) 正常启动（随机端口，可用 REST 手工调用）
mvn spring-boot:run
```

### 测试与日志

测试日志按要求打印**输入信号、命中规则与判定依据**，可在 surefire 输出中检索：

- `[UT-REJECT]`：非法流水输入信号与可区分拒绝码；
- `[UT-MONO]` / `[UT-EXPLAIN]`：风险场景、命中因子 `规则码+贡献`、总分/等级/版本阈值；
- `[UT-ARRIVE]` / `[SIM-END]`：三种到达方式的逐笔评分与接受/拒绝/重复/开案计数；
- `[UT-VERSION]`：版本切换前后结论、历史复读与跨版本复算；
- `[UT-CASE]` / `[UT-CASE-DENY]` / `[UT-CASE-CONC]`：状态流转审计链、可区分拒绝、并发处置统计；
- `[ACCT-CASE]` / `[UT-AGG]`：账户级归集开窗判定（窗口、笔数、阈值）、关联明细与一致性计数；
- `[BATCH-DISP]` / `[UT-BATCH]`：批量处置逐条迁移、整批拒绝原因、幂等回放；
- `[UT-ENTRY]` / `[UT-WEB]`：各入口对同一异常输入的拒绝原因、指纹与留痕一致性。

主要测试类：

| 测试类 | 覆盖内容 |
|---|---|
| `TransactionValidatorTest` | 9 类字段问题的可区分拒绝原因码、合法流水通过 |
| `RiskRuleEngineTest` | 因子逐项加入下总分单调不减、贡献非负且可解释、分值求和一致 |
| `ArrivalConsistencyTest` | 顺序/乱序/并发三模式计数与逐笔结论一致、重复不重复计分开案 |
| `RuleVersionSwitchTest` | 新版本只影响新流水、历史结论不变、旧版本可读且可复算 |
| `ReviewCaseFlowTest` | 状态机合法路径、非法迁移 vs 权限不足可区分、并发开案/处置一致 |
| `AccountAggregationTest` | 窗口归集开案、阈值与滑动窗口、重复/乱序/并发归集一致、历史结论不可变 |
| `BatchDispositionTest` | 批量处置整批原子、batchId 幂等、可区分失败原因、并发等价串行、终态保护 |
| `EntryPointConsistencyTest` | 同一异常流水跨入口结果/原因/留痕一致，异常输入不影响同批正常流水 |
| `EntryPointContractWebTest` | HTTP 层 400/404 契约、批量入口、模拟回放混合输入 |

> 集成测试通过 `FixedClockTestConfig` 使用固定时钟（2026-09-26T23:00Z），
> 避免“未来时间”校验依赖真实机器时钟，保证测试可复现。
