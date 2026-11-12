# Outpost

**通用 Transactional Outbox SDK（Java / Spring Boot）· 内置缓存一致性处理器**

[![Version](https://img.shields.io/badge/version-1.0.0--SNAPSHOT-yellow)](#兼容性) [![Java](https://img.shields.io/badge/Java-8%2B-orange)](#兼容性) [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.3%20--%202.7-brightgreen)](#兼容性) [![Maven](https://img.shields.io/badge/Maven-Wrapper%203.9.9-blue)](#开发)

> **一句话说明**：把「下游异步动作」和「业务写库」放进同一个数据库事务，事务提交后由后台调度器持续重试投递，保证业务提交成功后下游动作最终执行。

Outpost 是**最终一致性**方案，不追求提交后立即一致。业务事务提交成功时，注册在同一事务里的消息一定已落库；调度器按退避策略重试，直到成功或进入死信，死信可通过运维 API 重发。

**缓存失效是内置的第一种场景，不是能力边界**：注册、调度、抢占、退避、死信、清理、运维、指标全链路都与 `bizType` 无关。任何幂等的异步动作（发 MQ、HTTP 回调、索引同步、通知推送、延迟任务等）都可以实现 `DeliveryHandler` 接入，详见[通用业务消息](#通用业务消息)。

---

## 目录

- [为什么需要 Outpost](#为什么需要-outpost)
- [特性](#特性)
- [模块](#模块)
- [快速开始](#快速开始)
- [通用业务消息](#通用业务消息)
- [配置](#配置)
- [运维与观测](#运维与观测)
- [兼容性](#兼容性)
- [文档与脚本](#文档与脚本)
- [开发](#开发)
- [关键约束与限制](#关键约束与限制)
- [许可证](#许可证)

<a id="为什么需要-outpost"></a>

## 为什么需要 Outpost

「先提交 DB，再执行异步动作」在动作失败时通常只留下一条日志：缓存里的旧值、没发出去的事件、漏掉的回调都可能永久丢失。Outpost 用一张 `outpost_message` 表把下游动作变成**可持久化、可重试、可观测**的消息：

```text
业务事务  ──►  业务表 UPDATE
          └──►  outpost_message INSERT（同连接、同事务、beforeCommit）
                     │
                     ▼
              后台调度器（分布式锁 / CAS 抢占）
                     │
                     ▼
              DeliveryHandler（幂等：DEL / TagAsDeleted / MQ / HTTP / …）
                     │
          成功 → SUCCESS / 失败 → 退避重试 / 耗尽 → DEAD（可重发）
```

缓存一致性是这一模型最典型的应用：「DB 提交成功、异步删缓存失败」留下的永久脏缓存由此被消除。

<a id="特性"></a>

## 特性

- **通用 Outbox**：`bizType` 由业务定义，`DeliveryHandler` SPI 承接任意幂等异步动作；SDK 只负责可靠投递。
- **同库同事务**：消息与业务数据共用同一个 `DataSource` 与事务，业务回滚则消息不落库。
- **双 API**：编程式 `OutpostTemplate`（含延迟消息）与注解式 `@OutboxMessage` / `@OutboxCache`（SpEL）。
- **可靠投递**：分布式锁调度、批量抢占、逐条 CAS 降级、`PROCESSING` 超时重置、阶梯退避、死信治理。
- **内置缓存处理器**：`cache_delete`（DEL + 可选延迟双删）与 `cache_tag_delete`（原生 rockscache Lua，无三方 SDK 依赖），可整体关闭。
- **可运维**：查询/重发/统计/热更的 `OutpostAdminService`、每日清理任务、8 个固定 Micrometer 指标。
- **可关闭**：`outpost.enabled=false` 时不装配任何 Bean；纯通用场景也可关闭全部缓存依赖。

<a id="模块"></a>

## 模块

| 模块 | 职责 | 发布 |
| --- | --- | --- |
| [`outpost-core`](outpost-core/pom.xml) | 通用 Outbox 内核：事务聚合、状态机、重试、调度 SPI、运维 SPI。仅依赖 `spring-tx` + SLF4J，不含 Spring Boot / ORM | 是 |
| [`outpost-spring-boot-starter`](outpost-spring-boot-starter/pom.xml) | 自动装配、JdbcTemplate 仓储、AOP、可插拔分布式锁、内置缓存处理器、rockscache 读客户端、Micrometer 指标 | 是 |
| [`outpost-sample`](outpost-sample/pom.xml) | 缓存与通用业务消息两套可运行示例 + Testcontainers 集成测试 | 否 |

<a id="快速开始"></a>

## 快速开始

### 前置条件

| 组件 | 要求 |
| --- | --- |
| JDK | 源码以 `--release 8` 编译；测试建议在 JDK 17+ 运行 |
| 构建 | 仓库自带 Maven Wrapper（Maven 3.9.9），无需预装 Maven |
| 数据库 | MySQL 5.7+ / 8.0+，消息表与业务表**同库** |
| Redis | 缓存处理器或 `lock-type=redis` 时需要；纯通用场景可完全不需要 |

### 1. 建表

由 DBA 审核并执行 [`scripts/ddl/outpost_message.sql`](scripts/ddl/outpost_message.sql)；本地调试可临时设置 `outpost.auto-create-table=true` 让 SDK 幂等建表。默认关闭自动建表，表缺失时会在**首次写入**给出明确提示。

### 2. 引入依赖

```xml
<dependency>
    <groupId>com.outpost</groupId>
    <artifactId>outpost-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

应用需提供 `DataSource`（多数据源时标记 `@Primary` 或提供 `OutpostDataSourceProvider`）与 `PlatformTransactionManager`；启用缓存处理器或使用 `lock-type=redis` 时还需要 `StringRedisTemplate`。

### 3. 配置

```yaml
outpost:
  enabled: true
  auto-create-table: false        # 生产建议 false，由 DBA 执行 DDL
  delivery-cache:
    handler-type: cache_delete    # 或 cache_tag_delete
```

### 4. 注册消息（内置缓存场景）

编程式：

```java
@Transactional
public void updateOrder(String orderId) {
    jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?", "PAID", orderId);
    outpostTemplate.registerCacheInvalidate("order", Collections.singletonList("order:" + orderId));
}
```

注解式：

```java
@Transactional
@OutboxCache(namespace = "order", key = "'order:' + #orderId")
public void updateOrder(String orderId) {
    // 业务 SQL
}
```

<a id="通用业务消息"></a>

## 通用业务消息

缓存一致性只是内置场景。任意「事务提交后需要最终执行」的幂等动作都可以接入：

| 场景 | `bizType` 示例 | `DeliveryHandler` 动作 |
| --- | --- | --- |
| MQ / 领域事件 | `order_paid` | 发送订单已支付事件（用 `bizKey` 做业务侧去重） |
| 外部回调 / Webhook | `webhook_notify` | 调用第三方 HTTP 接口 |
| 搜索 / 索引同步 | `search_index` | 重建 ES 文档或物化视图 |
| 通知推送 | `sms_notify` | 短信 / 邮件 / App 推送 |
| 延迟任务 | `order_timeout` | 配合 `registerDelayedMessage` 定时触发 |

三步接入：

**1. 事务内注册**（`payload` 为字符串，序列化由业务负责，默认上限 65536 字节）：

```java
outpostTemplate.executeInTransaction(() -> {
    jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?", "PAID", orderId);
    outpostTemplate.registerMessage("order_paid", orderId, "{\"orderId\":\"" + orderId + "\"}");
    return null;
});
```

也可用注解：

```java
@Transactional
@OutboxMessage(bizType = "order_paid", bizKey = "#orderId", payload = "#orderId")
public void pay(String orderId) {
    // 业务 SQL
}
```

**2. 实现处理器并注册为 Bean**（`bizType` 全局唯一，不得与 `cache_delete` / `cache_tag_delete` 冲突）：

```java
@Bean
public DeliveryHandler orderPaidHandler() {
    return new DeliveryHandler() {
        @Override
        public String getBizType() {
            return "order_paid";
        }

        @Override
        public void deliver(OutboxDeliveryMessage message) throws Exception {
            // 必须幂等；抛 UnrecoverableDeliveryException 直接进死信
            eventPublisher.publish(message.getBizKey(), message.getPayload());
        }

        @Override
        public long getMaxExecutionMillis() {
            return 3000L; // 慢调用必须声明，参与启动期超时与耦合公式校验
        }
    };
}
```

**3. 纯通用场景可完全去掉 Redis**：

```yaml
outpost:
  enabled: true
  delivery-cache:
    enabled: false       # 关闭内置缓存处理器与灰度回落
  schedule:
    lock-type: local     # 单实例部署；多实例必须用 redis
```

| `schedule.lock-type` | 实现 | 适用 |
| --- | --- | --- |
| `redis`（默认） | `RedisDistributedLock`（`SET NX PX` + Lua 解锁） | 多实例生产部署，需要 `StringRedisTemplate` |
| `local` | `LocalDistributedLock`（进程内 `ReentrantLock`） | 单实例 / 本地调试 / 纯通用场景，无需 Redis |

> [!IMPORTANT]
> `local` 锁只在单 JVM 内有效。多副本部署必须使用 `redis`，否则各实例会重复扫表；CAS 抢占能保证单条消息只被一个实例抢占，但重复投递仍可能由超时重置触发，**handler 幂等是硬要求**。

完整示例见 [`outpost-sample`](outpost-sample/src/main/java/com/outpost/sample)，详细契约见[接入文档 §1.1](docs/接入文档.md)。

<a id="配置"></a>

## 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `outpost.enabled` | `true` | `false` 时不装配任何 Outpost Bean |
| `outpost.table-prefix` | `outpost_` | 白名单 `^[A-Za-z0-9_]{0,32}$`，非法值启动失败 |
| `outpost.auto-create-table` | `false` | 关闭且表缺失时，首次写入抛明确异常 |
| `outpost.delivery-cache.enabled` | `true` | `false` 时关闭全部内置缓存处理器与缓存便捷层 |
| `outpost.schedule.type` | `redis-lock` | 调度器类型，无匹配 `OutpostScheduler` 实现时启动失败 |
| `outpost.schedule.lock-type` | `redis` | `redis` / `local`，决定 `DistributedLock` 实现 |
| `outpost.schedule.batch-size` | `100` | 区间 `[1, 1000]`，受热更耦合公式校验 |
| `outpost.schedule.processing-timeout-seconds` | `300` | 需 ≥ 10 × handler 最大执行耗时 |
| `outpost.retry.backoff-steps` | `1,3,10,30,60,300` | 严格单调递增，否则启动失败 |
| `outpost.cleanup.enabled` | `true` | SUCCESS 保留 3 天、DEAD 保留 30 天 |

启动期强制校验（数据源唯一、bizType 唯一、超时/耗时、耦合公式、参数区间、退避单调、队列容量、锁类型）任一失败都会**终止启动**。完整配置项与偏差说明见[接入文档](docs/接入文档.md)。

<a id="运维与观测"></a>

## 运维与观测

```java
OutpostPage<OutboxDeliveryMessage> page = adminService.queryMessages(new MessageQuery());
adminService.resendMessage(deadMessageId);
OutpostStats stats = adminService.getStats();

adminService.updateConfig(new OutpostConfig()
        .set(OutpostConfig.BACKOFF_STEPS, new long[]{1, 3, 10})
        .set(OutpostConfig.ENABLED_NAMESPACES, Arrays.asList("order")));
```

仅热更白名单内的配置可在运行时修改，其余项必须重启；`schedule.batch-size` 变更会重新校验耦合公式，失败则整体回滚。

8 个固定指标名（Prometheus 导出时 `.` 转为 `_`）：

| 指标 | 含义 |
| --- | --- |
| `outpost.message.produce.total` | 事务内注册并落库的消息数 |
| `outpost.message.delivery.success.total` / `.fail.total` | 投递成功 / 失败数（带 `biz_type` 标签） |
| `outpost.message.dead.gauge` | 当前死信数 |
| `outpost.message.redelivery.ratio` | 重复投递占比 |
| `outpost.schedule.duration.ms` | 单轮调度耗时 |
| `outpost.delivery.duration.ms` | 单条投递耗时 |
| `outpost.retry.count.distribution` | 重试次数分布 |

现成看板：[`monitoring/grafana/outpost-dashboard.json`](monitoring/grafana/outpost-dashboard.json)。无 `MeterRegistry` 时自动降级为无埋点，功能不受影响。

<a id="兼容性"></a>

## 兼容性

| 环境 | 结论 | 验证方式 |
| --- | --- | --- |
| JDK 8 字节码 | 支持 | `--release 8` |
| JDK 17+ 测试 | 通过 | 本机 JDK 21 运行全部单元测试 |
| Spring Boot 2.3.12.RELEASE | 通过 | `.\mvnw.cmd -Pboot23 clean test` |
| Spring Boot 2.7.18（默认） | 通过 | `.\mvnw.cmd clean install` |
| Spring Boot 3.x | **运行时不兼容，V1 不发布** | 仅提供 `AutoConfiguration.imports` 声明 |

Boot 3 不兼容的原因：Spring Framework 6 移除了 `JdbcTemplate` 的 `queryForObject(String, Class, Object...)`、`update(String, Object...)` 等旧重载，且 Boot 3 要求 JDK 17 与 Jakarta 命名空间。详见[接入文档 §9](docs/接入文档.md)。源码除 JDK 自带的 `javax.sql.DataSource` 外无 `javax.*` / `jakarta.*` 引用。

<a id="文档与脚本"></a>

## 文档与脚本

| 内容 | 路径 |
| --- | --- |
| 接入文档：能力边界、通用业务消息、不支持清单、选型对比、接入 Checklist | [`docs/接入文档.md`](docs/接入文档.md) |
| 迁移与回滚操作手册：三阶段影子验证、Drain、紧急回灌 | [`docs/迁移与回滚手册.md`](docs/迁移与回滚手册.md) |
| 原始详细设计（按规格约束保持不变） | [`docs/本地消息表缓存一致性 SDK V1.0（MVP）详细设计文档.md`](docs/本地消息表缓存一致性%20SDK%20V1.0（MVP）详细设计文档.md) |
| DBA 建表脚本 | [`scripts/ddl/outpost_message.sql`](scripts/ddl/outpost_message.sql) |
| 紧急回灌 | [`scripts/backfill.sql`](scripts/backfill.sql) · [`scripts/replay.sh`](scripts/replay.sh) |
| 影子对账 | [`scripts/shadow-reconcile.sql`](scripts/shadow-reconcile.sql) · [`scripts/shadow-reconcile.sh`](scripts/shadow-reconcile.sh) |
| Grafana 看板（8 指标） | [`monitoring/grafana/outpost-dashboard.json`](monitoring/grafana/outpost-dashboard.json) |
| 设计规格与验收清单 | [`.trae/specs/build-outpost-outbox-sdk/`](.trae/specs/build-outpost-outbox-sdk/spec.md) |

<a id="开发"></a>

## 开发

```bash
# Linux / macOS
./mvnw clean install        # 编译 + 单元测试 + 打包
./mvnw verify               # 额外运行 *IT 集成测试（需 Docker）
./mvnw -Pboot23 clean test  # Boot 2.3 兼容性验证
```

```powershell
# Windows PowerShell
.\mvnw.cmd clean install
.\mvnw.cmd verify
.\mvnw.cmd -Pboot23 clean test
```

- `clean install`：编译、单元测试、打包；不运行需要 Docker 的集成测试。
- `verify`：运行 `outpost-sample` 的集成测试（缓存链路 + 通用 Outbox 链路）；**Docker 不可用时由 `@Testcontainers(disabledWithoutDocker = true)` 自动跳过**，不会导致构建失败，也可显式 `-DskipITs=true`。
- 依赖边界检查：`.\mvnw.cmd -pl outpost-core dependency:tree`，输出中不应出现 `spring-boot-*`。

运行示例（默认 `outpost.enabled=false`，无 MySQL/Redis 也能启动；先执行一次 `clean install` 安装本仓库依赖）：

```powershell
.\mvnw.cmd clean install
.\mvnw.cmd -pl outpost-sample spring-boot:run '-Dspring-boot.run.profiles=demo'
```

`package` 后可执行 jar 为 `outpost-sample/target/outpost-sample-1.0.0-SNAPSHOT-exec.jar`；主 artifact 保留为普通 jar（`classifier=exec`），避免 Boot 的 `BOOT-INF/classes` 结构破坏集成测试 classpath。

<a id="关键约束与限制"></a>

## 关键约束与限制

> [!WARNING]
> **`cache_delete` 处理器会击穿缓存，且所有缓存 key 必须设置 TTL。** `DEL` 之后至回源完成之间的并发读会直接打到 DB；若某个 key 没有 TTL 且删除动作最终丢失，脏值会永久残留。上线前必须为每个 key 设置 TTL、评估热点 key 击穿，或改用 `cache_tag_delete` 模式。

- 消息注册必须在**事务最外层**，且消息表与业务表共用同一个 `DataSource` bean 实例；不在事务内注册会立即抛异常。
- `handler-type=cache_tag_delete` 时**必须**显式配置 `outpost.delivery-cache.tag.read-client`（`builtin` / `external`），读路径必须改用 hash 语义，普通 `GET` 会 `WRONGTYPE`。
- 投递处理器必须**天然幂等**；超时重置、锁过期都可能造成重复投递。
- `schedule.lock-type=local` **仅单实例有效**，多副本必须用 `redis`。
- 不提供：强一致性、本地缓存失效、跨库/跨服务事务、消息顺序性、通用幂等屏障、NESTED savepoint 内消息撤销、rockscache 强一致读。完整清单见[接入文档 §2](docs/接入文档.md)。

<a id="许可证"></a>

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。在遵守该许可证条款的前提下，你可以自由使用、修改和分发本项目的源代码与衍生作品。

- 版权归属：Copyright 2026 Outpost Authors
- 专利授权与免责声明见 [LICENSE](LICENSE) 全文
- 使用本项目时请保留版权、专利、商标与署名声明；修改文件需标注变更说明
