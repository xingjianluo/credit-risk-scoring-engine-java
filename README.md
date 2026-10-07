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

### 1.5 受理入口统一异常契约（新增）

同一笔有问题或字段不完整的流水，无论走哪个受理入口，处理结果、可区分拒绝原因
与留痕完全一致：

| 入口 | 异常输入行为 |
|---|---|
| `POST /api/risk/transactions`（单笔） | 返回 200 + `accepted=false` 的拒绝结论（含原因码），留痕落账 |
| `POST /api/risk/transactions/batch`（批量） | 逐条返回结论；异常条目拒绝留痕，**不影响同批正常流水评分与计数** |
| `POST /api/risk/simulate`（本地模拟回放） | 三种到达模式下同一流水的拒绝原因与留痕和单笔/批量一致 |
| 并发批量（多线程同时提交） | 与串行提交的最终评分、拒绝留痕、计数完全一致 |

- 任何入口都不会因输入异常抛出系统级错误：空流水、空 ID、缺账户、坏 JSON、
  非法枚举参数分别映射为 `MISSING_TRANSACTION_ID` / `MISSING_ACCOUNT_ID` /
  `MALFORMED_REQUEST_BODY`(400) / `INVALID_ARGUMENT`(400) 等**可区分**结果；
  未预期异常也有兜底 `INTERNAL_ERROR` 错误码，绝不返回不透明失败。
- 无交易 ID 的拒绝无法按 ID 索引，转入账本匿名留痕列表，同样**不静默丢弃**。

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

### 3.1 账户级可疑活动归集（新增）

同一账户在一个**可配置时间窗口**内出现多笔达到 HIGH（含）以上的流水时，
除每笔流水保留独立评分结论与单笔案件外，还会把它们归集到同一个
**账户维度审查案件**（`ACCT-CASE-xxxxxx`），方便运营集中跟进。

- **窗口模型**：纪元对齐固定时间桶（`risk.account-case.window`，默认 `PT1H`）。
  时间桶只由 `eventTime` 推导，归集结果与到达顺序（顺序/乱序/并发）无关。
- **幂等归集**：`txToCase` 幂等表保证同一交易只归集一次；同批重复流水
  不重复归集、不重复开案；每账户一把锁串行化归集，并发提交下案件计数与串行一致。
- **历史不可变**：归集只追加新账户案件/新关联，从不修改历史评分结论、
  单笔案件及其审计事件链（`AccountCaseAggregationTest` 逐笔断言）。
- 账户案件复用同一状态机（OPEN→INVESTIGATING→RESOLVED/REOPENED），
  处置入口 `/api/account-cases/dispositions`，审计事件链同样不可变。

### 3.2 案件批量处置（新增）

`POST /api/cases/dispositions/batch` 支持运营对同一账户下的一批案件一次性提交处置
（如统一推进到 INVESTIGATING / RESOLVED）：

- **批次幂等**：`batchId` 为幂等键，重复提交返回 `duplicate=true` 的首次结果，
  不产生重复处置记录与重复审计事件。
- **all-or-nothing**：先在所有目标案件锁内逐项校验（权限 + 迁移合法性 + 案件存在性），
  任一不满足则整批拒绝，抛 `BATCH_DISPOSITION_REJECTED`（全部权限不足时 HTTP 403，
  否则 409），响应携带**逐项可区分**失败原因
  （`CASE_PERMISSION_DENIED` / `ILLEGAL_CASE_TRANSITION` / `CASE_NOT_FOUND`），
  且整批无任何状态变更与审计事件，杜绝半成功。
- **并发等价串行**：批次互斥锁 + 案件按 caseId 固定顺序加锁（无死锁），
  并发批量处置的最终状态与审计记录和串行执行一致；
  原有合法流转路径与终态保护（RESOLVED 只能 REOPEN）完全保留。

---

## 4. 一致性与并发设计要点

| 场景 | 保证机制 |
|---|---|
| 重复提交 | transactionId 幂等；每 ID 常驻锁串行；重复返回 `duplicate=true` 的首次结论，不重复计分/开案 |
| 乱序到达 | 先收集全部候选再按 eventTime 评分；基线只依赖事件时间 |
| 并发提交 | 同交易锁 + `ConcurrentHashMap.putIfAbsent` 账本；案件 `computeIfAbsent` + 案件级处置锁 |
| 计数最终一致 | 唯一交易/接受/拒绝/重复/开案计数由幂等原语推导，三种到达方式断言相同 |
| 账户级归集 | 纪元对齐时间桶（只依赖 eventTime）+ `txToCase` 幂等表 + 每账户归集锁 |
| 批量处置 | batchId 幂等表 + 批次互斥锁 + 案件按 caseId 定序加锁；先校验后生效（all-or-nothing） |
| 入口异常契约 | 所有入口共用同一校验与管线；坏 JSON/非法参数映射可区分 400；兜底 `INTERNAL_ERROR` |

---

