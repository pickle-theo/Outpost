# Tasks

- [x] Task 1: 搭建 Maven 多模块工程与构建基线
  - [x] SubTask 1.1: 创建 parent `pom.xml`（`outpost-core` / `outpost-spring-boot-starter` / `outpost-sample`），统一版本、UTF-8、`--release 8`
  - [x] SubTask 1.2: 落地可复现构建入口（Maven Wrapper `mvnw` + `.mvn/wrapper`，或工作区内固定版本 Maven），验证 `clean install -DskipTests`
  - [x] SubTask 1.3: 约束依赖边界：core 仅 `spring-tx`/`slf4j`；starter 引入 `spring-boot-autoconfigure`、`spring-aop`、`aspectjweaver`、`spring-data-redis`、`micrometer-core`（optional）
  - [x] SubTask 1.4: 建立包结构与 `outpost-sample` 骨架，确保模块间无循环依赖

- [x] Task 2: 数据模型、DDL 与自动建表
  - [x] SubTask 2.1: 编写 `outpost_message` 建表 SQL（列、默认值、注释、3 个索引），放入 starter 资源与交付脚本目录
  - [x] SubTask 2.2: 实现表名前缀白名单校验与表名解析
  - [x] SubTask 2.3: 实现 `auto-create-table`（默认 false）幂等建表，表不存在且未开启时首次写入抛出明确异常
  - [x] SubTask 2.4: 实现行对象 `OutboxMessageRecord` 与 `RowMapper`

- [x] Task 3: 配置绑定与启动期校验
  - [x] SubTask 3.1: 实现 `OutpostProperties`，覆盖设计文档 §10 全部配置项与默认值
  - [x] SubTask 3.2: 实现数据源解析（`@Primary` / `OutpostDataSourceProvider`）与 `bizType` 唯一校验
  - [x] SubTask 3.3: 实现参数校验：`fixedDelay`、`batchSize`、`maxRetry`、退避单调性、队列容量（`≥10×batchSize`）、§4.4 耦合公式
  - [x] SubTask 3.4: 校验失败抛异常阻止启动，补充覆盖各失败分支的单元测试

- [x] Task 4: 持久层 Repository（JdbcTemplate）
  - [x] SubTask 4.1: `insertBatch`（同事务内批量写入，`status=PENDING`）
  - [x] SubTask 4.2: `selectPending`、`claimBatch`（批量 UPDATE）、`claimOne`（降级 CAS）、`selectByIds`
  - [x] SubTask 4.3: `markSuccessBatch`、`markFailed`、`markDead`、`resetTimeoutProcessing`
  - [x] SubTask 4.4: 运维查询、按 id 分页、重发状态重置、统计聚合、分批清理删除
  - [x] SubTask 4.5: Repository 单元/集成测试（SQL 正确性、影响行数、`version` 递增）

- [x] Task 5: 生产路径与编程式 API
  - [x] SubTask 5.1: 事务上下文校验（`isSynchronizationActive` + `hasResource`），告警用 `AtomicBoolean` 只打一次、每次调用都抛异常
  - [x] SubTask 5.2: 聚合器：以 `TransactionSynchronization` 为作用域绑定 `ThreadLocal`，`beforeCommit` 批量 INSERT，`afterCompletion` 无条件清理
  - [x] SubTask 5.3: `OutpostTemplateImpl` 实现 4 个方法；payload 大小、类型、单事务条数阈值（分批 + WARN）
  - [x] SubTask 5.4: 测试：业务回滚消息不落库、`REQUIRES_NEW` 独立作用域、INSERT 异常向上传播、无事务抛异常

- [x] Task 6: 注解式 API
  - [x] SubTask 6.1: 定义 `@OutboxMessage`、`@OutboxCache` 注解
  - [x] SubTask 6.2: 实现 SpEL 求值器，结果必须为 `String` / `List<String>` / `null`，否则抛 `IllegalArgumentException`
  - [x] SubTask 6.3: 实现切面：业务方法成功返回后注册；`@OutboxCache` 按 key 展开，`bizKey=namespace:key`、`payload=key`
  - [x] SubTask 6.4: 测试：对象类型 payload、方法抛异常不注册、空 key 列表、`keys` 与 `key` 互斥校验

