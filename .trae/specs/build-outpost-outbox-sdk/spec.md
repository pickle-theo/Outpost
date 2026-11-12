# Outpost 本地消息表 SDK V1.0 规格

## Why

业务侧「DB 事务提交成功、异步删除缓存失败」会留下永久脏缓存，现有自研 `cache_key_queue` 难以复用与观测。Outpost 以 Outbox Pattern 提供嵌入式 SDK，保证「业务事务提交成功」与「下游异步动作最终执行」之间的最终一致性，用一张 DB 表换可靠性，并在 V1.0 用缓存失效场景验证这笔交易是否划算。

本文档是 [本地消息表缓存一致性 SDK V1.0（MVP）详细设计文档](../../../docs/本地消息表缓存一致性%20SDK%20V1.0（MVP）详细设计文档.md) 的规格化呈现，冲突时以本文档与设计文档的显式偏差说明为准。

## What Changes

* 新建 Maven 多模块工程：`outpost-core`（发布）、`outpost-spring-boot-starter`（发布）、`outpost-sample`（不发布）
* 新增 `outpost_message` 表、3 个索引、自动建表开关与 DBA 建表脚本
* 新增生产路径：编程式 `OutpostTemplate`、`@OutboxMessage`、`@OutboxCache`，同库同事务批量写入
* 新增调度引擎：`OutpostScheduler` SPI + Redis 全局锁调度器 + 批量抢占 + 无锁降级
* 新增投递执行：`DeliveryHandler` SPI、只读 DTO、独立线程池、内置 `cache_delete` / `cache_tag_delete` 处理器
* 新增 rockscache tag-as-deleted 原生实现：Java 直接执行兼容 Lua，并提供配套 Java 读客户端（`RockscacheReadClient`），用本地消息表替代 DTM 保证 DB 与 TagAsDeleted 的一致性
* 新增状态机、指数退避、死信治理、辅助消息（延迟双删第二次）
* 新增清理任务、监控埋点、运维管理 Java API
* 新增 Testcontainers 集成测试套件与交付文档/脚本
* **BREAKING**：无（当前仓库仅有 `docs/`，属全新工程）

## Impact

* Affected specs：无既有 spec（首次创建）
* Affected code：仓库根目录新增 Maven 工程；不改动 `docs/` 下既有设计文档
* 外部依赖：MySQL 5.7+/8.0+、Redis（单机/哨兵/集群）、Spring Boot 2.3\~2.7、JDK 8 字节码

## 显式偏差说明（相对设计文档）

| 项                      | 设计文档                           | 本规格                                                                                    | 原因                                                                                      |
| ---------------------- | ------------------------------ | -------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| NESTED（savepoint）内消息撤销 | §3.5 要求回滚到 savepoint 时清理该作用域消息 | V1.0 不实现 savepoint 级撤销，改为接入约束：消息注册必须位于事务最外层；`REQUIRES_NEW` 独立作用域仍支持                    | 公开 API `TransactionSynchronization` 无法感知 savepoint 回滚，实现需代理 DataSource，违背极简原则；纳入「不支持清单」 |
| 分页返回类型                 | §11.3 `Page<T>`                | 使用自有 `OutpostPage<T>`                                                                  | 避免为分页引入 spring-data-commons                                                             |
| 批量删除缓存的 payload        | §5.6 支持 `DEL key1 key2 ...`    | 每个缓存 key 一条消息，`payload` 为单 key；handler 内部提供多 key 批量删除能力                                | 避免 payload 内 key 分隔符转义歧义，bizKey 语义也更清晰                                                  |
| 强一致读模式                 | rockscache 提供 `StrongConsistency` | V1.0 只实现弱一致 Fetch（返回可能过期的值 + 异步刷新），不实现强一致读                                          | 强一致读会阻塞等待回源，与本 SDK「低侵入、可接受的秒级脏窗口」定位不符；纳入不支持清单                                              |

## 关键论证：本地消息表替代 DTM，并原生实现 TagAsDeleted

**结论：可行，且比适配三方 SDK 更简单。** 设计文档 `rockscache_tag` 处理器不再走适配器，由 SDK 直接用 Redis Lua 原生实现。