## 5. HTTP 接口（本地）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/risk/transactions` | 提交单条流水（评分或 422 拒绝，重复返回 duplicate） |
| POST | `/api/risk/transactions/batch` | 批量提交：逐条结论，异常条目拒绝留痕、不影响同批正常流水 |
| GET  | `/api/risk/transactions/{id}` | 查询首次结论（200 评分 / 422 拒绝原因 / 404 不存在） |
| GET  | `/api/risk/scores` · `/api/risk/rejections` | 全部评分 / 全部拒绝留痕 |
| POST | `/api/risk/simulate` | 三模式模拟：`{"runId":"r1","mode":"OUT_OF_ORDER","transactions":[...]}` |
| GET  | `/api/rules/versions` · `/versions/current` | 版本列表 / 当前版本 |
| POST | `/api/rules/versions` · `/versions/{v}/activate` | 发布版本 / 切换版本 |
| GET  | `/api/cases` · `/api/cases/{id}` · `/api/cases/{id}/events` | 案件查询与审计链 |
| POST | `/api/cases/dispositions` | 案件处置（状态机校验权限与迁移） |
| POST | `/api/cases/dispositions/batch` | 批量处置：`{"batchId":"B1","items":[{...}]}`，幂等 + all-or-nothing |
| GET  | `/api/account-cases` · `/{id}` · `/{id}/events` | 账户级归集案件查询与审计链 |
| GET  | `/api/account-cases/by-transaction/{txId}` | 按交易查归集案件（未归集 404） |
| POST | `/api/account-cases/dispositions` | 账户案件处置（复用同一状态机） |

`server.port=0` 表示随机端口；日志中可见实际端口。

---

## 6. 本地验证方法

```bash
# 1) 全量单测（27 个，覆盖重复/乱序/并发/版本切换/状态机/拒绝原因/
#    账户级归集/批量处置/入口异常契约）
mvn test

# 2) 启动时自动跑一遍三模式演示（计数一致性打印在日志 [DEMO]/[SIM-END]）
mvn spring-boot:run -Dspring-boot.run.arguments="--app.demo.seed=true"

# 3) 正常启动（随机端口，可用 REST 手工调用）
mvn spring-boot:run

# 4) 调整账户归集窗口（默认 1 小时，ISO-8601 时长）
mvn spring-boot:run -Dspring-boot.run.arguments="--risk.account-case.window=PT30M"
```

### 测试与日志

测试日志按要求打印**输入信号、命中规则与判定依据**，可在 surefire 输出中检索：

- `[UT-REJECT]`：非法流水输入信号与可区分拒绝码；
- `[UT-MONO]` / `[UT-EXPLAIN]`：风险场景、命中因子 `规则码+贡献`、总分/等级/版本阈值；
- `[UT-ARRIVE]` / `[SIM-END]`：三种到达方式的逐笔评分与接受/拒绝/重复/开案/账户归集计数；
- `[UT-VERSION]`：版本切换前后结论、历史复读与跨版本复算；
- `[UT-CASE]` / `[UT-CASE-DENY]` / `[UT-CASE-CONC]`：状态流转审计链、可区分拒绝、并发处置统计；
- `[UT-AGG]` / `[ACCT-CASE]`：账户级归集开案、归集明细、跨到达方式一致性；
- `[UT-BATCH]` / `[CASE-BATCH]`：批量处置生效/整批拒绝原因、幂等与并发竞争统计；
- `[UT-ENTRY]`：各受理入口对同一异常输入的拒绝原因一致性与留痕核对。

主要测试类：

| 测试类 | 覆盖内容 |
|---|---|
| `TransactionValidatorTest` | 9 类字段问题的可区分拒绝原因码、合法流水通过 |
| `RiskRuleEngineTest` | 因子逐项加入下总分单调不减、贡献非负且可解释、分值求和一致 |
| `ArrivalConsistencyTest` | 顺序/乱序/并发三模式计数与逐笔结论一致、重复不重复计分开案 |
| `RuleVersionSwitchTest` | 新版本只影响新流水、历史结论不变、旧版本可读且可复算 |
| `ReviewCaseFlowTest` | 状态机合法路径、非法迁移 vs 权限不足可区分、并发开案/处置一致 |
| `AccountCaseAggregationTest` | 账户级归集：窗口分桶、重复/乱序/并发一致、历史结论与单笔案件审计不变 |
| `BatchDispositionTest` | 批量处置：批次幂等、all-or-nothing、失败原因可区分、并发等价串行、终态保护 |
| `EntryPointConsistencyTest` | 入口异常契约：单笔/批量/模拟/并发/HTTP 下同一异常输入结果一致，无系统级错误 |

> 集成测试通过 `FixedClockTestConfig` 使用固定时钟（2026-09-26T23:00Z），
> 避免“未来时间”校验依赖真实机器时钟，保证测试可复现。

---

## 7. 兼容范围

本轮新增能力（账户级归集、批量处置、入口异常契约统一）均为**纯追加**：

- **历史结论不变**：既有评分结论、拒绝留痕、单笔案件及其审计事件链不被新逻辑修改；
  归集只追加账户维度案件，批量处置只通过既有状态机产生新审计事件。
- **原有入口不变**：`POST /api/risk/transactions`、`/api/risk/simulate`、
  `/api/cases/dispositions` 等既有接口的入参、出参与语义保持兼容，
  仅新增批量/账户案件接口与更明确的错误码。
- **规则版本仍然可用**：v1/v2 规则版本、版本切换与跨版本复算不受影响；
  归集阈值复用既有开案阈值（HIGH 含以上），与规则版本解耦。
