# Outpost 本地消息表 SDK

Outpost 用「同库同事务写一张消息表 + 异步投递」保证业务事务提交成功后，下游动作（V1.0 聚焦缓存失效）最终执行，解决「DB 提交成功、异步删缓存失败」留下的永久脏缓存。

## 模块

| 模块 | 说明 | 发布 |
| --- | --- | --- |
| `outpost-core` | 内核：事务聚合、状态机、重试、调度 SPI、运维 SPI。仅依赖 `spring-tx` + SLF4J，不含 Spring Boot / ORM / `javax.*` | 是 |
| `outpost-spring-boot-starter` | 自动装配、JdbcTemplate 仓储、AOP 注解、Redis 锁、原生 `TagAsDeleted`、rockscache 兼容读客户端、Micrometer 指标 | 是 |
| `outpost-sample` | 可运行示例与 Testcontainers（MySQL 8 + Redis 7）集成测试 | 否 |

## 构建

源码以 `--release 8` 编译；测试可在 JDK 17+ 运行。仓库自带 Maven Wrapper（Maven 3.9.9）。

```bash
# Windows
.\mvnw.cmd clean install
# Linux / macOS
./mvnw clean install
```

- `clean install`：编译、单元测试、打包（默认跳过需要 Docker 的集成测试）
- `.\mvnw.cmd verify`：额外运行 `outpost-sample` 的 `*IT` 集成测试；**Docker 不可用时由 `@Testcontainers(disabledWithoutDocker = true)` 自动跳过**，不会导致构建失败；也可显式 `-DskipITs=true`
- 确认依赖边界：`.\mvnw.cmd -pl outpost-core dependency:tree`，输出中不应出现 `spring-boot-*`

## 最小接入

1. 由 DBA 执行建表脚本 [`scripts/ddl/outpost_message.sql`](scripts/ddl/outpost_message.sql)（或临时开启 `outpost.auto-create-table=true`）。
2. 引入依赖：

```xml
<dependency>
    <groupId>com.outpost</groupId>
    <artifactId>outpost-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

3. 应用需提供：`DataSource`（多数据源时标记 `@Primary` 或提供 `OutpostDataSourceProvider`）、`PlatformTransactionManager`、`StringRedisTemplate`。
4. 配置：

```yaml
outpost:
  enabled: true
  auto-create-table: false        # 生产建议 false，由 DBA 执行 DDL
  delivery-cache:
    handler-type: cache_delete    # 或 cache_tag_delete
```

5. 编程式：

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
public void updateOrder(String orderId) { /* 业务 SQL */ }
```

自定义业务处理器只需把 `DeliveryHandler` 注册为 Spring Bean，`bizType` 全局唯一。

## 关键约束（务必阅读）

- **消息注册必须在事务最外层**，且消息表与业务表共用同一个 `DataSource` bean 实例；不在事务内注册会立即抛异常。
- **所有缓存 key 必须设置 TTL**。`cache_delete` 模式存在缓存击穿风险，详见 [接入文档](docs/接入文档.md)。
- `handler-type=cache_tag_delete` 时 **必须**显式配置 `outpost.delivery-cache.tag.read-client`（`builtin` / `external`），且读路径必须改用 hash 语义，普通 `GET` 会 `WRONGTYPE`。
- 投递处理器必须天然幂等；不提供跨服务分支事务、消息顺序性或通用幂等屏障。

## 兼容性

| 环境 | 结论 |
| --- | --- |
| Spring Boot 2.3.12.RELEASE | `.\mvnw.cmd -Pboot23 clean test` 编译与全部单元测试通过 |
| Spring Boot 2.7.18（默认） | `.\mvnw.cmd clean install` / `.\mvnw.cmd verify` 通过 |
| Spring Boot 3.x | 仅提供 `META-INF/spring/...AutoConfiguration.imports` 声明；**运行时不兼容，V1 不发布**。原因：Spring Framework 6 移除了 `JdbcTemplate` 的 `queryForObject(String, Class, Object...)` / `update(String, Object...)` / `queryForList(String, Class, Object...)` 等旧重载，且 Boot 3 要求 JDK 17 与 Jakarta 命名空间。详见 [接入文档 §9](docs/接入文档.md) |

源码仅使用 JDK 的 `javax.sql.DataSource`，无其他 `javax.*` / `jakarta.*` 引用。

## 文档与脚本

| 内容 | 路径 |
| --- | --- |
| 接入文档（能力边界、不支持清单、选型对比、接入 Checklist） | [`docs/接入文档.md`](docs/接入文档.md) |
| 迁移与回滚操作手册 | [`docs/迁移与回滚手册.md`](docs/迁移与回滚手册.md) |
| DBA 建表脚本 | [`scripts/ddl/outpost_message.sql`](scripts/ddl/outpost_message.sql) |
| 紧急回灌脚本 | [`scripts/backfill.sql`](scripts/backfill.sql)、[`scripts/replay.sh`](scripts/replay.sh) |
| 影子对账脚本 | [`scripts/shadow-reconcile.sql`](scripts/shadow-reconcile.sql)、[`scripts/shadow-reconcile.sh`](scripts/shadow-reconcile.sh) |
| Grafana 看板（8 个指标） | [`monitoring/grafana/outpost-dashboard.json`](monitoring/grafana/outpost-dashboard.json) |
| 原始详细设计 | [`docs/本地消息表缓存一致性 SDK V1.0（MVP）详细设计文档.md`](docs/本地消息表缓存一致性%20SDK%20V1.0（MVP）详细设计文档.md) |

## 示例

默认配置 `outpost.enabled=false`，不连 MySQL/Redis 也能启动。跑通完整链路：

```bash
# 启动本地 MySQL 8 / Redis 7 后
.\mvnw.cmd -pl outpost-sample spring-boot:run -Dspring-boot.run.profiles=demo
```

或直接跑集成测试：`.\mvnw.cmd verify`（需 Docker）。