- [x] Task 7: 投递 SPI 与死信监听
  - [x] SubTask 7.1: 定义 `DeliveryHandler`、不可变 `OutboxDeliveryMessage`、`UnrecoverableDeliveryException`
  - [x] SubTask 7.2: 实现处理器注册表与 `bizType` 唯一性校验（冲突启动失败）
  - [x] SubTask 7.3: 定义 `DeadMessageListener`，内置按 `dead_reason` 分流日志（运维/业务通道），监听器异常逐个隔离
  - [x] SubTask 7.4: 测试：重复 `bizType`、DTO 不可变、监听器异常不影响状态

- [x] Task 8: 重试策略与状态回写
  - [x] SubTask 8.1: 实现退避策略（阶梯、超阶梯取最大、DB 侧时间计算）
  - [x] SubTask 8.2: 实现结果处理器三分支：成功批量回写、不可恢复直接 DEAD、普通失败按退避置 FAILED/DEAD
  - [x] SubTask 8.3: 辅助消息（`message_type=1`）失败置 FAILED 终态，不计死信统计、不告警
  - [x] SubTask 8.4: 测试：退避序列、`dead_reason` 取值、`error_msg` 截断、辅助消息终态

- [x] Task 9: 调度器 SPI 与 Redis 锁调度器
  - [x] SubTask 9.1: 定义 `OutpostScheduler` SPI，按 `outpost.schedule.type` 选择实现，无匹配则启动失败
  - [x] SubTask 9.2: 实现 Redis 锁（`SET NX PX` + Lua 解锁 + 本轮结束主动释放）
  - [x] SubTask 9.3: 实现单轮流程（超时重置 → 查询 → 批量抢占 → 影响行数不匹配重查 → 提交 → 释放锁）
  - [x] SubTask 9.4: 实现降级：连续 N 轮加锁失败转无锁 + 逐条 CAS，Redis 恢复后回切
  - [x] SubTask 9.5: 集成测试：批量抢占不匹配、并发不重复、超时重置、降级路径

- [x] Task 10: 投递执行线程池
  - [x] SubTask 10.1: 线程池装配（默认 2/4/1000，`CallerRunsPolicy`），支持注入自定义 `Executor`
  - [x] SubTask 10.2: `TaskDecorator` 传递 MDC `traceId`
  - [x] SubTask 10.3: 投递循环与异常兜底（`Error` 转异常，保证一定回写状态）
  - [x] SubTask 10.4: 测试：traceId 透传、异常不中断调度、自定义执行器生效

- [x] Task 11: 缓存场景、灰度与原生 TagAsDeleted
  - [x] SubTask 11.1: 定义 `CacheInvalidator`，实现 `DirectCacheInvalidator`（`afterCommit` 同步删除 + WARN）
  - [x] SubTask 11.2: 实现 `OutboxCacheInvalidator` 与 `enabled-namespaces` 白名单路由（空=全开）
  - [x] SubTask 11.3: 实现 `cache_delete` 处理器（幂等 `DEL`，受 `redis-timeout-ms` 约束）
  - [x] SubTask 11.4: 实现 rockscache 协议常量与 Lua 脚本（`TagAsDeleted` 单 key、批量逐 key，Cluster 安全），实现原生 `cache_tag_delete` 处理器，无任何三方 SDK 依赖
  - [x] SubTask 11.5: 实现 `RockscacheReadClient`：`fetch`/`fetchBatch`/`rawGet`/`rawSet`，含锁 + owner 校验、每 JVM singleflight、`EmptyExpire`、`RandomExpireAdjustment`、命中后异步刷新
  - [x] SubTask 11.6: 实现 tag 模式配置与启动校验（`read-client` 必填、tag 模式强制关闭双删并 WARN、tag 延迟/锁参数）
  - [x] SubTask 11.7: 测试：TagAsDeleted 后 hash 字段与 TTL 正确、标记后首读返回旧值并异步刷新、并发回源 only-flight、旧值不覆盖新值、白名单回落、Redis 超时转可重试失败

