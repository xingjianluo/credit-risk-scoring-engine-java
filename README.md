# 可解释交易风险评分与可疑活动审查流程（本地可运行）

基于 Spring Boot 的内存实现：用内存账本（Ledger）保存交易流水、评分结论与拒绝记录，
用本地事件模拟器（EventSimulator）构造重复、乱序、并发提交与规则版本切换场景。
无需数据库，启动即用；结论可解释、可复现、可追溯。

## 1. 评分模型

### 1.1 多因子规则与账户历史基线

每条流水先做字段校验，通过后由 `ScoringEngine` 按**当前激活规则版本**逐因子打分。
每个因子独立判定，命中才贡献一个**非负固定分值**，总分是各因子贡献之和：

| 因子 | 输入信号 | v1 阈值 | v1 分值 |
| --- | --- | --- | --- |
| `AMOUNT_LARGE` 单笔大额 | amount | >= 50,000.00 | 20 |
| `RAPID_SUCCESSION` 短时连续交易 | 与同账户上一笔（事件时间更早）的间隔 | <= 60 秒 | 15 |
| `NEW_PAYEE` 陌生收款方 | payeeId 是否出现在更早的历史流水中 | 未出现即命中 | 10 |
| `HIGH_RISK_JURISDICTION` 高风险司法辖区 | jurisdiction | 命中 {XX, ZZ, OFAC} | 25 |
| `OFF_HOURS` 深夜时段 | eventTime 的 UTC 小时 | 落在 [00, 06) | 10 |
| `VELOCITY_AMOUNT` 滚动窗口累计突增 | 更早流水中最近 300 秒累计金额 | >= 100,000.00 | 20 |

- **单调性**：所有因子分值非负，任何风险因素新增或加强都不会让总分下降
  （见 `ScoringEngineTest.monotonic_moreRiskFactorsNeverLowerScore`）。
- **可解释**：`ScoreResult` 返回总分、风险等级、每个命中因子的分值与可读证据
  `evidence`（输入信号 + 实际阈值 + 命中说明）。
- **风险等级**：`0 <= score < 30` 为 LOW，`30..59` 为 MEDIUM，`>= 60` 为 HIGH；
  `score >= 50`（autoCaseThreshold）自动生成审查案件。
- **历史基线**：评分时只统计同账户 **eventTime 严格更早**的流水；评分时刻的基线
  流水 ID 列表快照写入 `ScoreResult.baselineTransactionIds`，保证乱序到达不会
  回溯改写已冻结的历史结论（见 §4）。

### 1.2 字段校验与可区分拒绝原因

问题流水不会被静默丢弃，而是落账为 `RejectedSubmission` 并返回可区分原因码：

| 拒绝码 | 触发条件 |
| --- | --- |
| `MISSING_TRANSACTION_ID` | 流水号缺失/空白 |
| `MISSING_ACCOUNT_ID` | 账户号缺失/空白 |
| `MISSING_AMOUNT` | 金额为 null |
| `NON_POSITIVE_AMOUNT` | 金额 <= 0（零、负数） |
| `MISSING_EVENT_TIME` | 事件时间为 null |
| `FUTURE_EVENT_TIME` | 事件时间晚于系统当前时间 |

## 2. 规则版本化与兼容规则

- `RuleStore` 保存全部规则版本；`RuleSet` 为不可变快照（阈值、分值、高风险名单）。
- 激活新版本后，**只有之后新到达的流水**按新版本评分；已存在的 `ScoreResult`
  永久引用其 `rulesVersion`，历史结论不变。
- **旧版本只读、永久可读**：版本号不可覆盖；`GET /api/rules/{version}` 可读取旧版本；
  `GET /api/transactions/{id}/replay` 用存档版本 + 存档基线快照逐因子重算，
  报告 `consistent=true` 表示历史结论可复现。
- 激活新版本时未提供的字段自动继承当前版本（小步调参无需全量字段）；
  非法定义（空版本号、非正阈值、负分值、`medium > high`、非法小时等）返回
  `INVALID_RULE_DEFINITION` 并附全部校验错误。

## 3. 审查状态机与权限

案件按可疑流水的 transactionId **幂等开案**，案件保存评分快照与规则版本。

```
OPEN      --CLAIM-->     IN_REVIEW        （ANALYST / SENIOR）
OPEN      --DISMISS-->   DISMISSED        （仅 SENIOR，必须填原因）
IN_REVIEW --ESCALATE-->  ESCALATED        （ANALYST / SENIOR）
IN_REVIEW --CLOSE-->     CLOSED           （必须填原因）
IN_REVIEW --DISMISS-->   DISMISSED        （仅 SENIOR，必须填原因）
ESCALATED --CLOSE-->     CLOSED           （仅 SENIOR，必须填原因）
ESCALATED --DISMISS-->   DISMISSED        （仅 SENIOR，必须填原因）
```

`CLOSED` / `DISMISSED` 为终态。每次迁移写入不可变审计记录（时间、前后状态、
动作、角色、操作人、原因）。可区分的错误码：

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `CASE_NOT_FOUND` | 404 | 案件不存在 |
| `TRANSACTION_NOT_FOUND` | 404 | 流水不存在/尚未处理 |
| `ILLEGAL_TRANSITION` | 409 | 当前状态不允许该动作（含终态迁移） |
| `FORBIDDEN_ROLE` | 403 | 角色无权执行该处置 |
| `REASON_REQUIRED` | 400 | CLOSE/DISMISS 未填原因 |
| `RULE_VERSION_NOT_FOUND` | 404 | 规则版本不存在 |
| `INVALID_RULE_DEFINITION` | 400 | 新版本定义非法/版本号重复 |