1. **`TagAsDeleted` 本身极简。** rockscache 的删除动作就是一个作用于 Redis Hash 的 Lua 脚本，客户端侧没有任何复杂逻辑：

   ```lua
   redis.call('HSET', KEYS[1], 'lockUntil', 0)
   redis.call('HDEL', KEYS[1], 'lockOwner')
   redis.call('EXPIRE', KEYS[1], ARGV[1])
   ```

   缓存条目为 hash，字段 `value` / `lockUntil`（秒级时间戳）/ `lockOwner`（uuid）。标记删除后，下一次 `Fetch` 发现 `lockUntil` 已过期，抢占锁并异步回源，仅当 `lockOwner` 未被覆盖时才写回，从算法上消除了「删除之后到达的写回」竞态。

2. **Java 原生实现没有技术障碍。** 用 `StringRedisTemplate` + `DefaultRedisScript` 对齐上述协议即可，不需要 Go SDK，也不需要业务提供适配器。这正是本规格替换原「适配器桥接」方案的原因。

3. **DTM 方案与本 SDK 是同一模型的不同实现。**
   - DTM 的 `DoAndSubmitDB`（本地事务 + 2 阶段消息）= 本 SDK 的「同库同事务写消息表」；
   - DTM 的 `QueryPrepared` checkback = 本 SDK 的调度器扫表；
   - DTM 的 barrier 幂等屏障 = 本 SDK 的「handler 天然幂等」契约（`DEL` 与 `TagAsDeleted` 均幂等）。
   - 差异：本 SDK 不需要额外 DTM 服务与 barrier 表，但只支持同库同事务，重试/死信/清理由本地表治理，不提供跨服务分支事务。

4. **前提不能省：读路径必须使用 tag 语义。** rockscache 官方只有 Go 实现，Java 业务若用普通 `GET` 读，写侧写入 hash 会 `WRONGTYPE`。因此 SDK 同时提供 Java 读客户端（`read-client=builtin`），并允许 `external` 复用已有兼容实现；tag 模式下 `read-client` 必须显式声明，不允许静默假设。

5. **tag 模式下延迟双删应当关闭。** 锁 + owner 已从算法层解决写回竞态，双删只会让消息量翻倍而无收益，配置开启时启动告警并强制只注册一条消息。

## ADDED Requirements

### Requirement: 工程结构与构建基线

系统 SHALL 提供三模块 Maven 工程：`outpost-core`、`outpost-spring-boot-starter`、`outpost-sample`。

* `outpost-core` 仅依赖 `spring-tx`、SLF4J（及测试依赖），**不得**依赖 Spring Boot、ORM 或 `javax.*` / `jakarta.*`
* `outpost-spring-boot-starter` 依赖 Spring Boot AutoConfigure、Spring AOP、`spring-data-redis`（通过 `StringRedisTemplate` 同时兼容 Jedis / Lettuce）、Micrometer（optional）
* 源码以 `--release 8` 编译；测试可在 JDK 17+ 运行
* 持久层仅使用 `JdbcTemplate`，不引入任何 ORM 或代码生成
* 提供可复现的构建入口（Maven Wrapper 或工作区内固定版本 Maven 分发），`clean install` 可离线/在线复现

#### Scenario: 构建成功

* **WHEN** 在仓库根目录执行构建命令
* **THEN** 三个模块编译打包成功，`outpost-core` 的依赖树中不出现 `spring-boot-*`

#### Scenario: 无命名空间依赖

* **WHEN** 对 `outpost-core` 与 `outpost-spring-boot-starter` 的源码与依赖做静态检查
* **THEN** 不存在 `javax.` / `jakarta.` 引用（`javax.sql.DataSource` 除外，属 JDK）

### Requirement: 数据模型与 DDL

系统 SHALL 提供 `outpost_message` 表，列、默认值、注释与索引严格对齐设计文档 §8.1：

* 列：`id`、`biz_type`、`biz_key`、`payload`、`message_type`、`status`、`retry_count`、`max_retry`、`deliver_attempt`、`next_retry_time`、`version`、`error_msg`、`dead_reason`、`trace_id`、`create_time`、`update_time`
* 索引：`idx_status_retry_time(status, next_retry_time)`、`idx_status_create_time(status, create_time)`、`idx_biz_type_key(biz_type, biz_key)`
* `outpost.auto-create-table` 默认 `false`；为 `true` 时以幂等方式建表（MySQL 方言）
* `outpost.table-prefix` 必须先通过白名单校验（字母、数字、下划线，长度受限）再拼接表名
* 提供交由 DBA 审核执行的建表 SQL 脚本

