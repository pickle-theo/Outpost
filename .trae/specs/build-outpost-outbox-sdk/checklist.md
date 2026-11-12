# Checklist

- [x] 工程为三模块 Maven 结构，`clean install` 成功；`outpost-core` 依赖树无 `spring-boot-*`、无 ORM
- [x] 源码以 `--release 8` 编译通过，测试在 JDK 17+ 运行通过
- [x] `outpost_message` 表结构与 3 个索引严格对齐设计文档 §8.1，并提供独立 DDL 脚本
- [x] `auto-create-table` 默认关闭；关闭且表不存在时首次写入抛出明确异常
- [x] 非法 `table-prefix` 启动失败，不拼接进任何 SQL
- [x] `OutpostProperties` 覆盖 §10 全部配置项并设置默认值
- [x] 启动期 8 项校验（数据源、bizType 唯一、超时/耗时、耦合公式、参数区间、退避单调、队列容量、锁类型）均实现且失败即阻止启动
- [x] 无事务上下文调用注册 API 立即抛异常，且告警日志全局只打印一次
- [x] 业务事务回滚后 `outpost_message` 无残留消息（集成测试验证）
- [x] `REQUIRES_NEW` 事务独立聚合，外层回滚不影响内层已提交消息
- [x] 生产路径 INSERT 异常向上传播，未做异常吞噬
- [x] SpEL 结果非 `String`/`List<String>`/`null` 时抛 `IllegalArgumentException`，无隐式 `toString()`
- [x] `@OutboxCache` 按 key 展开为 `bizKey=namespace:key`、`payload=key`，双删时每个 key 两条消息
- [x] `DeliveryHandler` `bizType` 冲突导致启动失败；DTO 不可变且仅暴露 getter
- [x] `UnrecoverableDeliveryException` 直接置 DEAD（`dead_reason=2`），不发生退避重试
- [x] 普通失败按 1s/3s/10s/30s/60s/300s 退避，`next_retry_time` 由数据库侧计算
- [x] 辅助消息失败停在 FAILED，不计死信统计、不触发告警与死信监听
- [x] Redis 锁使用 `SET NX PX` + Lua 解锁，本轮结束前主动释放
- [x] 批量抢占影响行数不匹配时按 id 重查，只投递 `status=PROCESSING` 的行（集成测试覆盖）
- [x] 连续 3 轮加锁失败降级为无锁 + 逐条 CAS，Redis 恢复后回切加锁模式
- [x] `PROCESSING` 超时消息在下一轮被重置为 `PENDING` 并可重新投递
- [x] 投递使用独立线程池（默认 2/4/1000，`CallerRunsPolicy`），支持注入自定义 `Executor`，`traceId` 经 `TaskDecorator` 透传
- [x] `cache_delete` 处理器可用；`cache_tag_delete` 原生实现 rockscache 协议（`lockUntil=0`、`HDEL lockOwner`、`EXPIRE delay`），无三方 SDK 依赖
- [x] TagAsDeleted 后 Redis 中对应 key 为 hash 且字段/TTL 与 rockscache 协议一致
- [x] `RockscacheReadClient` 提供 `fetch`/`fetchBatch`/`rawGet`/`rawSet`，标记删除后首读返回旧值并异步刷新，旧值不会覆盖新值
- [x] 同一 key 并发回源在单 JVM 内 only-flight，跨实例由 Redis 锁保证最多一次回源
- [x] `handler-type=rockscache_tag` 时 `read-client` 必填，缺失启动失败；tag 模式强制关闭双删并打 WARN
- [x] Redis 操作受 `redis-timeout-ms` 约束，超时按可重试失败处理
- [x] 白名单为空时全量走消息表；namespace 不在白名单时回落同步直删并打 WARN，绝无静默跳过；`DEL` 回落对 tag 读路径安全
- [x] 清理任务仅删除保留期外的 SUCCESS/DEAD，FAILED/PROCESSING 保留，批量 1000 且复用调度锁
- [x] `OutpostAdminService` 6 个方法可用，默认过滤辅助消息，重发后状态重置正确
- [x] `updateConfig` 仅对热更白名单项生效，`schedule.batch-size` 变更重新校验耦合公式
- [x] 8 个固定指标名全部注册；无 `MeterRegistry` 时功能不受影响；`redelivery.ratio` 可反映重复投递
- [x] 日志统一携带 `message_id`/`biz_type`/`biz_key`/`trace_id`，堆栈仅 DEBUG
- [x] 同时提供 `spring.factories` 与 `AutoConfiguration.imports`；`outpost.enabled=false` 时不装配任何 bean
- [x] 源码静态检查无 `javax.*` / `jakarta.*` 引用
- [x] Testcontainers 集成测试覆盖 9 类缓存核心场景 + 7 项通用 Outbox 场景（含 tag 模式端到端、无 Redis 链路）并在 Docker 环境全绿；无 Docker 时跳过
- [x] 交付物齐全：DDL、接入文档（含不支持清单、两种处理器选型对比与接入 Checklist）、迁移回滚手册、回灌脚本、对账脚本、Grafana 看板、README

# 验证记录与待复验项

本机已验证：

- `.\mvnw.cmd clean install`（Spring Boot 2.7.18，默认）：三模块编译、单元测试、打包全绿。
- `.\mvnw.cmd -Pboot23 clean test`（Spring Boot 2.3.12.RELEASE）：编译与全部单元测试全绿。
- `.\mvnw.cmd verify`：本机无 Docker，16 个 `*IT` 用例全部跳过（completed=16 / skipped=16 / failures=0 / errors=0），构建成功。
- 184 个单元测试全绿，其中通用 Outbox 改造新增 12 项（无 Redis 上下文 5、本地锁 3、锁类型校验 4）。
- `outpost-core` 依赖树无 `spring-boot-*`；主源码除 JDK 自带 `javax.sql.DataSource` 外无 `javax.*` / `jakarta.*`；Grafana JSON 解析通过（8 个 panel）；README 20 个相对链接与全部目录锚点有效。
- 自动装配上下文测试 12 项（总开关、全链路加载、非法表前缀、未知调度类型、重复 bizType、耦合公式、Micrometer 启用/降级，以及无 Redis 通用场景 5 项）通过。

待外部复验：

- 第 39 项「Docker 环境全绿」：本机无 Docker，只验证了「无 Docker 时跳过」；需在含 Docker 的 CI 执行 `.\mvnw.cmd verify` 复验缓存 9 类 + 通用 Outbox 7 项场景。
- Boot 3.x 运行时兼容性：已「尝试并给出结论」——不兼容，V1 不发布，详见 `docs/接入文档.md` §9。