## 4. 重复、乱序与并发的一致性保证

`ScoringService.submit` 在单把服务锁内完成
**查重 → 校验 → 评分 → 入账（putIfAbsent）→ 自动开案**：

- **重复提交**：同一 transactionId 直接返回首次结论，响应带 `duplicate=true`，
  不二次计分；重复的非法流水同样幂等返回首次拒绝原因。
- **乱序到达**：评分只依赖事件时间更早的基线；先到流水的结论一经写入即冻结，
  后补到达的更早流水只影响**之后新评分**的流水。每条结论带基线 ID 快照，复现时
  严格按快照重建。
- **并发提交**：服务锁串行化 + Ledger/CaseStore 的 putIfAbsent 双保险，
  N 个线程提交同一流水恰有 1 次首次评分、N-1 次幂等返回、恰好 1 个案件
  （见 `ConcurrentScoringTest`，重复 5 轮）。

## 5. HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/transactions` | 提交一条流水（字段可缺失/非法，返回评分或拒绝原因） |
| `GET /api/transactions/scores` | 全部评分结论 |
| `GET /api/transactions/rejections` | 全部被拒绝流水及原因 |
| `GET /api/transactions/{id}/score` | 读取某条流水的存档结论 |
| `GET /api/transactions/{id}/replay` | 用存档版本/基线复现并比对 |
| `GET /api/rules/current` / `GET /api/rules` / `GET /api/rules/{version}` | 当前/全部/指定版本规则 |
| `POST /api/rules/activate` | 激活新版本（缺省字段继承当前版本） |
| `GET /api/cases` / `GET /api/cases/{id}` | 案件列表/详情（含状态流转审计） |
| `POST /api/cases/{id}/transitions` | 案件处置：action/role/actor/reason |
| `GET /api/scenarios/duplicates` | 场景：重复提交 |
| `GET /api/scenarios/out-of-order` | 场景：乱序到达 |
| `GET /api/scenarios/concurrent?threadsPerTransaction=8` | 场景：并发提交 |
| `GET /api/scenarios/version-switch` | 场景：规则版本切换 |
| `POST /api/scenarios/reset` | 清空账本/案件并恢复 v1（本地验证用） |

### 本地验证示例

```bash
mvn spring-boot:run

# 1) 提交一条可疑流水（深夜 + 大额 + 高风险辖区，会自动开案）
curl -s -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' -d '{
  "transactionId":"t-1","accountId":"a-1","amount":90000,"payeeId":"p-1",
  "jurisdiction":"xx","eventTime":"2026-09-20T03:00:00Z"}'

# 2) 重复提交：返回同结论且 duplicate=true
curl -s -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' -d '{
  "transactionId":"t-1","accountId":"a-1","amount":90000,"payeeId":"p-1",
  "jurisdiction":"xx","eventTime":"2026-09-20T03:00:00Z"}'

# 3) 非法金额：返回可区分拒绝原因 NON_POSITIVE_AMOUNT
curl -s -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' -d '{
  "transactionId":"t-bad","accountId":"a-1","amount":-5,"eventTime":"2026-09-20T03:00:00Z"}'

# 4) 历史复现
curl -s localhost:8080/api/transactions/t-1/replay

# 5) 激活更严格的 v2（大额阈值降到 1000，分值升到 30）
curl -s -X POST localhost:8080/api/rules/activate -H 'Content-Type: application/json' -d '{
  "version":"v2","amountLargeThreshold":1000,"amountLargePoints":30}'

# 6) 案件处置（先认领；升级后仅 SENIOR 可关闭且必须填原因）
curl -s -X POST localhost:8080/api/cases/CASE-000001/transitions -H 'Content-Type: application/json' -d '{
  "action":"CLAIM","role":"ANALYST","actor":"alice","reason":null}'

# 7) 一键场景验证（响应中 consistent=true 即通过）
curl -s 'localhost:8080/api/scenarios/concurrent?threadsPerTransaction=12'
curl -s localhost:8080/api/scenarios/version-switch
```

## 6. 测试与本地验证方法

```bash
mvn test
```

共 26 个测试，覆盖：

- `ScoringEngineTest`：六因子命中、贡献分解、**评分单调性**、证据文本；
- `TransactionValidatorTest`：六类字段问题对应**可区分拒绝码**；
- `ScoringFlowIntegrationTest`：重复提交只计一次、乱序结论冻结与复现、非法流水落账；
- `CaseStateMachineTest`：幂等开案、全部合法/非法迁移、角色权限、原因必填、终态保护；
- `ConcurrentScoringTest`：16 线程并发重复提交 + 10 笔×8 副本混合并发，多轮重复；
- `RuleVersionSwitchTest`：新版本只影响新流水、历史可复现、旧版本可读、版本不可覆盖；
- `EventSimulatorScenarioTest`：四个模拟器端到端场景全部 `consistent=true`。

测试与运行日志统一打印**输入信号、命中规则与判定依据**，例如：

```
[评分输入] txnId=t-1 amount=90000 jurisdiction=XX eventTime=... version=v1 历史基线笔数=0
[命中规则] txnId=t-1 factor=AMOUNT_LARGE points=20 依据=金额 90000 >= 大额阈值 50000.00
[判定依据] txnId=t-1 version=v1 总分=65 等级=HIGH (medium>=30, high>=60)
[自动开案] txnId=t-1 score=65 >= autoCaseThreshold=50
[幂等命中] txnId=t-1 返回首次评分结论 duplicate=true
[状态流转] caseId=CASE-000001 OPEN --CLAIM--> IN_REVIEW actor=alice(ANALYST)
```