#### Scenario: 非法表前缀

* **WHEN** 配置 `outpost.table-prefix` 含空格、分号、反引号等非法字符
* **THEN** 应用启动失败并给出明确错误信息，不执行任何 SQL

#### Scenario: 默认不自动建表

* **WHEN** `auto-create-table` 为默认值 `false` 且表不存在
* **THEN** 启动不报错，首次写入消息时抛出明确异常提示执行 DDL

### Requirement: 配置绑定与启动期强制校验

系统 SHALL 绑定设计文档 §10 的全部配置项并提供默认值，同时实现 §4.5 启动期校验：

| 校验项                                                        | 不满足时 |
| ---------------------------------------------------------- | ---- |
| 数据源唯一可解析（`@Primary` 或 `OutpostDataSourceProvider`）         | 拒绝启动 |
| `DeliveryHandler#getBizType()` 全局唯一                        | 拒绝启动 |
| `processingTimeout ≥ 10 × handler 最大执行耗时`（无申明时用默认上界）       | 拒绝启动 |
| `batchSize × 单条耗时 / 核心线程数 < processingTimeout × 0.5`       | 拒绝启动 |
| `fixedDelay ≥ 100ms`、`batchSize ∈ [1,1000]`、`maxRetry ≥ 1` | 拒绝启动 |
| `backoff-steps` 严格单调递增                                     | 拒绝启动 |
| `queue-capacity ≥ 10 × batch-size`                         | 拒绝启动 |

校验失败 MUST 抛出异常终止启动，不得仅打印 WARN。

#### Scenario: 批量参数违反耦合公式

* **WHEN** `batch-size=1000`、`processing-timeout-seconds=300`、`core-size=4` 且单条耗时默认上界 1s
* **THEN** 启动失败，错误信息包含公式、当前取值与不满足项

### Requirement: 生产路径与事务绑定

系统 SHALL 提供 `OutpostTemplate` 编程式 API：

```java
<T> T executeInTransaction(Supplier<T> businessAction);
void registerMessage(String bizType, String bizKey, String payload);
void registerDelayedMessage(String bizType, String bizKey, String payload, long delayMills);
void registerCacheInvalidate(String namespace, List<String> cacheKeys);
```

行为约束：

* 注册前 MUST 校验 `TransactionSynchronizationManager.isSynchronizationActive() && hasResource(dataSource)`；不满足立即抛异常并提示「当前方法无事务上下文」
* 校验失败的告警日志通过 `AtomicBoolean` 全局只打印一次，但每次调用都抛异常
* 消息按事务同步作用域聚合：`beforeCommit` 阶段批量 INSERT（`status=PENDING`），`afterCompletion` **无条件**清理聚合器；`REQUIRES_NEW` 各自独立
* 单事务消息数超过 `batch-register-threshold`（默认 200）时分批插入并打 WARN
* `payload` 超过 `max-payload-bytes`（默认 65536）时抛异常，不截断
* 生产路径的 INSERT 异常 MUST 向上传播，不做吞噬
* 业务事务回滚时消息 MUST 不落库

#### Scenario: 无事务注册

* **WHEN** 在无 `@Transactional` 的方法中调用 `registerMessage`
* **THEN** 立即抛出异常，且数据库中不新增任何消息

#### Scenario: 业务回滚

* **WHEN** 事务内注册消息后业务代码抛出运行时异常
* **THEN** 业务数据与 `outpost_message` 行一同回滚，表内无残留消息

#### Scenario: 同库不同 DataSource 实例

* **WHEN** 业务事务绑定的 `DataSource` 与 SDK 解析到的不是同一 bean 实例
* **THEN** 注册阶段因 `hasResource` 校验失败抛异常，不产生「消息独立提交、业务回滚」的静默错误

### Requirement: 注解式 API

系统 SHALL 提供 `@OutboxMessage` 与 `@OutboxCache` 注解及对应切面。

* `@OutboxMessage`：`bizType`、`bizKey`（SpEL）、`payload`（SpEL）、`delayMills`
* `@OutboxCache`：`namespace`、`key`（SpEL）、`keys`（SpEL，`List<String>`）、`doubleDeleteDelayMills`（`-1` 表示用全局配置）
* SpEL 求值结果 MUST 是 `String`、`List<String>` 或 `null`，否则注册阶段抛 `IllegalArgumentException`；内核不得做隐式 `toString()`
* `@OutboxCache` 按 key 展开：每个 key 生成一条消息，`bizKey=namespace:cacheKey`、`payload=cacheKey`，`bizType` 由 `handler-type` 决定（`cache_delete` / `cache_tag_delete`）
* 开启延迟双删时每个 key 注册两条消息（见辅助消息需求）
* 切面 MUST 在业务方法成功返回后注册，方法抛异常时不注册