- [x] Task 12: 清理任务
  - [x] SubTask 12.1: 实现每日 `fixedDelay` 清理，复用调度锁
  - [x] SubTask 12.2: 实现按主键范围分批删除（SUCCESS 3 天 / DEAD 30 天，批 1000），支持 `cleanup.enabled=false`
  - [x] SubTask 12.3: 测试：保留期边界、FAILED/PROCESSING 不被清理、关闭开关后不执行

- [x] Task 13: 监控埋点与日志规范
  - [x] SubTask 13.1: 接入 `MeterRegistry`（可缺省），注册 8 个固定名称指标
  - [x] SubTask 13.2: 在注册、抢占、投递、死信路径埋点，包含 `redelivery.ratio` 计算
  - [x] SubTask 13.3: 统一日志字段（`message_id`、`biz_type`、`biz_key`、`trace_id`），堆栈仅 DEBUG
  - [x] SubTask 13.4: 测试：`SimpleMeterRegistry` 断言计数、无 `MeterRegistry` 时功能正常

- [x] Task 14: 运维管理接口
  - [x] SubTask 14.1: 定义 `OutpostAdminService`、查询对象、`OutpostPage`、`OutpostStats`
  - [x] SubTask 14.2: 实现查询、死信按 `dead_reason` 筛选、单条/批量重发、统计（默认过滤辅助消息）
  - [x] SubTask 14.3: 实现 `updateConfig` 仅热更白名单项，批量大小变更触发耦合公式校验
  - [x] SubTask 14.4: 测试：重发后状态重置、辅助消息被过滤、非法热更项不生效

- [x] Task 15: 自动装配与兼容性
  - [x] SubTask 15.1: 编写 core/starter 配置类与条件装配（总开关、调度类型、处理器类型）
  - [x] SubTask 15.2: 提供 `spring.factories` 与 `AutoConfiguration.imports` 两份装配声明
  - [x] SubTask 15.3: 上下文加载测试：Boot 2.3.x / 2.7.x；`outpost.enabled=false` 时不装配任何 bean
  - [x] SubTask 15.4: 静态检查无 `javax.` / `jakarta.` 引用；尝试 Boot 3.x 兼容验证（不发布）

- [x] Task 16: 示例工程与 Testcontainers 集成测试
  - [x] SubTask 16.1: `outpost-sample` 可运行示例（MySQL + Redis 容器，含 `@OutboxCache` 与 tag 读路径用法）
  - [x] SubTask 16.2: 集成测试覆盖 9 类核心正确性场景（事务回滚、并发抢占、宕机恢复、超时重置、批量抢占不匹配、双删、死信重发、清理、tag 模式端到端：TagAsDeleted → 返回旧值 → 回源刷新 → 新值可见）
  - [x] SubTask 16.3: surefire/failsafe 配置，Docker 不可用时跳过集成测试而非失败

- [x] Task 17: 交付文档与运维脚本
  - [x] SubTask 17.1: 交付 DDL 脚本（供 DBA 审核）
  - [x] SubTask 17.2: 接入文档：能力边界、不支持清单、`delete` vs `rockscache_tag` 选型对比与读路径改造要求、接入 Checklist（击穿风险 + 缓存 key 必须设 TTL）
  - [x] SubTask 17.3: 迁移与回滚手册：三阶段影子验证、Drain 流程、紧急回灌
  - [x] SubTask 17.4: 回灌脚本与影子对账脚本示例
  - [x] SubTask 17.5: Grafana 看板 JSON（对应 8 个指标）
  - [x] SubTask 17.6: 仓库 README：模块说明、构建命令、最小接入示例

# Task Dependencies

