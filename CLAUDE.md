
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

InfiniteChat 是一个 Spring Boot 3.5 + Spring Cloud Alibaba 的 IM（即时通讯）微服务系统，Java 17，Maven 多模块聚合工程。服务注册发现使用 Nacos，服务间调用使用 OpenFeign，异步链路使用 Kafka，实时通道使用 Netty WebSocket。

仓库没有 Maven Wrapper，需要本机安装 Maven。

## 常用命令

```bash
mvn -T 1C clean install -DskipTests
```

```bash
mvn -pl UserService -am clean package -DskipTests
```

```bash
mvn -pl RedPacketService test
```

```bash
mvn -pl UserService test -Dtest=InitProjectApplicationTests#someMethod
```

启动单个服务（模块目录内或用 `-pl`）：

```bash
mvn -pl GateWay spring-boot:run
```

`-am` 会一并构建依赖的 `Common` 模块；改动 `Common` 后必须重新 `install` 或带 `-am` 构建，否则其他模块用的是旧的 `Common` jar。

## 模块与端口

| 模块 | 端口 | 职责 |
| --- | --- | --- |
| `GateWay` | 10010 | Spring Cloud Gateway（WebFlux），全局 JWT 鉴权，路由到下游 |
| `UserService` | 8104 | 用户/登录/好友/会话/群聊，MinIO 头像，Netty 节点分配 |
| `RealTimeService` | 8102（Netty 9101） | Netty WebSocket 长连接、消息收发推送 |
| `OfflineDataService` | 8101 | 消息落库、离线/历史消息、Canal 增量同步 |
| `RedPacketService` | 8103 | 红包发送/领取/过期退款，余额账本 |
| `AiService` | 8105 | LangChain4j + 通义千问（DashScope），RAG / MCP / 工具调用 |
| `Common` | — | 被所有服务依赖的公共 jar（无启动类） |

外部依赖（`application.yml` 中均为 localhost 非标准端口）：MySQL `49152/InfiniteChat`、Redis `59000`、Nacos `18375`、Kafka `9092`、pgvector `54328`、MinIO `9000`、Canal `11111`。

## 架构要点

### Common 模块的隐式约定
每个服务的 `@SpringBootApplication` 都写成 `scanBasePackages = {"com.shanyangcode.<自身>", "com.shanyangcode.common"}`。这意味着 `Common` 里的 `@Configuration`/`@Aspect`/`@RestControllerAdvice`（`MybatisPlusConfig` 分页插件、`LogInterceptor` 控制器日志 AOP、`GlobalExceptionHandler`）对所有服务自动生效。新建服务时必须照抄这个 scanBasePackages，否则全局异常处理和分页插件会失效。

控制器统一返回 `BaseResponse<T>`（`ResultUtils.success/error`），业务异常抛 `BusinessException(ErrorCode)`，由 `GlobalExceptionHandler` 兜底。Kafka topic 名、Redis token key 前缀、消息类型/会话类型常量都集中在 `Common` 的 `constant` 包，跨服务共用，不要在各服务内重复定义。

### 认证：双 Token + 网关全局过滤器
`GateWay/filter/AuthorizeFilter` 是唯一的 HTTP 鉴权入口：读 `Access-Token` 头 → `JwtUtil.parse` → 与 Redis `access:token:{userId}` 比对。Access Token 失效但 Refresh Token（`refresh:token:{userId}`）有效时返回 `TOKEN_EXPIRED` 让前端刷新，否则返回 `NOT_LOGIN_ERROR`。白名单在 `EXCLUDE_PATHS`（登录/注册/验证码/刷新）。

WebSocket 不走网关，`RealTimeService/websocket/WebSocketAuthHeader` 在握手的 `FullHttpRequest` 阶段用 `Authorization` 头做同样的 Redis 比对，通过后把 userId ↔ Channel 注册进 `ChannelManager`。

注意：网关只配置了 `/api/user/**`、`/api/message/**`、`/api/ai/**` 三条路由，`RedPacketService`（`/api/chat/redPacket`）目前不在网关路由表里。

### 长连接节点定位（有状态服务的关键设计）
`ChannelManager` 是**进程内** `ConcurrentHashMap`，所以某个用户的连接只存在于某一个 RealTimeService 实例上。用户登录时 `UserServiceImpl` 调用 `NettyServiceLocator`：从 Nacos 拿 RealTimeService 实例列表 → `UrlHashLoadBalancer`/`ConsistentHash` 按 userId 一致性哈希选节点 → 把 `host:port/ws/netty` 作为 `nettyUri` 随登录响应返回给前端。前端据此直连该节点。修改扩缩容/哈希逻辑会直接影响用户重连后能否落到持有其历史状态的节点。

推送侧：`push-topic` 以 `sessionId` 作为消息 key 分区，各实例都消费（groupId `infinite-chat-push-group-0`），只有持有目标 Channel 的实例才推得出去，找不到 Channel 即视为离线（离线时间写 Redis `user:offline:{userId}`，供离线消息拉取使用）。