#### Scenario: payload 表达式返回对象

* **WHEN** `@OutboxMessage(payload = "#user")` 且 `#user` 为对象
* **THEN** 注册阶段抛 `IllegalArgumentException`，消息不入库

#### Scenario: 业务方法异常

* **WHEN** 被 `@OutboxCache` 标注的方法抛出异常
* **THEN** 不注册任何消息

### Requirement: 投递处理器 SPI

系统 SHALL 定义：

```java
public interface DeliveryHandler {
    String getBizType();
    void deliver(OutboxDeliveryMessage message) throws Exception;
}
```

* `OutboxDeliveryMessage` 为不可变只读 DTO：`id`、`bizType`、`bizKey`、`payload`、`deliverAttempt`、`traceId`、`createTime`，仅 getter
* DTO 演进规则：只允许新增字段与 getter，不得删除或修改既有字段语义
* `bizType` 重复注册 MUST 导致启动失败
* 不支持同 `bizType` 链式处理器
* 业务注册为 Spring Bean 后自动被发现

#### Scenario: 业务类型重复

* **WHEN** 两个 `DeliveryHandler` 返回相同 `bizType`
* **THEN** 应用启动失败并指出冲突的 bean 名称

### Requirement: 异常语义与重试

系统 SHALL 按以下规则处理投递结果：

| handler 结果                               | 内核行为                                                                                              |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 正常返回                                     | `status=SUCCESS`，同批次可合并批量回写，`version+1`                                                           |
| 抛 `UnrecoverableDeliveryException`       | 直接 `status=DEAD`、`dead_reason=2`，跳过退避，触发死信监听器                                                     |
| 抛其他 `Exception` / `Throwable`（含 `Error`） | `retry_count+1`，按退避阶梯计算 `next_retry_time`，`status=FAILED`；达到 `max_retry` 则 `DEAD`、`dead_reason=1` |
| 失败且为辅助消息（`message_type=1`）               | `status=FAILED` 并停在此终态，不置 DEAD、不计死信统计、不触发死信监听与告警                                                  |

* 退避阶梯默认 `1s → 3s → 10s → 30s → 60s → 300s`，超阶梯取最大值
* `next_retry_time` MUST 在数据库侧计算（如 `DATE_ADD(NOW(), INTERVAL ? SECOND)`）
* 支持全局 `max-retry-count` 与单条消息 `max_retry`
* `error_msg` 存 `Class: message` 摘要并截断至 1024 字符，完整堆栈进日志

#### Scenario: 不可恢复异常

* **WHEN** handler 抛 `UnrecoverableDeliveryException`
* **THEN** 该消息一次重试都不发生，直接 `DEAD` 且 `dead_reason=2`

#### Scenario: 辅助消息失败

* **WHEN** 延迟双删第二条消息（`max_retry=1`）首次投递抛普通异常
* **THEN** 消息为 `FAILED`，不被再次扫到，不触发死信告警与统计

### Requirement: 调度引擎

系统 SHALL 定义 `OutpostScheduler` SPI（`getType` / `start` / `stop`），由 `outpost.schedule.type` 选择实现；配置值无匹配实现时启动失败。

V1.0 Redis 锁调度器：

* 固定间隔 `fixedDelay`（默认 5000ms）触发，不使用 cron
* 加锁 `SET key value NX PX 30000`；解锁用 Lua 校验 value 后 `DEL`；本轮结束前主动释放
* 单轮顺序：重置超时挂死消息 → `SELECT ... WHERE status IN (0,3) AND retry_count < max_retry AND next_retry_time <= NOW() ORDER BY next_retry_time LIMIT ?` → 批量抢占 `UPDATE ... WHERE id IN (...)` → 校验影响行数 → 提交线程池 → 释放锁
* 影响行数不等于 id 数时 MUST 按 id 重查，仅投递 `status=PROCESSING` 的行
* 连续 `degrade-after-failures`（默认 3）轮加锁失败后自动降级为无锁模式：多实例并行扫表 + 逐条 CAS 抢占；Redis 恢复后回到加锁模式
* 锁不续期、不可重入、不使用 Redlock