- Task 2、3 依赖 Task 1
- Task 4 依赖 Task 2
- Task 5 依赖 Task 3、4
- Task 6 依赖 Task 5
- Task 7 依赖 Task 3（可与 Task 5 并行）
- Task 8 依赖 Task 4、7
- Task 10 依赖 Task 7（可与 Task 5 并行）
- Task 9 依赖 Task 4、8、10
- Task 11 依赖 Task 5、6、7、8
- Task 12 依赖 Task 4、9
- Task 13 依赖 Task 5、9、10
- Task 14 依赖 Task 4、8、12
- Task 15 依赖 Task 9、11、12、13、14
- Task 16 依赖 Task 11、15
- Task 17 依赖 Task 2、11、14（文档可与 Task 16 并行）

# 验证记录

- `.\mvnw.cmd clean install`（默认 Spring Boot 2.7.18）：三模块编译、单元测试、打包通过。
- `.\mvnw.cmd verify`：加入 failsafe 集成测试；本机无 Docker，16 个 `*IT` 用例由 `@Testcontainers(disabledWithoutDocker = true)` 全部跳过，构建成功（failsafe-summary：completed=16 / skipped=16 / failures=0 / errors=0）。**有 Docker 的 CI 需复验缓存 9 类 + 通用 Outbox 7 项场景全绿**。
- `.\mvnw.cmd -Pboot23 clean test`（Spring Boot 2.3.12.RELEASE）：编译与全部单元测试通过，覆盖 Boot 2.3 / 2.7 两个目标版本的上下文加载。
- Boot 3.x 兼容性验证结论（V1 不发布）：已提供 `AutoConfiguration.imports` 声明；运行时不兼容，原因是 Spring Framework 6 移除 `JdbcTemplate` 旧重载且 Boot 3 要求 JDK 17 + Jakarta，详见 `docs/接入文档.md` §9。
- 静态检查：主源码除 JDK 自带 `javax.sql.DataSource` 外无 `javax.*` / `jakarta.*` 引用；Grafana JSON 解析通过（8 个 panel）；`outpost-core` 依赖树无 `spring-boot-*`。
- 相对既有内核的额外补齐：`OutpostAdminRepository` 运维/清理 SQL、`OutpostAdminService`、`MessageCleanupTask` / `OutpostCleanupScheduler`、`MicrometerOutpostMetrics`（8 指标）、`OutpostAutoConfiguration` 全套自动装配、`OutboxTableGuard` 首次写入 DDL 提示、`@OutboxCache.doubleDeleteDelayMills` 注解级延迟生效、`DynamicBackoffPolicy` 热更生效。

# 通用 Outbox 改造（P0~P2）

- P0 定位：`README.md` 改为「通用 Transactional Outbox SDK，内置缓存一致性处理器」并新增「通用业务消息」章节；`docs/接入文档.md` 新增 §1.1 通用业务消息（API、SPI、幂等契约、慢调用耗时上界、锁选择、V1 全局配额限制）。按规格约束未改动 `docs/` 下既有详细设计文档。
- P1 解耦：新增 `outpost.schedule.lock-type`（`redis` 默认 / `local`）与启动期校验；新增 `LocalDistributedLock`（CAS 标志，避免可重入导致锁失效）、`OutpostRedisRequirement` / `OutpostRedisRequiredCondition`；`StringRedisTemplate` 装配与启动校验改为按「缓存启用或 redis 锁且无自定义锁」判定；`DistributedLock` 按锁类型装配。纯通用场景可完全不依赖 Redis。
- P2 示例与验证：`outpost-sample` 新增 `OrderPaidHandler`、`@OutboxMessage` 示例与 demo runner 通用消息；新增 `OutpostGenericIT`（仅 MySQL 容器、无 Redis），覆盖事务回滚、注解注册、延迟消息、不可恢复死信重发、并发 CAS；`spring-boot-maven-plugin` repackage 增加 `classifier=exec`，避免可执行 jar 破坏 failsafe classpath。
- 验证：184 个单元测试全绿（其中新增 12 项：通用上下文 5、本地锁 3、锁类型校验 4）；16 个 IT 无 Docker 时全部跳过；`-Pboot23 clean test` 通过；`outpost-core` 依赖树仍无 `spring-boot-*`；README 20 个相对链接与全部目录锚点有效。
- 未实施（后续阶段）：P3 多 bizType 独立配额/线程池隔离/指标维度；P4 Boot 3 兼容与发布治理。