### 消息主链路
1. 客户端 → Netty `WebSocketHandel.channelRead0`：`ping` 直接回 `pong`；业务消息补上雪花 ID + 时间戳后**双写** Kafka —— `store-topic`（持久化，只写一次避免重复消费）与 `push-topic`（推送）。
2. `OfflineDataService/ConsumerOfflineService` 消费 `store-topic` 落库 `message` 表。
3. `RealTimeService/ConsumerMessageService` 消费 `push-topic`，按 `sessionType` 分流：单聊（0）推给收发双方；群聊（1）Feign 调 UserService 拿成员列表逐个推；机器人（2）Feign 调 AiService 拿回复，以 `AI_ID` 身份推给用户并把 AI 回复再写回 `store-topic`。
4. 系统通知走独立链路：UserService 发 `system-notification-topic` → `SystemNotificationConsumer` 推 WebSocket 并转发 `store-notification-topic` 持久化。消息类型按区间划分：0–99 聊天消息，100–199 系统通知（见 `MessageTypeConstant`）。
5. `OfflineDataService/CanalClient` 作为 `CommandLineRunner` 独立线程订阅 Canal（表 `infinitechat.message`），把最新消息同步进 Redis 会话缓存，供会话列表/离线拉取加速。

### 延迟任务通用模式（红包过期、好友申请过期）
`RedPacketService/scheduler/ExpirationDispatcher` 与 `UserService/scheduler/FriendRequestExpirationDispatcher` 用的是同一套「时间轮」写法，新增定时过期业务应照此实现：

- Redis ZSet 存 `member=业务ID, score=到期时间戳`（如 `redpacket-expire-zset`）。
- `@Scheduled(fixedRate=1000)` + ShedLock `@SchedulerLock`（Redis provider）保证多实例只有一个在扫。
- Lua 脚本原子完成「扫描 + 从 ZSet 删除」，脚本内用 Redis `TIME` 取当前时间避免各节点时钟漂移。
- 扫到的 ID 投 Kafka，由 Executor 消费者做真正的业务处理（幂等）。
- 有 batchSize / maxBatchesPerTick / timeBudgetMs 三个预算参数，单轮不得超时占锁。

### 红包并发模型
Lua 脚本集中定义在 `RedPacketService/config/RedisConfig`（`receiveRedPacketScript`、`scanExpiredRedPacketsScript`、`calculateRemainAmountScript`），Redis key 模板与语义全部在 `RedisKeyConstant` 里有注释说明：`:pool`（金额池 List，预先按 `RedPacketAlgorithm` 拆分）、`:records`（已领 Hash）、`:state`（终态墓碑）、`:grant`（领取授权）、`:allow`（发红包瞬间的群成员快照，写后不更新）。领取路径全在 Redis 里原子完成，落库与余额变更由 `redpacket-receive-topic` 等 Kafka 事件异步补齐。金额一律以**分**为单位存储。`@PreventDuplicateSubmit` 注解 + AOP 做接口防重。Feign 调 UserService 配了 `UserServiceClientFallbackFactory`（需 `spring.cloud.openfeign.circuitbreaker.enabled=true` 才生效）。

### AiService
`AiChat` 是 LangChain4j 的声明式接口（`@SystemMessage(fromResource="system-prompt/*.txt")`、`@MemoryId sessionId`、`@InputGuardrails(SafeInputGuardrail)`），在 `AiChatService` 中用 `AiServices.builder` 组装：DashScope chat/streaming 模型、pgvector `ContentRetriever`（RAG）、Redis 聊天记忆（窗口 20 条）、本地工具（`TimeTool`/`RagTool`/`EmailTool`）与 MCP toolProvider。`RagDataLoader` 启动时从 `rag.docs-path` 灌入知识库。`Monitor/` 下的监听器把模型调用指标暴露到 Actuator + Prometheus（`/actuator/prometheus`）。AiService 有 dev/prod 两套 profile（`application-dev.yml` 默认激活），其他服务只有单份 `application.yml`。

## 约定

- 数据访问统一 MyBatis-Plus；逻辑删除字段全局配置为 `isDelete`（1 删除 / 0 未删），查询不要手写 `is_delete` 条件。
- 主键统一雪花 ID：`Common/SnowflakeUtil`，RealTimeService 另有按实例区分 workerId 的 `SnowflakeDynamicUtil`；`snowflake.workerId/datacenterId` 在各服务 yml 里配置，多实例部署必须区分。
- 服务间调用一律用 `@FeignClient(name = "<服务名>")` + `client` 包；UserService 的 `/api/internal/**`（`InternalValidationController`）是给内部服务调用的校验接口，不面向前端。
- 各服务 `application.yml` 中包含明文的数据库/Redis/邮箱口令与 API Key，改动配置时注意不要把新的真实密钥写进仓库。