#### Scenario: 抢占影响行数不匹配

* **WHEN** 批量 UPDATE 影响行数少于查询出的 id 数
* **THEN** 重新按 id 查询，只有 `status=PROCESSING` 的消息被投递，`PENDING` 的不投递

#### Scenario: 超时重置

* **WHEN** 存在 `status=PROCESSING` 且 `update_time` 早于 `NOW() - processing-timeout` 的消息
* **THEN** 本轮开始前被重置为 `PENDING` 并可被重新抢占

#### Scenario: Redis 不可用降级

* **WHEN** 连续 3 轮获取 Redis 锁失败
* **THEN** 调度器进入无锁模式，逐条 CAS 抢占消息，且不产生并发重复投递（仅允许幂等允许多次执行）

### Requirement: 投递执行线程池

系统 SHALL 使用独立线程池执行投递，与业务线程隔离：

* 默认核心 2、最大 4、队列 1000、拒绝策略 `CallerRunsPolicy`
* 支持业务注入自定义 `Executor`
* 通过自定义 `TaskDecorator` 把注册时的 MDC `traceId` 传入投递线程并写入日志
* 线程池异常 MUST 被捕获并转成失败回写，不得让调度线程中断

#### Scenario: 队列满回压

* **WHEN** 队列已满且调度线程执行任务
* **THEN** 本轮调度被阻塞但消息最终被处理，且启动期已校验 `queue-capacity ≥ 10 × batch-size`

### Requirement: 内置处理器（原生 TagAsDeleted）与延迟双删

系统 SHALL 原生实现两个缓存处理器，**不依赖任何三方缓存 SDK、不需要业务提供适配器**：

* `cache_delete`：执行 `DEL`，天然幂等
* `cache_tag_delete`：执行与 rockscache 协议严格一致的 Lua 脚本（`HSET lockUntil 0`、`HDEL lockOwner`、`EXPIRE delay`），天然幂等；多 key 逐 key 执行（Redis Cluster 下不跨 slot），可用 pipeline 降低往返
* tag 模式参数：`delivery-cache.tag.delay-seconds`（默认 10）、`tag.lock-expire-millis`（3000）、`tag.lock-sleep-millis`（100）、`tag.empty-expire-seconds`（60）、`tag.random-expire-adjustment`（0.1）
* Redis 操作 MUST 受 `delivery-cache.redis-timeout-ms`（默认 1000）约束：单次操作在超时内返回或判定为可重试失败
* `handler-type=rockscache_tag` 时 `double-delete` MUST 自动关闭并打 WARN：锁 + owner 已覆盖写回竞态，双删无收益
* `handler-type=delete` 且开启 `double-delete` 时，每个 key 注册两条消息：`delay=0, message_type=0` 与 `delay=配置值(默认 1000), message_type=1, max_retry=1`

#### Scenario: 原生 tag 标记

* **WHEN** `cache_tag_delete` 投递成功
* **THEN** 对应 key 为 Redis Hash：`lockUntil=0`、无 `lockOwner` 字段、TTL ≈ `tag.delay-seconds`

#### Scenario: tag 模式下配置了双删

* **WHEN** `handler-type=rockscache_tag` 且 `double-delete.enabled=true`
* **THEN** 启动日志输出 WARN，每个 key 只注册一条消息

#### Scenario: delete 模式双删

* **WHEN** 通过 `@OutboxCache` 触发一次缓存失效且 `handler-type=delete`、双删开启
* **THEN** 写入两条 `cache_delete` 消息，第二条 `message_type=1`、`max_retry=1`、`next_retry_time ≈ now + delay`

### Requirement: Rockscache 兼容读路径

tag 语义的最终一致性依赖读侧同样使用 hash 语义。系统 SHALL 在 starter 中提供可选启用的 Java 读客户端 `RockscacheReadClient`，与写侧共享同一套常量与 Lua：

* `fetch(key, expireSeconds, loader)`：弱一致 Fetch —— 命中且需要刷新时**立即返回旧值**并异步回源；未命中则抢锁、同步回源、按 owner 校验写回
* `fetchBatch(keys, expireSeconds, batchLoader)`：批量版本，语义同上
* `rawGet` / `rawSet`
* 语义对齐 rockscache：`lockUntil` 秒级时间戳、`lockOwner` uuid、每 JVM singleflight、`EmptyExpire` 空值保护、`RandomExpireAdjustment` 防雪崩、TTL 计算 `expire - delay - random`
* `delivery-cache.tag.read-client` ∈ `builtin`（内置读客户端）/ `external`（业务已有兼容读客户端，如 Go 服务或自研 Java 版，SDK 不装配读侧）
* `handler-type=rockscache_tag` 时 `read-client` MUST 显式配置，缺失则启动失败：读侧若是普通 `GET`，写侧 hash 会 `WRONGTYPE`，属于必须显式确认的前提
* 不支持 `StrongConsistency` 强一致读模式（V1.0）

#### Scenario: 标记删除后首次读取

* **WHEN** `TagAsDeleted` 投递完成后业务调用 `fetch`
* **THEN** 若存在旧值则立即返回旧值，同时异步回源刷新；刷新写回受 `lockOwner` 校验，旧值不会覆盖新值

#### Scenario: 并发回源只有一次落库

* **WHEN** 同一 key 的缓存被标记删除后大量并发 `fetch`
* **THEN** 同一 JVM 内 only-flight，跨实例由 Redis 锁保证最多一次回源，其余请求直接返回旧值

#### Scenario: 未声明读侧即启用 tag 模式

* **WHEN** `handler-type=rockscache_tag` 但未配置 `read-client`
* **THEN** 启动失败，错误信息要求确认读路径使用 rockscache 兼容的 hash 语义


### Requirement: 缓存灰度与回落

系统 SHALL 通过 `CacheInvalidator` 路由缓存失效：

```java
public interface CacheInvalidator {
    void invalidate(String namespace, List<String> keys);
}
```

* `enabled-namespaces` 为空：全部 namespace 走 `OutboxCacheInvalidator`（消息表，可靠）
* 非空：白名单内走消息表；白名单外走 `DirectCacheInvalidator`，在 `afterCommit` 同步 `DEL`，并打 WARN
* 回落 `DEL` 对 tag 读路径同样安全：整 key 删除后读侧按 miss 回源，不会出现类型冲突
* 灰度开关 MUST NOT 表现为「静默不删」

#### Scenario: namespace 不在白名单

* **WHEN** `enabled-namespaces=[order]` 且调用 `namespace=user`
* **THEN** 不写消息表，事务提交后直接同步删除缓存，并输出 WARN 日志说明已回落同步直删

### Requirement: 状态机与死信治理

系统 SHALL 保持 5 态状态机：`PENDING(0)`、`PROCESSING(1)`、`SUCCESS(2)`、`FAILED(3)`、`DEAD(4)`。

* 所有状态流转带 `version` 乐观锁
* 「终态」通过 `retry_count < max_retry` 扫描条件表达，不新增状态
* 死信触发后逐个 `try-catch` 调用 `DeadMessageListener#onDead(message, reason, cause)`，单个监听器失败不影响其他监听器与消息状态
* 内置按 `dead_reason` 分流日志：`1` → 运维通道 logger，`2` → 业务通道 logger
* 死信保留 `dead-retain-days`（默认 30 天）

#### Scenario: 监听器异常隔离

* **WHEN** 第一个 `DeadMessageListener` 抛异常
* **THEN** 后续监听器仍被调用，消息保持 `DEAD`

### Requirement: 数据清理

系统 SHALL 提供清理任务：

* 清理范围：`SUCCESS` + `DEAD`；`FAILED` / `PROCESSING` 不清理
* SUCCESS 保留 3 天、DEAD 保留 30 天
* 按主键范围分批 DELETE，批大小 1000
* 每天执行一次（`fixedDelay`），复用调度锁避免多实例重复清理
* `outpost.cleanup.enabled=false` 可关闭

#### Scenario: 保留期外清理

* **WHEN** 清理任务运行时存在 3 天前的 `SUCCESS` 与 30 天前的 `DEAD`
* **THEN** 二者被删除；同期的 `FAILED` 与 `PROCESSING` 保留

### Requirement: 运维管理接口

系统 SHALL 提供 `OutpostAdminService`（仅 Java API，不内置 HTTP 端点）：

```java
OutpostPage<OutboxDeliveryMessage> queryMessages(MessageQuery query);
OutpostPage<OutboxDeliveryMessage> queryDeadMessages(DeadMessageQuery query);
void resendMessage(Long messageId);
void batchResendMessages(List<Long> messageIds);
OutpostStats getStats();
void updateConfig(OutpostConfig config);
```

* 所有查询与统计默认过滤 `message_type=1` 辅助消息
* 重发后：`status=PENDING`、`retry_count=0`、`next_retry_time=NOW()`、清空 `error_msg` / `dead_reason`、`version+1`
* `updateConfig` 仅支持热更项：`retry.backoff-steps`、`delivery-cache.enabled-namespaces`、`cleanup.enabled`、`delivery-cache.double-delete.*`、`schedule.batch-size`（受耦合公式校验）；其余项不得生效并给出明确提示

#### Scenario: 重发死信

* **WHEN** 对一条 `DEAD` 消息调用 `resendMessage`
* **THEN** 该消息回到 `PENDING`，下一轮调度重新投递

### Requirement: 监控与日志

系统 SHALL 暴露以下 Micrometer 指标（名称固定）：

`outpost.message.produce.total`、`outpost.message.delivery.success.total`、`outpost.message.delivery.fail.total`、`outpost.message.dead.gauge`、`outpost.message.redelivery.ratio`、`outpost.schedule.duration.ms`、`outpost.delivery.duration.ms`、`outpost.retry.count.distribution`

* `MeterRegistry` 不存在时降级为无埋点，不影响功能
* `redelivery.ratio` = `deliver_attempt > retry_count + 1` 的消息数 / 总消息数
* 日志统一携带 `message_id`、`biz_type`、`biz_key`、`trace_id`；生产路径异常不吞、不打日志后继续

#### Scenario: 重复投递可观测

* **WHEN** 发生超时重置导致的重复投递
* **THEN** `outpost.message.redelivery.ratio` 与 `deliver_attempt` 列能够反映该现象

### Requirement: 自动装配与兼容性

系统 SHALL：

* 同时提供 `META-INF/spring.factories`（Boot 2.x）与 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（Boot 3.x）
* `outpost.enabled=false` 时不装配任何 Outpost bean，且不影响应用启动
* 编译目标为 Spring Boot 2.3.x 可用 API；在 2.3.x 与 2.7.x 下上下文加载验证通过；Boot 3.x 做兼容性验证
* 不自行创建数据源，默认复用 `@Primary` `DataSource`，多数据源通过 `OutpostDataSourceProvider` 显式提供

#### Scenario: 关闭总开关

* **WHEN** `outpost.enabled=false`
* **THEN** 调度器、处理器、管理接口均不生效，应用正常启动

### Requirement: 示例工程与集成测试

系统 SHALL 提供 `outpost-sample`，包含可运行示例与 Testcontainers（MySQL 8 + Redis 7）集成测试，至少覆盖：

1. 事务回滚：业务回滚后消息不落库
2. 并发抢占：多调度实例/无锁模式下不重复投递同一消息
3. 宕机恢复：`PROCESSING` 超时重置后可被重新投递
4. 超时重置与 `deliver_attempt` 观测
5. 批量抢占影响行数不匹配分支
6. 延迟双删：两条消息的 `message_type`、时序与失败终态
7. 死信：重试耗尽与不可恢复两条路径 + 重发
8. 清理：保留期边界

Docker 不可用时集成测试 MUST 跳过而非失败（surefire/failsafe 配置）。

#### Scenario: 集成测试全绿

* **WHEN** 在有 Docker 的环境执行集成测试
* **THEN** 上述 8 类用例全部通过

### Requirement: 交付文档与运维脚本

系统 SHALL 交付：

* 供 DBA 审核的建表 SQL 脚本
* 接入文档：能力边界、不支持清单、接入 Checklist（首条高亮 `delete` 处理器的击穿风险，并强制要求缓存 key 设置 TTL）
* 迁移与回滚操作手册：三阶段影子验证、Drain 流程、紧急回灌步骤
* 回灌脚本与影子对账脚本示例
* Grafana 看板 JSON（对应 8 个指标）
* 仓库 README：构建、模块说明、最小接入示例

#### Scenario: 文档覆盖不支持清单

* **WHEN** 检查接入文档
* **THEN** 明确包含：强一致性不支持、本地缓存失效不支持、跨库事务不支持、消息顺序性不支持、通用幂等不支持、NESTED savepoint 内消息撤销不支持、rockscache `StrongConsistency` 强一致读不支持；并包含 `delete` 与 `rockscache_tag` 两种模式的选型对比与读路径改造要求
