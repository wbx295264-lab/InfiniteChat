# IM 项目八股汇总 - V2

> 来源：[飞书原文](https://q0kyes8lnt5.feishu.cn/wiki/WBLCwCuODijxt3k5jhacfoLWnmb)
>
> 说明：由只读共享页面转换为 Markdown；流程图/画板已转为可检索文本。

## 如果数据量太大了给消息队列（Kafka）塞满了怎么办？

消息队列发生消息积压是一个典型的生产消费长效优化机制速率不匹配问题。在我们的IM项目中，这可能发生在几种场景下，比如节假日群发祝福消息导致生产洪峰，或者某个消费服务（如离线消息入库）发生故障。针对这个问题，可以从“监控预警”、“应急处理”和“长效优化”三个层面来系统性地解决。

### 第一步：问题的定位与监控 (Sensing the Problem)

在讨论解决方案之前，最关键的是能实时感知到问题。我们会监控Kafka最核心的指标：消费者组的Lag（消费位点与分区最新位点的差距）。

- 监控体系：我们会使用 Prometheus + Grafana 搭建监控告警平台，通过 Kafka Exporter 采集Broker和消费者的关键指标。

- 核心告警：当某个Topic的Consumer Lag超过设定的阈值（例如10万条）并持续一段时间（例如5分钟），就会通过钉钉或短信触发告警，通知相关开发人员。

### 第二步：应急处理方案 (Immediate Actions)

收到告警后，需要立即采取措施，防止雪崩效应。

1. 紧急扩容消费者 (Scale Out)：这是最直接有效的方法。如果我们的Real-TimeCommunicationService或OfflineDataStore服务是容器化部署在K8s上的，可以直接通过kubectl scale命令横向扩容Pod数量，快速提升整体消费能力。

2. 降级非核心业务 (Degradation)：如果扩容无法立即解决问题，我们会实施服务降级。在APIGateway或MessagingService的入口处，暂时关闭或延后处理非核心消息，例如“某某某已读了你的消息”这类状态同步、朋友圈的点赞通知等。优先保障核心的单聊、群聊文本消息的投递。

3. 消息转储应急方案 (Message Backup)：如果积压非常严重，且消费端短时间无法恢复，我们会启动一个“紧急转储”程序。这个程序是一个高吞吐、无复杂业务逻辑的消费者，它唯一的任务就是把积压Topic中的消息快速消费出来，转储到另一个临时的大分区Topic，甚至是对象存储（如OSS）中。这能快速降低原Topic的水位，保证新消息的正常流转。之后再起专门的离线任务慢慢处理这些转储的消息。

> **图示（文本化）**

~~~text
视图

应急处理流程

正常流程

message

message

积压严重
大量积压消息

转储

离线处理

新消息
正常处理

消息生产者

核心Topic

核心业务消费者

紧急转储消费者

临时大分区Topic

批处理消费者
~~~

### 第三步：长效优化机制 (Long-term Optimization)

应急处理治标不治本，长期的优化才是关键。

1. 生产者端流量控制：

2. Broker端分区策略：

通过这套“监控-应急-优化”的组合拳，我们可以确保消息系统在面对流量洪峰时，既能快速响应，又能从根本上提升系统的健壮性和吞吐能力。

---

## 群聊怎么实现？

关于群聊的实现，我们确实是基于单聊模型之上进行扩展的。可以先快速地描述一下单聊的流程，再重点讲解群聊的实现方案、遇到的挑战以及优化。

### 1. 单聊实现（作为铺垫）

单聊的本质是点对点的消息投递。

> **图示（文本化）**

~~~text
OfflineStore
客户端B
RealTimeCommService(B)
Redis
Kafka
Caffeine缓存
(本地)
MessagingService
API网关
客户端A
OfflineStore
客户端B
RealTimeCommService(B)
Redis
Kafka
Caffeine缓存
(本地)
MessagingService
API网关
客户端A
路由查询(两级缓存)
alt
[缓存命中]
[缓存未命中]
发送消息给B
转发消息请求
1. 消息持久化(异步)
2. 查询本地缓存(用户B的路由)
返回B -> RTC_B
3. 查询Redis(用户B的路由)
返回B -> RTC_B
4. 写入本地缓存
5. HTTP请求推送消息给B
6. WebSocket Push
(后台)消息落库
~~~

这个流程的核心在于MessagingService能够通过Redis精确地知道接收者B连接在哪一台RealTimeCommService（称之为RTC服务）上，实现了消息的精准路由。

### 2. 群聊实现：从“暴力广播”到“精准扩散”

群聊是一对多的消息投递。

实现思路：MessagingService把消息推送给所有的RTC服务，每台RTC服务再判断自己管理的连接中有哪些属于这个群。

实现流程：

> **图示（文本化）**

~~~text
视图

客户端 A

构建消息对象
会话ID + 内容 + 时间戳

通过 Kafka 发送消息

保存到数据库

通过 Nacos 服务发现

获取所有 Netty 服务地址

遍历所有服务节点

发送消息到各个 Netty 服务

根据会话ID查找群成员

获取所有群成员的 Channel 地址

通过 WebSocket 广播消息

写扩散完成
~~~

### 3. 可能的追问与解决方案

- 追问：如果群成员非常多（例如上千人），你这个方案有什么问题？
  - 回答：这是“写扩散”方案的主要挑战。当群成员数量巨大时：
    - 消息风暴：一次消息发送会扩散成成百上千次的推送，对MessagingService和RTC服务造成巨大瞬间压力。

---

## 为什么要用二级缓存？二级缓存怎么实现？

在我们的系统中，用户和其所连接的Netty服务器（RTC服务）的路由关系，是一个典型的“读多写少”的场景。用户登录/下线时会“写”一次，但每次消息发送都需要“读”一次。为了应对这种高频读取，我们设计了Caffeine + Redis的二级缓存架构。

### 1. 为什么需要二级缓存？

1. 极致的性能追求：Caffeine是基于Java堆内存的本地缓存，它的访问速度是纳秒级别的。而Redis是分布式缓存，需要经过网络IO，访问速度在毫秒级别。对于消息路由这种核心路径，用Caffeine作为一级缓存，能极大地降低消息转发的延迟。这遵循了计算机体系结构中的数据局部性原理。

2. 降低下游系统压力：通过Caffeine拦截掉大部分的路由查询请求，可以显著降低对Redis的QPS压力和网络带宽消耗，保护了Redis这个共享资源。

3. 提升系统可用性：Redis作为二级缓存，保证了服务的无状态性和容灾能力。即使某个MessagingService实例重启，Caffeine缓存丢失，它依然可以从Redis中获取到路由信息，系统功能不受影响。

### 2. 二级缓存的实现

我们采用经典的“Cache-Aside”模式来实现二级缓存的读写。

> **图示（文本化）**

~~~text
视图

分布式中间件

MessagingService实例

1.查询路由

2.本地未命中

3.查询Redis

4.Redis返回

5.写入Caffeine

6.返回给调用方

业务代码

L1: Caffeine本地缓存

L2: Redis分布式缓存
~~~

### 3. 技术难点：数据一致性问题

使用二级缓存最大的挑战是数据一致性。当一个用户的路由信息发生变更时（例如用户断线重连，被分配到新的RTC服务器），我们需要同时更新Redis和所有MessagingService实例上的Caffeine缓存。

- 问题场景：用户A从RTC_1掉线，重连到了RTC_2。AuthenticationService更新了Redis中的路由为RTC_2。但此时，某个MessagingService实例的Caffeine中可能还缓存着旧的路由RTC_1。如果这时有人给A发消息，这个MessagingService就会把消息错误地发往RTC_1。

- 我们的解决方案：发布/订阅模型进行缓存同步

- 我们利用Redis的Pub/Sub功能来主动通知所有MessagingService实例进行缓存清理。
  a. 变更时发布消息：当AuthenticationService处理用户登录/下线，更新完Redis中的路由信息后，会向一个特定的channel（例如 user_route_update_channel）发布一条消息，内容为被变更的userId。

- 这样，当下一次再有请求查询该用户的路由时，Caffeine会未命中，然后穿透到Redis去加载最新的路由信息，从而保证了数据的一致性。

---

## 写扩散、读扩散是什么，他们的区别是什么？

写扩散和读扩散是分布式系统中处理一对多消息（尤其是群消息）投递的两种经典模型，它们在实现方式、资源开销和适用场景上有着显著的区别。

1. 核心定义

- 写扩散 (Write Diffusion / Fan-out on Write): 这是“推模型”（Push-based）。当一条消息产生时，系统会主动将这条消息的多个副本“扩散”并推送给所有接收方。对于群聊来说，就是将一条群消息复制多份，分别发送给群里的每一个成员。

- 读扩散 (Read Diffusion / Fan-out on Read): 这是“拉模型”（Pull-based）。消息产生时，只写入一个中心存储。接收方需要自己主动去这个中心存储“拉取”自己关心的消息。对于群聊来说，就是所有群成员去同一个“群收件箱”里拉取新消息。

2. 详细技术实现与对比

| 特性 | 写扩散 (Push Model) | 读扩散 (Pull Model) |
| --- | --- | --- |
| 数据流 | 发送者 → 消息服务 → 复制N份 → N个接收者 | 发送者 → 消息服务 → 写入1份 → N个接收者各自拉取 |
| 实时性 | 高。消息直接推送到在线用户，延迟低。 | 相对较低。依赖客户端轮询或通知拉取，存在延迟。 |
| 服务端写开销 | 高。一条消息会产生N次写/推送操作，N为群成员数。这就是所谓的“写放大”。 | 低。无论群多大，都只有一次写入操作。 |
| 服务端读开销 | 低。用户无需主动拉取。 | 高。每个成员都需要拉取，可能产生“读风暴”。 |
| 存储模型 | 消息存储在每个用户的“个人收件箱”中。 | 消息存储在共享的“群组收件箱”中。 |
| 适用场景 | 人数较少的活跃群组（如：几百人以内），对实时性要求极高。 | 人数巨大的群组（如：明星粉丝群、公司全员群），或者成员不频繁交流的场景。 |

---

## 如果每个服务器都要维护一个 userId 和 channel 的列表，服务器上同时在线的人很多，列表很大如何解决？

该问题直指分布式 IM 系统的核心难点之一：如何管理和路由海量的用户长连接状态。如果单机维护全量userId -> channel的映射，随着用户量增长，内存和CPU都会迅速成为瓶颈。在 IM 项目中，采用了分布式路由层的架构来解决这个问题。

首先，我们的ConcurrentMap方案，是在单机维度上解决线程安全问题的有效手段。在Real-TimeCommunicationService（RTC）节点内部，确实使用了ConcurrentHashMap<Long, Channel>来管理当前连接到本节点的用户的userId和Netty Channel的映射。但这仅仅是解决方案的一部分。

当系统扩展成集群时，真正的挑战在于MessagingService如何知道某个用户具体连接在哪一个RTC节点上。我的解决方案是一个分层的架构：

### 核心方案：基于一致性哈希的分布式路由层

这个方案的目标是：让每个RTC节点只负责一部分用户，而不是全部用户。这样，每个节点内部的ConcurrentMap规模就得到了有效控制。

实现细节：

1. 连接路由（用户登录时）：
  - 当用户尝试登录时，AuthenticationService不是随机选择一台RTC服务器。
  - 它会获取所有可用的RTC服务器实例列表（通过Nacos服务发现）。
  - 然后，它会使用一致性哈希算法，以用户的userId作为哈希的Key，计算出该用户应该连接到哪一台RTC服务器上。
  - AuthenticationService将这台被选中的RTC服务器的地址返回给客户端，客户端再与该服务器建立WebSocket长连接。

2. 状态存储（建立连接后）：
  - 当用户的WebSocket长连接在某个RTC节点（例如RTC-Server-3）上成功建立后，该RTC节点会完成两件事：
    - 本地存储： 在自己内存的ConcurrentMap中记录 put(userId, channel)。
    - 全局路由注册： 在Redis中记录一个全局的路由映射：HSET user_routing_table <userId> "RTC-Server-3"。这个Redis Hash结构就是我们的分布式路由层。我们为这个key设置一个合理的过期时间（例如，略长于心跳间隔），并通过心跳来续期。

3. 消息投递（消息发送时）：
  - 当MessagingService需要给用户B发送消息时，它不再需要知道用户B的Channel是什么。
  - 它只需要执行一步查询：HGET user_routing_table <userId_B>，从Redis中获取到用户B当前连接的RTC节点ID（RTC-Server-3）。

架构图示 (Mermaid):

> **图示（文本化）**

~~~text
消息发送

用户登录和连接建立

1.登录请求(userId)

2.获取RTC节点列表

3.一致性哈希(userId)

4.返回RTC_Node_2地址

5.建立WebSocket连接

6.注册路由信息

A. 收到发往userId的消息

B. 查询路由

C. 返回rtc_node_2

D. RPC调用

E. 从本地Map查找Channel

F. WebSocket推送

客户端

认证服务

Nacos

选择RTC节点2

HSET user_routing_table userId rtc_node_2

消息服务
~~~

这个方案的优势：

- 水平扩展能力强： 每个RTC节点都是无状态的（除了自己负责的一小部分连接状态），可以任意增删节点。

- 内存可控： 每个RTC节点的内存压力被分散，只与连接到本机的用户数成正比，而不是系统总用户数。

- 路由查询高效： Redis的Hash结构查询是O(1)的，性能极高。

技术难点与解决方案：

- RTC节点宕机： 如果RTC-Server-3宕机，连接在其上的用户会断线。
  - 解决方案： 客户端需要有断线重连机制。当重连时，会再次执行上述的登录流程。由于RTC-Server-3已从Nacos中被摘除，一致性哈希会自动将该用户路由到其他健康的节点上，系统自动恢复。

- 路由信息缓存： MessagingService每次都查Redis会有网络开销。

通过这套基于一致性哈希和Redis的分布式路由方案，我们优雅地解决了海量用户连接管理的问题，为系统的横向扩展奠定了坚实的基础。

---

## 你的项目怎么保证 Kafka 分区之间的顺序消费？

关于Kafka的顺序消费问题，可以理解是：Kafka本身只保证单个分区（Partition）内的消息是有序的，它不提供全局跨分区的顺序保证。 因此，关键在于我们如何设计分区策略，来满足我们业务场景下的顺序性要求。

在 IM 项目中，我们需要的不是所有消息的全局顺序，而是同一个会话（单聊或群聊）内的消息必须是有序的。比如，用户A给用户B发的“你好”必须先于“在吗”被接收。基于这个前提，我们的解决方案如下：

### 1. 生产者端：Key-Partition映射策略

核心思想是：将需要保证顺序的一组消息，通过指定相同的Key，让它们被发送到同一个分区。

- 分区键（Partition Key）的选择：

- Kafka生产者实现：
  - 当MessagingService要发送消息到Kafka时，我们会创建一个ProducerRecord对象，并明确指定这个Key。

~~~
// 伪代码示例
String topic = "im_messages";
String key = sessionId; 
String messageJson = "{\"msgId\": 123, \"content\": \"你好\"}";

// Kafka的默认分区器会对key进行哈希，然后模除分区数，来决定分区
// hash(key) % num_partitions
ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, messageJson);
kafkaProducer.send(record);
~~~

- 结果： 由于Kafka的默认分区器（DefaultPartitioner）会对Key进行哈希计算，然后对分区总数取模，所以相同的Key总是会被路由到同一个分区。这样，同一个会话的所有消息就自然地进入了同一个有序队列。

### 2. 消费者端：单线程消费分区

- OfflineDataStore服务作为消费者，会创建一个消费者组（Consumer Group）。

- Kafka的机制保证，一个分区最多只能被同一个消费者组里的一个消费者实例（线程）消费。

- 这意味着，无论我们的OfflineDataStore部署了多少个实例，处理partition-0上所有消息的责任，始终只落在某一个消费线程上。这个线程按顺序从分区中拉取消息并进行处理（例如，写入MySQL）。

架构图示 :

> **图示（文本化）**

~~~text
视图

消费者组 (OfflineDataStore)

Kafka Topic

生产者 (MessagingService)

key=conv_A

key=conv_B

key=conv_A

发送

消息1 (会话A)

KafkaProducer

消息2 (会话B)

消息3 (会话A)

Kafka Topic: thousands_word_message

Partition-0
[M1, M3]

Partition-1
[M2]

Partition-2

消费者实例1

消费者实例2

消费者实例3
~~~

图解：来自会话A的消息M1和M3，因为使用了相同的Key，被路由到了Partition-0，并由消费者1按顺序处理。会话B的消息M2则被路由到了Partition-1。

### 3. 技术难点与考量

- 分区热点问题（Hot Partition）： 如果某个群聊极其活跃（例如，一个万人大群），所有消息都涌向同一个分区，可能导致该分区的处理能力成为瓶颈，而其他分区很空闲。
  - 解决方案： 这是高级话题，可以展示你的深度。

- 消费者再均衡（Rebalance）： 当消费者实例增加或减少时，会触发Rebalance，分区会被重新分配。在此期间，消息消费会暂停，产生延迟。
  - 解决方案：
  i. 合理设置消费者Session超时和心跳间隔。
  ii. 在较新版的Kafka中，可以启用静态成员（Static Membership），减少不必要的Rebalance。
  iii. 保证消费处理逻辑尽可能快，减少单次poll()的耗时。

- 消息重复消费： 如果消费者处理完消息但在提交offset前崩溃，重启后会重复消费。
  - 解决方案： 消费端必须实现幂等性。在OfflineDataStore中，会将消息写入MySQL。在写入前，会先根据MessageID检查该消息是否已存在，如果存在则直接跳过，从而保证了即使重复消费也不会产生副作用。

通过以上“基于Key的分区策略 + 消费者组单线程消费分区”的设计，我们就在分布式环境下，利用Kafka的特性，实现了业务所需的会话内消息顺序性。

## 项目说到了高并发，什么结构或者说哪个模块用到了高并发，能体现出高并发的理念

## Netty 的内存参数是怎么设置的？

虽然我们没有在业务初期进行非常细致的内存参数调优，但我们深入研究了 Netty 的内存管理机制，这对于预防线上问题和进行性能优化至关重要。

主要分为以下几个层面：

### 1. 线程模型配置

这是服务器吞吐能力的基础。

- bossGroup = new NioEventLoopGroup(1): boss 线程池负责处理客户端的 accept 事件。对于一个服务端端口，通常一个线程就足够了，因为它只做连接建立，不做业务处理，开销极小。如果服务需要监听多个端口，可以适当增加线程数。

- workerGroup = new NioEventLoopGroup(): worker 线程池负责处理 read/write 事件和业务逻辑。Netty 默认会根据 NettyRuntime.availableProcessors() 创建 CPU核心数 * 2 个线程。这个配置在大多数 I/O 密集型场景下是最佳实践，能最大化利用 CPU 资源。如果业务逻辑中有大量耗时的同步操作（例如，同步调用外部 HTTP API），我们可能会考虑将业务逻辑提交到独立的业务线程池，避免阻塞宝贵的 I/O 线程。

### 2. 核心内存管理：ByteBuf 与内存池

Netty 的高性能很大程度上归功于其对内存的精细管理，核心是 ByteBuf。

- 池化内存（Pooled Memory） vs. 非池化内存（Unpooled Memory）:
  - 为了减少频繁创建和销毁 ByteBuf 带来的性能开销和 GC 压力，Netty 默认使用池化内存 (PooledByteBufAllocator)。它通过预先申请一大块内存，并将其组织成不同大小的块（PoolChunk、PoolSubpage）来复用内存，类似 jemalloc 的思想。
  - 在 IM 项目中，可以通过启动参数 serverBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) 来显式配置，这也是默认行为。

- 堆外内存（Direct Memory） vs. 堆内内存（Heap Memory）:
  - Netty 默认优先使用堆外内存（Direct Memory）。数据在进行网络 I/O 时，操作系统需要的是一块连续的内存空间。如果使用 Java 的堆内存（Heap Buffer），数据需要先从 JVM 堆拷贝到堆外内存，再由操作系统发送出去。
  - 使用堆外内存可以实现零拷贝（Zero-Copy），避免了这次多余的拷贝，显著提升 I/O 性能。这是我们选择 Netty 的重要原因之一。
  - 配置方式: 同样通过 -Dio.netty.noPreferDirect=false (默认) 来控制。

### 3. 关键内存参数与水位线配置（技术亮点）

为了防止由于上下游处理速度不匹配导致的内存溢出（OOM），Netty 提供了高低水位线机制。

- ChannelOption.WRITE_BUFFER_WATER_MARK: 这个参数非常重要，用于流量整形和防止内存积压。
  - 它包含一个高水位线（High Water Mark）和一个低水位线（Low Water Mark）。
  - 当 Netty 输出缓冲区中的字节数超过高水位线时，channel.isWritable() 会返回 false。此时，我们的业务代码应该停止向 Channel 写入数据，以防止内存无限增长。

- 伪代码示例：

~~~
// 设置高低水位线，例如 32KB 到 64KB
int lowWaterMark = 32 * 1024;
int highWaterMark = 64 * 1024;
bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(lowWaterMark, highWaterMark));

// 在业务Handler中检查
if (ctx.channel().isWritable()) {
    ctx.channel().writeAndFlush(message);
} else {
    // 缓冲区的消息太多了，可以考虑丢弃、记录日志或稍后重试
    log.warn("Channel is not writable, dropping message for user: {}", userId);
}
~~~

4. 技术难点与解决方案：堆外内存泄漏

- 难点: 堆外内存不受 JVM GC 的直接管理，如果忘记手动释放，会导致内存泄漏，最终进程被操作系统杀死。这通常发生在自定义 Handler 中，当 ByteBuf 的引用计数没有被正确处理时（例如，读取后没有传递给下一个 Handler，也没有调用 release()）。

- 解决方案:

## 竞品也是用 WebSocket 和 Netty 吗，这方面有了解过吗？

是的，我们对主流 IM 产品的技术架构做过一些调研和思考。虽然具体实现细节是商业机密，但通过公开的技术分享、开源组件和社区分析，我们可以大致勾勒出它们的轮廓。总体来说，选择什么技术，主要取决于产品的目标用户、业务场景和历史包袱。

WebSocket + Netty 是当前非常主流和成熟的组合，尤其适合从零开始构建、需要兼顾 Web 端和移动端的现代 IM 系统。但顶级竞品们往往有更复杂的演进路线。

> **图示（文本化）**

~~~text
视图
大众消费级
开发者/社区
企业办公
小众/开源
Rocket.Chat
DingTalk/Feishu
Slack/Discord
WeChat
协议标准化程度
目标用户群体
IM竞品技术选型象限图
~~~

1. 微信（国民级应用，技术驱动的极致体验）

- 核心协议: 自研的私有协议 MMProto（基于 TCP/UDP）。这不是简单的 WebSocket。

- 网络库: 自研的跨平台网络库 Mars（部分已开源）。底层基于 C++ 实现，以达到最高的性能和平台兼容性。

- 总结: 微信的方案是“不惜一切代价追求极致用户体验”的产物，技术壁垒非常高，不适合普通团队模仿。

2. 钉钉/飞书（企业级协作平台，可靠与功能并重）

- 核心协议: WebSocket 是主力，因为它标准化程度高，能很好地支持它们庞大的 Web/PC 客户端生态。同时可能混合 HTTP/2 或自研协议（基于 TCP）作为移动端补充。

- 网络库: 后端大概率是基于 Netty 或类似模式的自研 Java/Go 框架。

- 为什么这么选? 企业级市场对多端同步、消息必达、安全可控的要求极高。
  - WebSocket 提供了标准化的实时通道。
  - 在此之上，它们构建了复杂的上层信令系统，保证消息的严格顺序和状态同步（如已读、未读、正在输入等）。
  - 音视频通话则依赖 WebRTC 技术栈。

3. Discord/Slack（社区与开发者工具，生态开放）

- 核心协议: WebSocket + RESTful API 的经典组合。
  - WebSocket: 用于实时事件推送，如新消息、用户在线状态变化、有人开始打字等。
  - RESTful API: 用于拉取历史数据、发送消息、管理频道等非实时操作。

- 为什么这么选? 这种架构非常清晰，且对第三方开发者友好，便于构建丰富的机器人和应用生态。它们的成功很大程度上依赖于此。

- 后端技术: Discord 以其海量用户下的高性能而闻名，早期使用 Elixir，后来大量服务迁移到 Go 和 Rust。Slack 也是类似的多元化技术栈。

总结与思考

- 没有银弹: 技术选型服务于业务。微信的成功在于其在特定历史时期对移动弱网的极致优化。钉钉、Slack 的成功则在于找到了标准协议与业务功能的最佳结合点。

- WebSocket + Netty 的普适性: 对于我们的项目而言，这个组合是性价比最高的选择。它既能满足高性能、高并发的需求，又有成熟的生态和社区支持，能够快速实现核心功能，并兼顾 Web/H5 等多端场景，是现代 IM 系统的理想起点。

---

## 为什么 WebSocket + Netty 是主流？

WebSocket 和 Netty 之所以成为主流搭配，是因为它们在网络通信的不同层面形成了完美的互补，共同构建了一个高性能、高可控、高扩展性的实时通信解决方案。

1. 角色分工与协同效应

- WebSocket 扮演的是“应用层协议”的角色。它解决了“我们该说什么，怎么说”的问题。
  - 标准化: 它是一个 RFC 标准，提供了浏览器原生支持，解决了跨平台兼容性问题。
  - 全双工: 定义了握手、数据帧（Text/Binary Frame）、心跳帧（Ping/Pong Frame）等机制，使得服务端和客户端可以平等地双向通信，这是实现“服务端推送”的基础。

- Netty 扮演的是“网络基础框架”的角色。它解决了“如何高效、稳定地建立连接和传输数据”的问题。
  - 高性能 I/O 模型: 基于 Java NIO，采用 Reactor 线程模型（EventLoopGroup），能够用少量线程处理海量并发连接。
  - 精细化内存管理: 通过池化的堆外内存和零拷贝技术，极大地降低了 GC 压力和内存拷贝开销。
  - 高度可扩展的管道（Pipeline）架构: 这是 Netty 的精髓。它允许我们像搭积木一样，将各种处理逻辑（编解码、心跳检测、安全认证、业务处理）串联起来，代码结构清晰，易于维护和扩展。

2. “1+1 > 2”的化学反应

当 WebSocket 运行在 Netty 之上时，我们得到了一个完美的组合：

- Netty 为 WebSocket 提供了坚实的性能底座。

- Netty 的 Pipeline 机制让处理 WebSocket 协议本身变得极其简单。Netty 内置了 WebSocketServerProtocolHandler，它能自动处理 WebSocket 的 HTTP 升级握手、Ping/Pong 心跳、数据帧的编解码等繁琐工作。开发者只需要关注真正的业务逻辑即可。

流程图：一条消息在 Netty+WebSocket 架构中的旅程

> **图示（文本化）**

~~~text
视图
业务Handler
ChannelPipeline
Worker EventLoop
Boss EventLoop
客户端
业务Handler
ChannelPipeline
Worker EventLoop
Boss EventLoop
客户端
开始处理该Channel上的事件
WebSocket 长连接建立成功
发起连接请求 (TCP SYN)
接受连接 (TCP ACK)
将已连接的Channel注册到Worker
发送 WebSocket 握手请求 (HTTP Upgrade)
HttpRequestDecoder
HttpObjectAggregator
WebSocketServerProtocolHandler (处理握手)
返回握手成功响应
发送 WebSocket Frame (如 TextWebSocketFrame)
WebSocketFrameDecoder (解码)
传递解码后的消息 (如 String)
处理业务逻辑 (如消息入库、转发)
writeAndFlush(responseFrame)
WebSocketFrameEncoder (编码)
将编码后的Frame发送给客户端
~~~

3. 技术难点与解决方案

这个组合虽然强大，但在大规模应用中也面临挑战，而 Netty 恰好为这些挑战提供了成熟的解决方案：

- 难点一：海量连接下的心跳保活
  - 问题: 如何高效检测出已经“死掉”的连接（如客户端断网、崩溃），避免资源浪费？
  - Netty 方案: 使用 IdleStateHandler。我们可以配置一个时间，如果在这段时间内既没有读操作也没有写操作，它就会触发一个 UserEventTriggered 事件。我们可以在自定义的 Handler 中捕获此事件，然后向客户端发送一个 Ping 帧，如果客户端在规定时间内没有响应 Pong 帧，就可以安全地关闭连接。

- 难点二：优雅停机

- 难点三：粘包/半包问题

综上所述，WebSocket 解决了“做什么”的标准化问题，而 Netty 以其高性能和高扩展性解决了“怎么做”的工程化问题。它们的结合为构建现代、高效、可靠的实时通信系统提供了一个近乎完美的答案。

---

## 在 WebSocket 即时通信项目中，采用了 Kafka 消息队列。如果生产者向 broker 发送的信息，broker 向生产者返回 ack，此时 ack 丢失了，但 broker 已经接收到这个消息，生产者会重新发送，这个时候消息会重复被读取吗？

在我的项目中，我们综合运用了 Kafka 自身的机制和消费端的幂等性设计来彻底解决这个问题。

### 1. Kafka 生产者层面的保障：开启幂等性

首先，在生产者端，我们利用了 Kafka 0.11.0 版本后引入的幂等性（Idempotence）功能。

#### 1.1 技术原理

Kafka 的幂等性是通过为每个生产者分配一个唯一的 PID (Producer ID) 和为每条消息分配一个单调递增的 Sequence Number 来实现的。Broker 端会缓存每个 PID 对应的最新 Sequence Number。

当生产者重发消息时，它会使用相同的 PID 和 Sequence Number。Broker 收到消息后，会将其 Sequence Number 与缓存中的值进行比较：

- 如果收到的 Sequence Number 等于缓存中的 (last_sequence + 1)，说明是正常的下一条消息，Broker 会接收它。

- 如果收到的 Sequence Number 小于或等于缓存中的 last_sequence，说明是重复消息，Broker 会直接丢弃，并仍然返回成功的 ACK。

这样就在 Broker 层面避免了因重传导致的数据重复。

#### 1.2 具体实现

在我们的项目中，配置非常简单，只需在生产者的 application.properties 或 ProducerFactory 中设置：

~~~
# spring.kafka.producer.properties.* 是 Spring Boot 2.x 的配置方式
spring.kafka.producer.properties.enable.idempotence=true

# 为了保证幂等性，以下配置会自动调整，但明确写出有助于理解
# acks 必须设置为 all 或 -1，保证消息被所有 in-sync replicas 确认
spring.kafka.producer.acks=all 
# 重试次数应大于0
spring.kafka.producer.retries=3 
# inflight.requests.per.connection 必须小于等于5 (Kafka 1.x+)
spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
~~~

#### 1.3 流程图示

> **图示（文本化）**

~~~text
视图
Kafka Broker
生产者 (Producer)
Kafka Broker
生产者 (Producer)
网络波动，ACK 丢失
判断为重复消息，直接丢弃
发送消息 (PID=1, Seq=0)
存储消息 (PID=1, last_seq=0)
ACK (成功)
未收到 ACK，触发超时重试
重发消息 (PID=1, Seq=0)
检查 PID=1, 发现 incoming_seq(0) <= last_seq(0)
ACK (成功)
收到 ACK，确认发送成功
~~~

### 2. 消费端最终保障：业务去重

虽然生产者的幂等性解决了单次会话中的重传问题，但它无法覆盖所有场景，例如生产者进程崩溃重启后 PID 会改变。因此，我们必须在消费端实现最终的业务幂等性作为兜底。

#### 2.1 技术方案

我们在项目中采用全局唯一消息 ID + 数据库唯一键的方式实现。

1. 生成唯一 ID：在消息被创建时（通常在 MessagingService 中），我们就使用雪花算法 (Snowflake) 为其生成一个全局唯一的 64-bit message_id。这个 ID 本身就包含了时间戳、机器 ID 等信息，具有天然的趋势递增和唯一性。

2. 消费端处理：消费端模块 (OfflineDataStore) 在消费消息并将其存入 MySQL 时，message_id 字段在数据库表中被设置为唯一索引 (UNIQUE KEY)。

#### 2.2 伪代码示例

<!-- 飞书代码块未在只读页面中展开 -->

通过这种方式，即使 Kafka 层面出现了重复投递，数据库的唯一键约束也能保证数据不会被重复存储，从而实现了最终的业务数据一致性。

---

## 在 WebSocket 即时通信项目中，采用了 Kafka 消息队列。如果消费者消费消息，向 broker 返回的 ack 丢失了，这个消息会被重复读取吗？

在 Kafka 中，消费者处理完消息后，需要向 Broker "提交位移"（Commit Offset），这个提交操作就相当于 ACK。如果这个 "ACK" 丢失了，Broker 侧的位移没有更新。当消费者重启或者分区再均衡 (Rebalance) 发生后，新的消费者会从上一次成功提交的位移处开始拉取消息，导致这部分消息被重复处理。

IM 项目中，我们主要通过精细的位移管理和消费端幂等性设计来解决。

### 1. 核心策略：关闭自动提交，采用手动提交

我们首先将消费者的 enable.auto.commit 配置项设置为 false。自动提交会按固定时间间隔提交位移，这会让我们失去对提交流程的控制，可能导致消息处理了一半就提交了位移，或者处理完了但没来得及提交。

<!-- 飞书代码块未在只读页面中展开 -->

### 2. 消费端幂等性设计：利用 Redis 实现快速去重

对于实时性要求高的 IM 系统，每次都去查询数据库来判断是否重复，会给 DB 带来巨大压力。因此，我们引入 Redis 作为前置的去重屏障。

#### 2.1 技术原理

1. 唯一标识：我们依然使用雪花算法生成的全局唯一 message_id。

2. 处理前检查：消费者在处理每条消息的业务逻辑之前，先去 Redis 中检查该 message_id 是否已存在。

3. 原子操作：我们使用 Redis 的 SET key value NX PX milliseconds 命令。NX 选项保证了只有当 key 不存在时才能设置成功，这是一个原子操作，避免了并发场景下的问题。PX 用来设置一个合理的过期时间（例如，略大于消息处理的最大耗时），防止因消费者异常退出导致锁无法释放。

#### 2.2 伪代码示例

<!-- 飞书代码块未在只读页面中展开 -->

#### 2.3 流程图示

> **图示（文本化）**

~~~text
视图
Kafka Broker
MySQL
Redis
消费者 (Consumer)
Kafka Broker
MySQL
Redis
消费者 (Consumer)
网络故障，ACK 丢失
拉取消息 (Offset=N)
返回消息 (Msg_A)
SETNX "processed:Msg_A_ID"
OK (True)
存储消息 Msg_A
存储成功
提交 Offset (N+1)
Consumer 发生重启或 Rebalance
从上次成功提交的 Offset 开始拉取 (Offset=N)
再次返回消息 (Msg_A)
SETNX "processed:Msg_A_ID"
Fail (False)，因为 Key 已存在
判断为重复消息，跳过业务逻辑
提交 Offset (N+1)
ACK 成功
~~~

这种 Redis 预检 + 数据库唯一键兜底 的双重保险机制，既保证了高性能，又保证了最终数据的一致性，是工业界处理消费幂等性的成熟方案。

---

## 消息记录如何存储？

在 IM 项目中，我们对存储方案进行了分阶段的考虑和设计，从简单到复杂，最终落地的是一个冷热分离的方案。

### 1. 方案一：MySQL 集中式存储（项目初期方案）

在项目起步阶段，为了快速验证核心功能，我们采用了最直接的方案：所有消息记录都存储在 MySQL 中。

#### 1.1 表结构设计

~~~
CREATE TABLE message (
  message_id bigint NOT NULL,
  sender_id bigint NOT NULL COMMENT '发送者id',
~~~

- 优点：实现简单，ACID 特性保证了强一致性，便于进行数据统计和分析。

- 缺点：随着数据量增长，单表会变得极其庞大，idx_session_id_created_at这个核心索引的性能会严重下降，导致拉取历史消息（消息漫游）非常缓慢。高并发写入也容易达到 MySQL 的瓶颈。

### 2. 方案二：Redis (热) + MySQL (冷) 分层存储

为了解决方案一的性能问题，我们设计了冷热数据分离的架构。

#### 2.1 架构设计

- 热数据层 (Redis)：存储每个会话最近的 N 条消息（例如 N=200）。用户打开聊天窗口时，首次加载和新消息推送都直接命中 Redis，响应速度极快。

- 冷数据层 (MySQL)：全量存储所有历史消息，用于消息漫游（用户向上滑动加载更早的记录）和数据备份。

#### 2.2 为什么用 ZSet 存储热数据？

我们选择 Redis 的 ZSet (有序集合) 来存储热数据，这是因为 ZSet 的特性与 IM 场景完美匹配：

1. 天然有序：ZSet 的每个成员 (member) 都有一个分数 (score)。我们可以用消息的时间戳 (或雪花 ID) 作为 score，用消息的 JSON 字符串或其唯一 ID 作为 member。这使得消息能自动按时间排序。

2. 高效范围查询：拉取最近消息，本质上就是按分数（时间）倒序查询。ZREVRANGEBYSCORE 或 ZREVRANGE 命令可以实现 O(log(N)+M) 复杂度的查询，其中 N 是 ZSet 成员数，M 是返回的成员数，性能极高。

3. 固定大小：我们可以轻松地维持每个会话的 ZSet 只保留最新的 N 条消息。当新消息到来时，添加新成员后，检查 ZSet 大小，如果超过 N，就用 ZREMRANGEBYRANK 命令移除分数最低（即最旧）的成员。

#### 2.3 数据流转与查询流程

> **图示（文本化）**

~~~text
读取流程

Yes

No (上拉加载历史)

客户端请求消息

是否为首次加载/新消息?

从 Redis ZSet 拉取最近200条

返回给客户端

从 MySQL 分页拉取

写入流程

Yes

No

MessagingService 收到新消息

写入 Kafka

OfflineDataStore 消费消息

存入 MySQL

写入 Redis ZSet

ZSet大小 > 200?

移除最旧的消息

完成
~~~

#### 2.4 技术难点与解决

- 数据一致性：这是一个最终一致性的设计。写入时，我们先保证 MySQL 持久化成功，再写入 Redis。这样即使 Redis 写入失败，数据也不会丢失，最坏情况是用户需要从 MySQL 加载（感知上会慢一点）。读取时，优先读 Redis，Redis 没有再去读 MySQL。

- 空会话查询：如果一个会话的热数据在 Redis 中因长期不活跃而被清理，首次查询会穿透到 MySQL。我们可以在 Redis 中为这些空会话缓存一个特殊的空值，防止缓存穿透。

### 3. 方案三：引入时序数据库或 NoSQL（未来演进方向）

当业务体量达到千万甚至亿级用户时，即使分库分表的 MySQL 在处理海量消息写入和复杂查询时也可能力不从心。此时，我们会考虑引入更专业的存储方案。

- MongoDB：它的文档模型非常适合存储非结构化的消息体，内建的分片和高可用机制使其水平扩展能力强于传统关系型数据库。

- Cassandra / HBase：这类列式存储 NoSQL 数据库是为海量数据写入和查询设计的。我们可以将 conversation_id 作为分区键，message_id 或时间戳作为聚簇键，实现极高的写入吞吐和高效的顺序读取，非常适合 IM 消息的 "写多读少" 和 "顺序读" 特性。

总结：在面试中，可以首先详细阐述我们当前正在使用的 Redis+MySQL 冷热分离方案，讲清楚选择 ZSet 的原因、数据流转和关键实现细节。然后，补充说明，我们对未来的技术演进有清晰的规划，展示出架构前瞻性。

---

## 聊天时，Kafka 的 Topic 如何设计，Partition 的个数又如何呢？

关于 Kafka 的 Topic 和 Partition 设计，可以从业务隔离、消息特性和系统性能三个维度来综合考量，这是一个典型的权衡（trade-off）过程。

### 1. Topic 设计策略：按业务与消息类型隔离

在我们的 IM 项目中，消息可以粗略分为几大类，因此我会为不同业务或不同重要程度的消息创建独立的 Topic。

- im-single-chat-message: 用于单聊消息。这类消息实时性要求最高，需要保证会话内的顺序性。

- im-group-chat-message: 用于群聊消息。同样要求高实时性，需要保证群内的顺序性。

- im-notification: 用于系统通知，如好友申请、点赞评论、红包领取通知等。这类消息的顺序性要求相对较弱，但实时性也很重要。

- im-signal: 用于信令消息，例如“正在输入中...”、“消息已读”等。这类消息量大，但价值较低，可以设置较短的数据保留策略（Log Retention）。

这样设计的好处是：

1. 业务隔离与独立扩展：不同业务的流量洪峰（比如节日期间群聊消息暴增）不会相互影响。可以针对不同 Topic 的吞吐量需求，独立调整 Partition 数量或消费者组的资源。

2. 差异化服务质量 (QoS)：可以为不同的 Topic 设置不同的副本因子（Replication Factor）、数据保留策略（Retention Policy）、压缩算法（Compression Type）等。例如，聊天记录的 retention.ms 可以设置得很长（如7天），而信令消息可能只需要保留几小时。

3. 维护性与可观测性：职责清晰的 Topic 更容易监控、告警和排查问题。

### 2. Partition 数量设计：兼顾并行度、顺序性与吞吐量

Partition 的数量直接决定了消息处理的并行度和系统的吞吐上限。我会遵循以下原则来确定：

#### 1.2 核心原则：顺序性由 Key 保证，并行度由 Partition 驱动

- 顺序性保障：Kafka 仅保证单个 Partition 内的消息是有序的。为了实现 IM 场景中的“会话内消息有序”（例如，张三和李四的聊天记录），我们在发送消息到 Kafka 时，必须指定一个 Key。
  - 单聊 (im-single-chat-message): Key 直接使用 SessionId。
  - 群聊 (im-group-chat-message): Key 直接使用 SessionId。

- Kafka 的生产者会根据 Key 的哈希值将消息稳定地路由到同一个 Partition，从而保证了同一会话/群组的消息被顺序地写入和消费。

- 并行度与吞吐量：
  a. 消费者并行度：Partition 数量应该大于等于消费者组中消费者的数量（Partitions >= Consumers），这样才能充分利用消费端的处理能力，避免消费者空闲。
  b. 吞吐量预估：业界经验，单个 Partition 的写入吞吐量上限通常在 10MB/s 左右（这取决于硬件、网络、消息大小等）。我们需要预估业务的峰值流量。例如，预估单聊消息峰值为 50MB/s，那么至少需要 50 / 10 = 5 个 Partition。
  c. Broker 负载均衡：Partition 会均匀分布在集群的 Broker 上。通常建议 Partition 总数是 Broker 数量的整数倍，这样负载会更均衡。

#### 1.2.2 最终决策公式与流程

综合以上因素，一个 Topic 的目标 Partition 数可以这样确定：

Partition数 = Max(预估峰值吞吐量 / 单Partition吞吐量, 消费者组核心线程数)

并且，这个数值最好是 Broker 数量的整数倍，以便负载均衡。

### 1.3 技术难点与解决方案

- 技术难点：数据倾斜（Hotspot Partition） 如果某个大V网红的粉丝群或某个热点群聊异常活跃，所有消息都会涌向由该 SessionId确定的单个 Partition，导致该 Partition 成为性能瓶颈，而其他 Partition 可能很空闲。

- 解决方案：
  a. 监控与告警：首先需要对每个 Partition 的流量、消息积压（Lag）等指标做精细化监控，及时发现热点。

---

## Kafka 4 个消费者消费 3 个分区，3 个消费者消费 4 个分区，分别是什么现象？

这个问题很好地考察了对 Kafka 消费组（Consumer Group）核心工作原理的理解。其核心原则是：在一个消费组内，一个 Partition 最多只能被一个消费者实例消费，但一个消费者实例可以消费多个 Partition。

### 2.1 场景一：4 个消费者消费 3 个分区

- 现象：会有 3 个消费者分别消费 1 个分区，而第 4 个消费者将处于空闲（Idle）状态，不会被分配到任何 Partition，也无法消费任何消息。

- 原因：这是由 Kafka 的消费分配策略决定的。由于只有 3 个 Partition，根据“一个 Partition 最多被一个消费者绑定”的原则，最多只能有 3 个消费者同时工作。

- 架构启示：这种情况通常意味着资源浪费。在生产环境中，如果通过监控系统（如 Prometheus + Grafana）发现某个消费组长时间存在空闲的消费者实例，这可能是一个告警信号。它提示我们当前的消费者实例数超过了 Partition 数量，可以考虑缩减消费者实例数量以节约服务器或容器资源。

图示说明：

> **图示（文本化）**

~~~text
视图

Consumer Group (4 Consumers)

Kafka Topic (3 Partitions)

分配给

分配给

分配给

Partition 0

Partition 1

Partition 2

Consumer 1

Consumer 2

Consumer 3

Consumer 4 (空闲)
~~~

### 2.2 场景二：3 个消费者消费 4 个分区

- 现象：Kafka 会将 4 个 Partition 分配给 3 个消费者。由于 Partition 数量多于消费者数量，必然会有一个消费者消费 2 个 Partition，另外两个消费者各消费 1 个 Partition。

- 分配策略：具体的分配结果取决于所用的分区分配策略（partition.assignment.strategy）。

- 在这个场景下，一个典型的均衡分配结果是：

(具体哪个消费者消费2个分区是动态的，但模式是 2-1-1)

- 架构启示：这是非常常见的一种配置。它允许消费者的数量少于分区的数量，提供了处理能力的弹性。但需要注意，消费 2 个分区的那个消费者实例的负载会是其他消费者的两倍，我们需要确保单个消费者实例有能力处理这种峰值负载。为了达到最理想的负载均衡，最佳实践是让 Partition 的数量是消费者数量的整数倍。

图示说明：

> **图示（文本化）**

~~~text
Consumer Group (3 Consumers)

Kafka Topic (4 Partitions)

分配给

分配给

分配给

分配给

Partition 0

Partition 1

Partition 2

Partition 3

Consumer 1

Consumer 2

Consumer 3
~~~

---

## Kafka 跟 RocketMQ 的区别？

Kafka 和 RocketMQ 都是业界顶级的分布式消息中间件，但它们的设计哲学和侧重点有很大不同，这导致了它们在功能特性和适用场景上的显著差异。我们可以从以下几个关键维度进行深度对比。

### 3.1 核心架构与设计哲学

- Kafka: 遵循 "Dumb Broker, Smart Consumer" 的哲学。

- RocketMQ: 遵循 "Smart Broker, Dumb Consumer" 的哲学。
  - Broker（服务端）承担了更多的职责，内置了许多高级功能，如消息过滤、消息重试队列、死信队列、延迟消息、事务消息等。

### 3.2 功能特性差异对比

我将通过一个表格来清晰地展示它们的功能差异：

| 特性维度 | Kafka | RocketMQ | 差异点与影响 |
| --- | --- | --- | --- |
| 消息模型 | 发布/订阅 (Pub/Sub) | 发布/订阅 + 点对点 (P2P) | RocketMQ 的模型更丰富，但 Kafka 的单一模型通过 Consumer Group 也能实现 P2P 效果。 |
| 消息顺序性 | 分区内有序 (Partition-level) | 分区内有序 (Partition-level) + 全局有序（极少用，性能差） | 在保证顺序消费上，两者机制类似，都通过指定 Key 路由到同一分区实现。 |
| 消息重试 | 客户端实现。消费失败需应用自己捕获异常，重新发送或存入特定重试/死信Topic。 | 服务端支持。Broker 内置重试队列，消费失败后自动N次重试，若仍失败则投递到死信队列(DLQ)。 | RocketMQ 极大地简化了消费端的可靠性逻辑开发。 |
| 延迟/定时消息 | 原生不支持。需要借助外部系统（如定时任务）或Kafka Streams等生态组件实现。 | 原生支持。Broker 支持预设的多个延迟级别，发送时可指定，对账单、定时任务等场景非常友好。 | RocketMQ 在特定业务场景下有巨大优势。 |
| 事务消息 | 原生支持。提供 Exactly-Once 语义，通过事务性 Producer 实现。 | 原生支持。通过创新的两阶段提交（Half Message）机制，完美解决了分布式事务中的消息投递一致性问题。 | 两者都支持，但 RocketMQ 的实现对业务侵入性更小，在金融支付等场景应用广泛。 |
| 消息过滤 | 客户端过滤。消费者获取所有消息后，在自己的代码里根据消息内容进行过滤。 | 服务端过滤。支持在 Broker 端按 Tag 或 SQL92 表达式过滤，减少不必要的网络传输。 | RocketMQ 能有效降低消费端的负载和网络开销。 |
| 生态系统 | 极其强大和成熟。与大数据生态（Spark, Flink, Hadoop）无缝集成，拥有 Kafka Connect, ksqlDB, Kafka Streams 等强大组件。 | 相对聚焦。主要在 Java 和阿里体系内有深厚积累，生态正在快速发展，但广度不及 Kafka。 | Kafka 更适合作为公司级的数据总线和流处理平台。 |

### 3.3 场景选择与项目应用

- 选择 Kafka 的场景:

- 选择 RocketMQ 的场景:

总结：Kafka 和 RocketMQ 没有绝对的优劣，只有场景的适配度。Kafka 强在流处理生态和极致吞吐，而 RocketMQ 胜在为复杂业务场景提供丰富且可靠的消息功能。对于应聘者来说，理解它们的根本设计差异，并能结合具体业务场景做出合理的技术选型，是展示架构思维的关键。

## 红包模块的难点是什么？

### 1. 高并发领取的原子性控制（最核心难点）

问题本质： 多个用户在毫秒级时间窗口内同时领取同一个红包，如何保证：

- 不超领（10 个红包不能被 11 个人领走）

- 不重复领取（同一用户只能领一次）

- 金额正确（领取金额之和等于红包总额）

技术挑战：

并发场景示意：

~~~
┌─────────────────────────────────────────────────────────┐
~~~

结果：3人都成功领取，但数量只减了1 → 超领！

项目解决方案： 采用 Redis Lua 脚本实现「检查+扣减」原子操作。

### 2. 双数据源一致性保障

架构设计：

<!-- 飞书代码块未在只读页面中展开 -->

难点： 步骤1成功但步骤2-4失败时，如何处理 Redis 和 MySQL 的数据不一致？

### 3. 红包过期处理的可靠性

业务需求： 红包 24 小时未领完，剩余金额需自动退还给发送者。

技术难点：

- Redis key 过期事件通知不可靠（Redis 官方文档明确说明，具体可见
红包过期处理工业级成熟解决方案）

- 分布式环境下需防止重复退款

- 需要保证退款操作的最终一致性

### 4. 拼手气红包的金额分配算法

约束条件：

- 每个红包金额 ≥ 0.01 元

- 所有红包金额之和 = 总金额

- 保证一定的随机性和趣味性

难点：各种红包金额分配算法：二倍均值法、预处理线段切割法……

## Redis和MySQL数据库的一致性谁为主谁为辅?

### MySQL 为主，Redis 为辅

### 架构定位

| 存储系统 | 角色 | 存储内容 | 特点 |
| --- | --- | --- | --- |
| MySQL | 主存储（数据真源） | 红包详情、领取记录、用户余额、审计日志 | ACID 事务保证，数据可靠持久 |
| Redis | 辅助缓存（性能加速） | 剩余数量计数器 | 高性能，但可丢失 |

具体代码文件： MessagingService/src/main/java/com/shanyangcode/infinitechat/messagingservice/service/RedPacketReceiveService.java

<!-- 飞书代码块未在只读页面中展开 -->

关键设计点：

1. 幂等性检查用 MySQL：verifyUserHasNotReceived() 查询 red_packet_receive 表

2. 状态验证用 MySQL：validateRedPacketStatus() 基于数据库中的 status 字段

3. 最终写入用 MySQL：所有持久化操作在一个 @Transactional 事务中完成

##### 为什么选择 MySQL 为主？

##### Redis 的作用：性能优化而非数据决策

<!-- 飞书代码块未在只读页面中展开 -->

## Lua脚本实现扣减原子化操作是如何实现的?

#### 一、Lua 脚本的代码实现

文件： MessagingService/src/main/java/com/shanyangcode/infinitechat/messagingservice/constants/RedPacketConstants.java

<!-- 飞书代码块未在只读页面中展开 -->

#### 二、原子性实现原理

##### Redis 单线程执行模型

~~~
┌─────────────────────────────────────────────────────────────────┐
│                    Redis 命令执行队列                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Client A ─────→ ┌─────────────────────────────────────────┐   │
│                   │                                         │   │
~~~

核心原理：

1. Redis 使用单线程模型处理命令

2. Lua 脚本在执行期间独占 Redis 主线程

3. 脚本内的所有操作作为一个原子操作执行

4. 其他客户端的命令必须等待脚本执行完成

##### 为什么普通命令无法保证原子性？

<!-- 飞书代码块未在只读页面中展开 -->

<!-- 飞书代码块未在只读页面中展开 -->

#### 三、Lua 脚本执行流程详解

<!-- 飞书代码块未在只读页面中展开 -->

#### 四、Java 调用代码分析

文件： MessagingService/src/main/java/com/shanyangcode/infinitechat/messagingservice/service/RedPacketReceiveService.java:130-143

~~~
private Integer grabRedPacket(Long redPacketId) {
    // 1. 构造Redis key
    String redPacketCountKey = RED_PACKET_KEY_PREFIX + redPacketId;
~~~

#### 五、性能对比分析

| 方案 | 实现方式 | 单次耗时 | 并发1000吞吐 |
| --- | --- | --- | --- |
| 数据库悲观锁 | SELECT FOR UPDATE | ~10ms | ~100 QPS |
| 分布式锁 | Redisson | ~5ms 获取锁 + 操作 + 释放 | ~150 QPS |
| Lua脚本 | Redis原子操作 | ~0.1ms | 10000+ QPS |

Lua脚本的性能优势：

1. 零网络往返：脚本在Redis服务器执行，无需多次网络通信

2. 无锁开销：不需要获取/释放锁的额外操作

3. 单线程无竞争：Redis天然串行执行，无需锁协调

#### 六、完整的领取流程图

~~~
用户发起领取请求
        │
        ▼
┌───────────────────┐
│ 检查是否已领取    │ ← MySQL: SELECT FROM red_packet_receive
│ (幂等性保障)      │
└───────────────────┘
        │
        │ 未领取
~~~

---

## 你的红包业务如何保证高并发下的数据一致性和事务原子性？

子问题：

1. 用户发红包时需要扣减用户金额，是否要先加和锁后更新?

2. 如何保证红包表的修改和用户余额的修改是同步成功或失败的

3. Redis中已经修改的话,如何恢复

对于红包业务，其核心挑战在于高并发下的“账户扣款”和“红包创建”这两个操作。这涉及到并发控制、数据一致性和分布式事务三个层面的问题。我的设计思路是分层解决：

1. 单体并发控制：首先解决单个用户在同一时刻发起多次扣款请求的并发问题。

2. 本地事务保障：其次保证对数据库内多张表（如用户余额表、红包记录表）操作的原子性。

3. 分布式一致性：最后解决跨异构数据源（数据库 MySQL 和缓存 Redis）操作的一致性问题。

### 1. 并发扣款问题与锁策略选择

问题：“发红包是否要先加锁”。

在我们的系统中，一个用户可能在PC端和手机端同时点击“发送红包”，或者由于网络抖动，客户端进行了重试，这都会导致对同一个用户的余额进行并发扣款。如果不加任何控制，就会发生“更新丢失”的问题，最终导致用户超额扣款或扣款不足，这是支付级应用完全无法接受的。

因此，必须加锁。

#### 方案：数据库悲观锁

思路是“先上锁，再操作”。在查询用户余额时，直接锁定该行数据，防止其他事务读取或修改。

实现方式： 在事务中，使用 SELECT ... FOR UPDATE 语句。

<!-- 飞书代码块未在只读页面中展开 -->

---

### 2. 保证多表操作的原子性：本地事务

问题，如何保证“红包表”和“用户余额表”的修改同步成功或失败。

当用户成功扣款后，我们还需要在 红包领取记录表中插入一条记录。这两个操作必须是一个原子单元。

实现方式： 使用 Spring 的 @Transactional 注解，这是最直接、最有效的方案。

底层原理说明： Spring AOP 会为这个方法创建一个代理。在方法执行前，代理会通过 DataSourceUtils 从连接池获取一个数据库连接，并执行 connection.setAutoCommit(false) 开启事务。如果方法正常执行完毕，代理会调用 connection.commit()。如果方法抛出任何异常（rollbackFor 指定的异常类型），代理则会调用 connection.rollback()。

流程图示：

> **图示（文本化）**

~~~text
视图
数据库
红包服务 (AOP代理)
客户端
数据库
红包服务 (AOP代理)
客户端
alt
[更新成功 (rows=1)]
[更新失败 (rows=0)]
sendRedPacket(userId, amount)
BEGIN TRANSACTION (setAutoCommit(false))
UPDATE user_balance ... WHERE version = ...
INSERT INTO red_packet_info ...
COMMIT
返回"发送成功"
抛出异常 (e.g., OptimisticLockException)
ROLLBACK
返回"操作失败，请重试"
处理完成
~~~

注意点：@Transactional 注解只对 public 方法生效，且必须通过代理对象调用（即不能在类内部 this.someMethod() 调用），否则事务会失效。

### 3. 应对分布式挑战：数据库与 Redis 的一致性

问题：“Redis 中已经修改，数据库事务失败了如何恢复？”

在我们的业务中，为了实现高性能的“发红包”“抢红包”功能，红包的元信息（总金额、剩余数量等）在创建后，会被写入 Redis。这就产生了跨数据源的操作：写 MySQL + 写 Redis。@Transactional 只能管理数据库事务，无法管理 Redis 操作。这就引发了经典的分布式事务问题。

#### 3.1 方案一：补偿事务（TCC模式的简化思想） - 抢红包

这个方案的思路是 try-catch-compensate。在 catch 块中执行 Redis 的回滚操作。

伪代码：

<!-- 飞书代码块未在只读页面中展开 -->

技术难点与致命缺陷：

- 补偿操作可能失败：如果在执行 redisTemplate.delete() 时，Redis 恰好宕机或网络中断，怎么办？这会导致 Redis 中残留脏数据，数据一致性被破坏。

- 代码侵入性强：业务代码和补偿代码强耦合，非常丑陋且难以维护。

#### 3.2 方案二：将 Redis 操作移到事务成功提交后执行  - 发红包

这个方案的核心思想是最终一致性。我们优先保证核心业务（数据库操作）的成功。只有在数据库事务确认成功提交后，才去执行非核心的、可补偿的操作（如更新 Redis 缓存、发送消息等）。

实现方式： 利用 Spring 提供的 TransactionSynchronizationManager，它允许我们注册一个在事务不同阶段（如提交后、回滚后、完成后）触发的回调。

流程图示：

> **图示（文本化）**

~~~text
Redis
数据库
Spring事务管理器
DB服务
红包服务
客户端
Redis
数据库
Spring事务管理器
DB服务
红包服务
客户端
alt
[事务提交成功]
[事务提交失败]
sendRedPacket(...)
createRedPacketInDb(...)
开启事务
UPDATE user_balance...
INSERT red_packet_info...
返回 RedPacketInfo 对象
registerSynchronization(afterCommit -> 操作Redis)
COMMIT
触发 afterCommit() 回调
HSET red_packet:id ...
OK
(不触发afterCommit回调)
返回成功/失败
~~~

技术难点与解决方案：

- afterCommit 之后应用挂了怎么办？ 数据库事务已提交，但 Redis 操作还没执行或执行中应用就崩溃了。这依然会导致不一致。

- 解决方案：
  a. 数据库加一张“消息表”：在主业务的本地事务中，除了更新业务表，再往一张“待发送消息表”里插一条记录（如：{target: 'redis', content: '...'}）。有一个独立的、可靠的后台服务（或定时任务）去轮询这张表，将消息投递到 Redis。投递成功后，更新或删除该记录。这是业界非常成熟的“可靠消息最终一致性”方案。

#### 3.3 方案三：引入分布式事务框架（如 Seata）

对于更复杂的、跨多个微服务、多个数据库的场景，可以引入像 Seata 这样的分布式事务框架。

- Seata AT 模式：它会自动代理数据源，在业务SQL执行前后，记录 undo_log。如果全局事务需要回滚，Seata 会根据 undo_log 自动生成补偿SQL来恢复数据。

- 权衡：

结论：对于“MySQL + Redis”这种异构数据源的场景，发红包使用方案二（事务后回调 + 可靠消息/MQ），抢红包使用方案一。

## 抢红包请求量过大（例如数十万请求），不可能对数据库所有数据加锁，这个怎么解决

抢红包的核心优化是把并发竞争从 DB 搬到 Redis，再从 Redis 搬到异步——发红包时预分配好每一份的金额并写入 Redis（List 存金额、Set 存已领用户、Hash 存元数据）；抢红包时用一条 Lua 脚本原子性完成去重、扣减、取金额三件事，Redis 返回后立即响应用户；真正的数据库落账（red_packet_receive / user_balance / balance_log）通过 Kafka 异步消费 + 批量写入 + 唯一索引幂等完成。配合接入层限流和本地快速失败缓存保护 Redis；最终用定时对账和延迟队列兜底过期和一致性。这样整条链路上 DB 只承担持久化，不承担并发，行锁热点被彻底消除，理论瓶颈从 MySQL 的几千 QPS 抬升到 Redis 的十万 QPS 量级。

## 为什么考虑使用 Redis 存储红包剩余个数?

在我们的项目中，之所以选择使用 Redis 存储红包的关键信息（如剩余个数、剩余金额），主要基于以下四点核心考量，这四点共同构成了我们应对高并发“抢红包”场景的解决方案：

### 1. 利用内存数据库实现极致性能

抢红包是一个典型的“读多写多”且对响应时间极度敏感的场景。

- 性能对比：传统关系型数据库（如我们项目中的 MySQL），数据存储在磁盘上。在高并发写操作下，会涉及复杂的事务、行锁、页锁以及大量的磁盘 I/O，这在高QPS（每秒查询率）下会成为严重的性能瓶颈。而 Redis 是基于内存的键值数据库，内存的读写速度比磁盘快几个数量级，能够轻松支撑每秒数万甚至数十万次的读写请求，完美匹配抢红包场景的性能要求。

- 实现细节：我们将每个红包的核心数据，如 red_packet_id:count（剩余数量）、red_packet_id:amount（剩余金额）作为独立的 Key 存储在 Redis 中。当用户发起抢红包请求时，服务直接与 Redis 交互，避免了对底层数据库的直接冲击。

### 2. 借助原子操作保证数据一致性

在并发环境下，多个用户同时抢夺最后一个红包，如果不做任何控制，极易发生“超卖”问题，即最终抢到红包的人数超过了红包设定的总数。

- 原子性是关键：Redis 提供了多种原子操作命令，这些命令在执行过程中不会被其他客户端的命令打断。我们主要利用了 DECR (自减) 和 Lua 脚本这两种方式。

- 简单场景 DECR：对于普通红包，如果只关心数量，DECR 命令本身就是原子的。在抢之前，先对红包数量执行 DECR，如果返回值大于等于0，则表示抢成功。

- 复杂场景 Lua 脚本：为了在一个原子操作内完成“检查余量、扣减数量、计算金额”等多个步骤，我们采用了 Redis 的 Lua 脚本。这相当于在 Redis 服务端自定义了一个原子性的事务，避免了多次网络请求带来的延迟和潜在的并发问题。

### 3. 作为数据库前置屏障，减轻 MySQL 压力

尽管 Redis 性能卓越，但我们依然需要 MySQL 做数据的最终持久化和兜底。Redis 在此架构中扮演了至关重要的“缓冲层”和“挡板”角色。

- 写操作异步化：用户成功抢到红包后，我们并不是立即同步写 MySQL。而是先将抢成功的记录（如用户ID、红包ID、抢到金额）封装成一条消息，发送到 Kafka 消息队列中。

- 数据库削峰填谷：由一个专门的消费者服务（如 OfflineDataStore）从 Kafka 中平稳地拉取消息，进行批量数据库写入操作。这样，即使在抢红包的瞬间有巨大的并发写请求，这些请求也只是快速地打在 Redis 和 Kafka 上，而对数据库的写入压力则被分散到后续的时间里，避免了数据库被打垮的风险。

下面是这个流程的示意图：

> **图示（文本化）**

~~~text
视图
MySQL
数据存储服务
Kafka
Redis
消息/红包服务
客户端
MySQL
数据存储服务
Kafka
Redis
消息/红包服务
客户端
执行 Lua 脚本，原子性检查并扣减数量
构造抢红包成功消息
批量更新数据库
par
[异步持久化]
alt
[抢成功]
[抢失败]
POST /red-packet/grab (红包ID, 用户ID)
EVAL script.lua 2 KEYS[1] KEYS[2] ARGV[1]
返回结果 (成功/失败/已抢)
响应: 抢红包成功
Produce(topic="red_packet_log", message={...})
Consume(topic="red_packet_log")
UPDATE red_packet ...
INSERT INTO red_packet_receive ...
Ack
响应: 手慢了，红包派完了
~~~

### 4. 利用 TTL 特性实现红包自动过期

业务上，红包在发出 24 小时后若未被抢完，需要自动过期并退还余额。Redis 的 TTL (Time To Live) 机制天然适合这个场景。

- 设置过期时间：在创建红包并将其信息存入 Redis 时，我们可以使用 EXPIRE 命令为相关的 Key 设置一个 24 小时的过期时间。

- 过期后处理：

但是，生产实践不可这样，具体可见
红包过期处理工业级成熟解决方案

综上所述，选择 Redis 是一个综合了性能、一致性、系统鲁棒性和功能契合度的架构决策。

---

## Redis 缓存挂掉怎么办? 用户还能抢红包吗

如果 Redis 发生单点故障，抢红包功能在我们当前的设计中是不能使用的，因为 Redis 在此场景下承载了核心的并发控制逻辑，是不可或缺的一环。

我们的应对策略不是在 Redis 挂掉后试图绕过它，而是通过一系列的架构设计来最大限度地保证 Redis 服务自身的高可用性，并在极端情况下实现服务的优雅降级和快速失败，避免故障扩散。

具体方案分为“事前预防”、“事中恢复”和“事后影响控制”三个层面。

### 1. 事前预防：构建高可用的 Redis 架构

为了防止单点故障，我们从一开始就不会部署单节点的 Redis。而是采用业界成熟的高可用方案：

#### 1.1 Redis Sentinel (哨兵模式)

对于中小型并发量的系统，哨兵模式是一个性价比很高的选择。

- 架构组成：它由一个 Master 节点（负责写操作）、多个 Slave 节点（负责读操作和数据备份）以及多个 Sentinel 哨兵进程组成。

- 核心职责：
  - 监控 (Monitoring)：Sentinel 会持续监控所有 Master 和 Slave 节点的健康状态。

#### 1.2 Redis Cluster (集群模式)

对于需要支撑海量数据和极高并发的场景，我们会选择 Redis Cluster。

- 架构组成：Cluster 通过数据分片（Sharding）将数据分散到多个 Master 节点上，每个 Master 节点都可以有自己的 Slave 节点做备份。它是一个去中心化的架构，节点之间通过 Gossip 协议交换状态信息。

- 优势：
  - 水平扩展：不仅解决了高可用的问题，还能通过增加节点来线性地扩展 Redis 的吞吐能力和存储容量。
  - 内置高可用：每个分片（Slot）都有主备，当某个 Master 宕机，其对应的 Slave 会自动提升为新的 Master，继续提供服务。

下图展示了 Redis Cluster 的高可用架构：

> **图示（文本化）**

~~~text
高可用 Redis 集群
分片 3 (Slots 11001-16383)
分片 2 (Slots 5501-11000)
分片 1 (Slots 0-5500)
读写请求
读写请求
读写请求
Master 1
Slave 1
Master 2
Slave 2
Master 3
Slave 3
红包服务
~~~

通过上述任一方案，单台 Redis 服务器的物理宕机将不再导致整个服务的不可用。

### 2. 事中恢复与影响控制：降级、熔断与告警

即使有了高可用架构，依然可能出现网络分区、或整个 Redis 集群都无法访问的极端情况。此时，我们的策略是保护核心系统，防止故障蔓延。

#### 2.1 快速失败与服务降级

- 不考虑数据库降级：有的方案会考虑在 Redis 挂掉后，将请求直接打到数据库，使用 SELECT ... FOR UPDATE 这样的悲观锁来实现并发控制。最好不采用这种方案。因为抢红包场景的并发量远超数据库的承受能力，这样做会立刻拖垮数据库，导致如聊天、登录等其他核心功能全部瘫痪，造成“雪崩效应”。

- 返回特定响应：当红包服务检测到无法连接到 Redis 时，会立即向客户端返回一个明确的错误信息，如“系统繁忙，请稍后再试”或“红包功能暂时维护中”。这是一种主动的服务降级。

#### 2.2 客户端熔断

在我们的微服务调用链中，会集成 Resilience4j 或 Sentinel 这样的熔断组件。

- 工作原理：当红包服务在短时间内连续多次调用 Redis 失败并达到预设阈值时（例如：10秒内失败率超过50%），熔断器会“打开”（Open）。

- 熔断效果：在接下来的一个时间窗口内（例如：1分钟），所有访问红包功能的请求将不再尝试连接 Redis，而是直接被熔断器拦截并快速失败。这避免了无用的资源消耗和请求堆积，保护了服务自身。

- 半开状态：熔断窗口期过后，熔断器会进入“半开”（Half-Open）状态，尝试放行少量请求。如果这些请求成功，熔断器关闭，服务恢复；如果依然失败，则继续保持打开状态。

#### 2.3 实时监控与告警

我们会使用 Prometheus + Grafana 对 Redis 集群的各项关键指标（如连接数、内存使用率、命令延迟、哨兵状态等）进行实时监控。一旦出现异常，会通过飞书、短信等方式立即通知运维团队，以便快速人工介入。

总结一下，面对 Redis 故障，我们的核心思想是：以预防为主（高可用架构），以隔离和快速失败为辅（熔断降级），避免将瞬时高并发压力转移给后端数据库，优先保证整个 IM 系统核心功能的稳定性。

---

## 用 WebSocket 和别人聊天，聊天信息是如何存储的，有没有加密或者其他的东西？

### 1 消息的持久化

这个问题在项目中是通过Kafka消费完直接存储到MySQL，直接并没有做其他操作。但是可以说进阶思路，比如数据加密，可以说在项目中加入端对端加密，用户登录之后给用户颁发一对公私钥，私钥用户自己保存，发送消息的时候通过私钥加密，接收者接收消息的时候去获取发送者的公钥进行解密。

### 2 数据安全保障

对于聊天应用，数据安全至关重要。我们从两个层面来保障：

#### 2.1 传输层安全 (TLS/WSS)

这是最基础也是必须的安全措施。

- 实现方式：我们不使用明文的 ws:// 协议，而是使用加密的 wss:// (WebSocket Secure) 协议。这等同于 http 和 https 的关系。

- 作用：通过在 TCP 层之上添加 TLS/SSL 加密层，可以确保从客户端到 Netty 网关的整条通信链路是加密的。这能有效防止中间人攻击、网络窃听等，保证了消息在传输过程中的机密性和完整性。在 Netty 中，可以通过集成 SslHandler 来轻松实现。

#### 2.2 端到端加密 (End-to-End Encryption, E2EE) - 进阶设计

虽然 TLS 保证了传输安全，但消息在服务器端（如 MessagingService）是明文的。这意味着，公司内部的运维人员或入侵服务器的黑客可以看到用户的聊天内容。为了实现最高级别的隐私保护，我们可以设计并实现端到端加密。

这是我在项目中构思的一个进阶方案，其核心思想是：消息的加解密完全在用户设备上进行，服务器只负责转发无法解析的密文。

- 密钥管理：
  a. 公私钥对生成：每个用户在首次注册登录时，客户端会为其生成一对公私钥（例如使用 Curve25519 算法）。
  b. 私钥存储：私钥永远、只存储在用户设备本地，并进行加密保护。
  c. 公钥分发：用户的公钥会被上传到服务器，用于分发给其他希望与其通信的人。

- 加密与解密流程：
  a. 发送方 (Alice)：当 Alice 想给 Bob 发送消息时：
    - Alice 的客户端从服务器获取 Bob 的公钥。
    - 使用 Bob 的公钥 对消息内容进行加密。
    - （可选）同时使用 Alice 自己的私钥 对密文进行签名，以证明消息来源。
    - 将加密后的“密文包”通过 WebSocket 发送到服务器。
  b. 服务器：服务器收到的是一串无法解密的乱码。它只根据消息头中的 session_id 和 receiver_id 等元数据，将这个“密文包”原封不动地转发给 Bob 所在的 Netty 网关。

- 技术难点与权衡：
  - 多设备同步：如何让用户在手机和电脑上都能看到同一份聊天记录，是 E2EE 的一个经典难题，需要复杂的密钥同步和管理机制。
  - 服务端功能受限：由于服务器无法获取明文，像“聊天记录云端搜索”这样的功能将无法实现，或者需要采用“密文搜索”等更复杂的技术。

---

## 用户量巨大，如何分库分表？

对于IM系统中用户量和消息量持续增长带来的数据库性能瓶颈问题，我们可以设计一套基于 ShardingSphere 的分库分表方案。核心思路是“垂直拆分先行，水平拆分跟上”，并选择合适的分片键（Sharding Key）来保证数据均匀分布和查询效率。

### 1 拆分策略

#### 1.1 垂直拆分 (Vertical Sharding)

在项目初期，可以根据业务模块的关联性和数据增长速度，进行了垂直分库。例如：

- 用户库 (user_db): 存放 user, friend, user_balance 等与用户身份、关系强相关的表。这些数据相对稳定，但读取频繁。

- 消息库 (message_db): 存放 message, session, user_session 等与消息收发、会话相关的表。这是整个系统数据增长最快的部分，写入操作极为频繁。

- 业务库 (business_db): 存放 red_packet, moment 等特定业务功能的表。

这样做的好处是，将核心业务隔离开，不同业务的数据库IO和CPU压力不会相互影响，便于独立优化和扩容。

#### 1.2 水平拆分 (Horizontal Sharding)

当单一业务库（特别是消息库）也达到瓶颈时，可以进行水平拆分。主要针对以下几张核心表进行水平分片：

- message 表: 这是最核心的分片对象。可以选择 session_id 作为分片键。因为在IM场景中，绝大部分查询都是在特定会话内拉取历史消息（WHERE session_id = ?），使用 session_id 可以确保同一个会话的所有消息都落在一个分片上，避免了跨库查询的性能损耗。

- friend 表: 这张表可以采用user_id作为分片键。查询我的好友（WHERE user_id = ?）时，可以直接路由到特定分片，效率很高。

- user 表: 同样使用user_id作为分片键。

### 2. 分片键与分片算法

我们的ID都是由雪花算法生成的，这是一个 Long 类型的64位整数。它天然具备趋势递增的特性，非常适合作为分片键。

- 分片键: user_id, session_id

- 分片算法: 可以采用哈希取模算法。例如，对 message 表进行分库分表，假设规划了4个库，每个库16张表（message_0 到 message_15）。
  - 分库策略: database_index = session_id.hashCode() % 4

### 3. 技术实现与架构图

可以计划使用 ShardingSphere-JDBC，它对应用层是透明的，只需要在项目中引入依赖并进行配置，就可以像操作单库单表一样执行SQL，无需大幅改动业务代码。

#### 3.1 ShardingSphere-JDBC 核心配置示例

~~~yaml
spring:
~~~

#### 3.2 架构示意图

> **图示（文本化）**

~~~text
Database Layer
ShardingSphere-JDBC
Application Layer
ds0
session_id.hashCode() % 2
session_id.hashCode() % 16
路由结果: ds0.message_10
ds1
message_0
...
message_15
业务代码: SELECT * FROM message WHERE session_id = ?
SQL解析
SQL路由
路由到 ds0 还是 ds1?
路由到 message_0 到 message_15?
ds0: message_db_0
ds1: message_db_1
message_0
...
message_15
~~~

### 4. 技术难点与解决方案

1. 跨分片JOIN查询：水平分片后，无法直接进行JOIN查询。
  - 解决方案：通过“冗余字段”或“应用层组装”来解决。例如，在message表中冗余存储发送者的username和avatar，避免查询时JOIN user表。对于无法冗余的复杂查询，会发起多次单表查询，在业务代码中进行数据组装。

2. 分布式事务：例如，发红包操作需要同时扣减user_balance和写入red_packet记录，如果这两张表分在不同库，就会产生分布式事务。

3. 平滑扩容：当现有的分片也无法满足需求时，需要扩容。
  - 解决方案：采用双写+数据迁移的方案。例如，从4个库扩容到8个库，会先上线新库，在一段时间内，应用会同时向新旧库规则写入数据。同时，后台会有一个迁移任务，将历史数据从旧库按新规则迁移到新库。迁移完成后，再将读流量切换到新库，最后下线旧库。

---

## 雪花算法（全局唯一ID）

我们项目中采用了雪花算法（Snowflake）来生成全局唯一的ID，主要用于数据库主键、消息ID等。选择它的原因是它生成的ID既能保证全局唯一，又是趋势递增的，对数据库索引非常友好，同时性能极高。

### 1. 雪花算法原理与结构

雪花算法生成的是一个64位的 long 类型整数，它的内部结构非常有意思，就像一个精心设计的“时间+空间”坐标。

> **飞书画板**：只读页面未提供可提取的画板文本。

- 1位符号位: 最高位固定为0，保证生成的ID总是正数。

- 41位时间戳: 精确到毫秒。2^41毫秒大约可以使用69年。它决定了ID的趋势递增特性，新生成的ID一定比旧的ID大。

- 10位工作进程 ID: 这部分是分布式部署的关键，它最多可以支持 2^10 = 1024 个节点。我们通常会把它再拆分为5位数据中心ID（Datacenter ID）和5位工作节点ID（Worker ID），这样可以更好地对机房和机器进行分组管理。

- 12位序列号位: 用于解决同一毫秒内同一台机器上的并发问题。它支持同一节点同一毫秒内生成 2^12 = 4096 个不同的ID。

### 2. 实现与配置

虽然可以直接使用Hutool等工具包，但在项目中我们会将其封装成一个 Spring Bean，以便统一管理 Worker ID 和 Datacenter ID 的分配。

#### 2.1 ID生成器配置

Worker ID 和 Datacenter ID 的分配是关键，要确保在整个分布式系统中不能有任何两个服务实例的ID组合是相同的。

- 解决方案: 我们采用基于 Nacos 的动态分配机制。

#### 2.2 Java代码示例 (Spring Bean)

~~~java
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdGeneratorConfig {

    // workerId 和 datacenterId 可以通过 Nacos 动态获取，或通过启动参数、环境变量注入
    // 这里为了演示，使用 @Value 从配置文件读取
    @Value("${snowflake.worker-id}")
    private long workerId;

    @Value("${snowflake.datacenter-id}")
    private long datacenterId;

    /**
     * 将 Snowflake 注册为单例 Bean，全局使用
     * @return Snowflake instance
     */
~~~

### 3. 技术难点与解决方案：时钟回拨

雪花算法强依赖于系统时钟，它最大的一个潜在问题就是时钟回拨（Clock Skew）。如果服务器的系统时间被人为调后，或者因为NTP校时导致时间回拨，就可能生成重复的ID。

- 我们的解决方案: 在封装的ID生成器中加入时钟回拨的处理逻辑。
  a. 记录上一次生成ID的时间戳 lastTimestamp。
  b. 当请求新ID时，获取当前时间戳 currentTimestamp。
  c. 如果 currentTimestamp < lastTimestamp，说明发生了时钟回拨。
  d. 处理方式:
    - 直接抛出异常：通知调用方系统时钟异常，阻止继续生成错误的ID。这是最安全的方式。

我们项目中选择了第一种，即直接抛出异常，因为ID的正确性是首要的。

---

## 用户密码丢了可以修改密码？

我们项目目前主要采用手机验证码登录的方式，这种“无密码”设计在一定程度上简化了用户的心智负担。不过，对于需要设置和修改密码的场景，我们设计了一套完整的、安全的“忘记密码”流程。

这个流程的核心是通过可信的渠道（手机短信）来验证用户身份，并授权其进行密码重置操作。

### 1. 流程设计

整个流程分为两步：获取验证码和重置密码。

> **图示（文本化）**

~~~text
MySQL
Redis
短信网关
认证服务
客户端
用户
MySQL
Redis
短信网关
认证服务
客户端
用户
alt
[验证码正确]
[验证码错误或过期]
输入手机号，点击“获取验证码”
1. 请求发送验证码 (API: /auth/send-code)
2. 生成6位随机数字验证码
3. 存储验证码 (Key: "sms:reset_pwd:{phone}", Value: "code", TTL: 5min)
4. 调用三方接口发送短信
发送成功
提示验证码已发送
输入手机号、验证码、新密码
5. 请求重置密码 (API: /auth/reset-password)
6. 验证码校验 (GET "sms:reset_pwd:{phone}")
返回存储的验证码
7. 校验通过，对新密码进行BCrypt加盐哈希
8. 更新 user 表中的 password 字段
9. 删除已使用的验证码 (DEL "sms:reset_pwd:{phone}")
密码重置成功
返回 null 或不匹配
验证码错误
~~~

### 2. 关键技术点与安全考虑

1. 验证码存储与时效性:
  - 我们使用 Redis 来存储验证码，Key 包含业务前缀和手机号（如 sms:reset_pwd:138xxxxxx），Value 就是验证码本身。
  - 必须设置一个较短的过期时间，比如 5分钟，通过 Redis 的 SETEX 命令实现。这能有效防止验证码被暴力破解或被过期后使用。

2. 防止短信接口被滥刷 (SMS Bombing):

3. 密码存储安全:
  - 我们绝不会在数据库中存储明文密码。所有密码都必须经过加盐哈希处理。
  - 我们采用业界标准的 BCrypt 算法。BCrypt 会为每个密码自动生成一个随机的“盐”（Salt），并与密码一起进行哈希。即使两个用户设置了相同的密码，它们在数据库中的哈希值也是不同的，极大地提升了密码的安全性。

---

## 项目中为什么要用一致性哈希？

在我们的 IM 系统中，Real-TimeCommunicationService（即Netty长连接服务）是集群部署的，这就带来一个核心问题：当一个用户登录时，我们必须将他稳定地路由到集群中的某一个Netty实例上，并且在他后续的在线时间内，所有发给他的消息都要能准确地找到这个实例。一致性哈希就是我们用来解决这个分布式路由问题的关键技术。

### 1. 传统哈希路由的痛点

如果使用简单的哈希算法，比如 userId.hashCode() % N（N是服务器数量），会有一个致命缺陷：服务器数量N发生变化时，会导致大规模的路由失效。

- 场景: 假设有3台Netty服务器（N=3），用户A (hash=100) 被路由到 100 % 3 = 1 号服务器。

- 扩容: 为了应对高峰流量，我们增加了一台服务器，现在 N=4。此时，用户A的路由变成了 100 % 4 = 0 号服务器。

- 后果: 这意味着几乎所有用户的路由映射都会改变，导致大规模的连接断开和重连，对系统造成巨大冲击，这在生产环境中是不可接受的。

### 2. 一致性哈希的优势与原理

一致性哈希通过一个巧妙的设计——哈希环（Hash Ring），完美地解决了这个问题。

1. 构建哈希环: 我们可以想象一个由 2^32 个点组成的闭环。首先，我们将每个Netty服务器的IP地址或标识进行哈希，将它们散列到这个环上。

2. 对象路由: 当一个用户登录时，我们对他的 userId 进行同样的哈希，也得到环上的一个点。然后，从这个点开始顺时针寻找，遇到的第一个服务器节点，就是该用户应该连接的服务器。

3. 动态扩缩容:
  - 增加节点: 当在环上增加一个新节点D时，只有落在C和D之间（逆时针）的用户需要重新路由到D，其他所有用户的路由保持不变。
  - 移除节点: 当节点B宕机时，原来路由到B的用户会自动“顺延”到它的下一个节点C，同样只影响一小部分用户。

#### 2.1 架构示意图

> **图示（文本化）**

~~~text
路由关系 (扩容后)
User 1 --> Node D
User 2 --> Node C 不变
User 3 --> Node A 不变
Hash Ring (扩容后)
Node A
Node B
Node C
Node D - 新增
User 1
User 2
User 3
路由关系
User 1 --> Node B
User 2 --> Node C
User 3 --> Node A
Hash Ring
Node A
Node B
Node C
User 1
User 2
User 3
~~~

上图清晰地展示了，增加一个新节点D后，只有原本应路由到B、但现在离D更近的User 1需要变更路由，而User 2和User 3的路由完全不受影响。

### 3. 实现细节与流程

1. 服务发现: AuthenticationService 启动时，会通过 Nacos 订阅 Real-TimeCommunicationService 的服务列表，并监听其变化。

2. 构建哈希环: AuthenticationService 在内存中利用这个服务列表构建一个一致性哈希环。

3. 用户登录路由:
  - 用户登录请求到达 AuthenticationService。
  - AuthenticationService 使用 userId 在一致性哈希环上计算出目标Netty服务器的地址。
  - 将这个映射关系 (userId -> netty_server_address) 存入 Redis，并设置一个合理的过期时间（例如，比心跳周期稍长）。

4. 消息投递: MessagingService 在需要投递消息时，会先从 Redis 中查找接收者 userId 对应的 netty_server_address，然后通过HTTP请求将消息推送到该特定实例。

### 4. 技术难点与优化：虚拟节点

标准的一致性哈希算法可能会导致数据倾斜问题，即某些服务器节点因为在环上的位置“不好”，承载了远超其他节点的负载。

- 解决方案: 我们引入了虚拟节点（Virtual Nodes）机制。

- 原理: 我们不直接将物理节点（如192.168.1.100）哈希到环上，而是为每个物理节点创建多个虚拟节点（如 192.168.1.100#1, 192.168.1.100#2, ...）。然后将这些大量的虚拟节点散列到环上。

- 效果: 这样一来，环上的节点分布变得非常均匀，每个物理节点实际负责的范围是它所有虚拟节点负责范围的总和，从而使得负载在物理节点之间也趋于均衡。虚拟节点的数量越多，负载就越均衡。

---

## 数据库三范式，用到了哪个范式？有没有哪张表因为实际查询情况只满足第二范式的？

关于数据库范式的问题，我们在设计这个 IM 系统时进行了深入的思考和权衡。我们的设计哲学是默认遵循第三范式（3NF）以保证数据模型的规范性和一致性，但在核心、高频的查询场景下，会以性能为导向，适度进行“反范式”设计，并建立配套机制来管理数据一致性。

### 1. 范式回顾与整体设计

首先，简单回顾一下对三范式的理解：

- 第一范式 (1NF - Atomicity): 核心是“字段原子性”，确保表中的每一列都是不可再分的原子值。这是数据库设计的基础，我们项目所有表都遵循了 1NF。

- 第二范式 (2NF - Full Dependency): 核心是“消除部分依赖”，要求非主键列必须完全依赖于整个主键，主要针对联合主键。如果一个表是单主键，那么它只要满足 1NF 就自动满足 2NF。

- 第三范式 (3NF - No Transitive Dependency): 核心是“消除传递依赖”，要求任何非主键列都不能依赖于其他非主键列。

在本项目中，像 user (用户表)、friend (好友关系表)、session (会话表) 等绝大多数表，我们都严格遵循了第三范式。例如，friend 表只存储了 user_id 和 friend_id 的关系，而不会冗余好友的昵称、头像等信息，这些信息可以通过 JOIN user 表来获取，保证了数据无冗余和强一致性。

### 2. 核心案例分析：Message 表的反范式设计

本项目中最典型的反范式设计案例是 message (消息表)。它在设计上为了性能，刻意违背了第三范式，仅满足第二范式。

#### 2.1 问题：message_id → session_id → session_type 的传递依赖

在 message 表中，主键是 message_id。表里有一个 session_id 字段，同时还有一个 session_type 字段（1代表单聊，2代表群聊）。

这里的依赖关系是：message_id 决定了 session_id，而 session_id 在 session 表中决定了 session_type。这就构成了一个传递函数依赖 (Transitive Functional Dependency)。

> **图示（文本化）**

~~~text
关联
message
bigint
message_id
PK
bigint
sender_id
bigint
session_id
FK
tinyint
session_type
冗余字段, 造成对主键的传递依赖
text
content
session
bigint
session_id
PK
varchar
name
tinyint
type
真正的 session 类型
~~~

按照第三范式的要求，message 表中不应该存在 session_type 字段。但我们选择在这里冗余它，是出于对核心业务场景性能的极致追求。

#### 2.2 动因：为核心查询路径进行性能优化

IM 系统最高频的操作之一就是拉取历史消息。当用户打开一个聊天窗口，客户端会请求这个会话的历史消息。这个场景下，前端 UI 通常需要根据是单聊还是群聊来展示不同的布局（例如群聊中需要显示发送者昵称和头像）。

- 如果遵循 3NF： 查询需要 JOIN 操作。

~~~sql
-- 遵循 3NF 的查询方式
SELECT m.message_id, m.content, s.type as session_type
FROM message m
JOIN session s ON m.session_id = s.id
WHERE m.session_id = ?
ORDER BY m.created_at DESC
LIMIT 20;
~~~

- 在高并发场景下，尤其是当 message 表数据量达到亿级别时，频繁的 JOIN 操作会带来巨大的性能开销，增加数据库 CPU 负载和磁盘 I/O，导致查询延迟升高。

- 采用反范式设计后： 查询变得极其高效。

~~~sql
-- 反范式设计后的查询方式
SELECT m.message_id, m.content, m.session_type
FROM message m
WHERE m.session_id = ?
ORDER BY m.created_at DESC
LIMIT 20;
~~~

- 通过冗余 session_type，我们用空间换时间，将一个多表 JOIN 查询优化成了一个单表的索引查询，极大地提升了消息拉取接口的性能和并发能力。

下面这个时序图可以更清晰地展示两种方案在服务端的处理流程差异：

> **图示（文本化）**

~~~text
"Session DB (MySQL)"
"Message DB (MySQL)"
"API 服务"
Client
"Session DB (MySQL)"
"Message DB (MySQL)"
"API 服务"
Client
遵循第三范式 (3NF) 的查询
反范式设计 (2NF) 的查询
拉取消息 (session_id)
SELECT message_id, content FROM message WHERE session_id = ?
返回消息列表
SELECT type FROM session WHERE id = ?
返回 session_type
组装后返回完整消息
拉取消息 (session_id)
SELECT message_id, content, session_type FROM message WHERE session_id = ?
直接返回完整消息
直接返回完整消息
~~~

### 3. 延伸思考：朋友圈等模块的设计

类似的设计思想也体现在其他模块的考量中。例如在 moment_comment (朋友圈评论表) 中，我们只存储了 user_id，而没有冗余评论者的昵称和头像。

- 权衡点： 朋友圈的评论和点赞列表是读多写少的场景，冗余用户信息可以减少 JOIN。

- 最终选择： 我们没有这样做。因为用户的昵称、头像是高频易变数据，冗余它们会带来巨大的数据同步成本和一致性问题。相比之下，会话类型是几乎不变的。因此，对于朋友圈场景，我们选择在业务层进行聚合，通过一次性查询所需用户信息并在内存中（或使用 Redis 缓存）进行组装，来平衡性能和一致性。

### 4. 总结

总而言之，我的数据库设计遵循了以下原则：

1. 以 3NF 为基准： 保证了大部分数据模型的规范性。

2. 识别核心路径： 对IM系统的消息收发这一核心、高频路径进行重点分析。

3. 性能驱动反范式： 在关键路径上，通过“冗余低频可变数据”的方式进行反范式设计，用空间换时间，大幅提升读取性能。

4. 一致性保障： 对反范式设计带来的数据一致性问题有清晰的认知，并根据业务特性（如数据的不变性）选择合适的策略（业务约束或最终一致性）来解决。

这种在理论范式和工程实践之间进行务实权衡的思路，贯穿了我们整个系统的设计。

---

## 如何检测 TCP 连接已经断开？

对于这个问题，我们需要从“为什么会断连”和“如何有效感知”两个层面来回答，并展示一个健壮、低开销的解决方案。

### 1. 操作系统层：TCP Keep-alive (基础保障)

首先，TCP 协议栈自身提供了 keep-alive 机制。它的原理是，当一条 TCP 连接上长时间没有数据传输时，内核会自动发送一个“探测报文” (probe) 到对端。如果收到响应，则证明连接依然存活；如果在规定时间内没有收到响应，并经过多次重试后，内核就会判定连接丢失，并通知上层应用。

#### 1.1 技术细节与配置

在 Linux 系统中，这套机制由三个核心参数控制：

- tcp_keepalive_time (默认 7200s): 连接闲置多久后开始发送第一个探测包。

- tcp_keepalive_intvl (默认 75s): 探测包发送的间隔时间。

- tcp_keepalive_probes (默认 9次): 发送多少次探测包未收到响应后，才认为连接已失效。

#### 1.2 局限性

虽然 keep-alive 能在底层解决“僵尸连接”问题，但它对于我们的 IM 应用来说存在几个致命缺陷：

1. 感知延迟过高：默认配置下，从连接闲置到最终判定断开，可能需要 7200s + 75s * 9次 ≈ 2.1 小时。这对于要求实时性的 IM 系统是完全无法接受的。

2. 不够灵活：它是针对 TCP 连接的全局配置，我们无法为不同的应用或连接设置不同的心跳策略。

3. 无法感知应用状态：keep-alive 只能检测到 TCP 层的连接存活，无法判断客户端应用是否崩溃或卡死，而这在移动端是很常见的情况。

因此，TCP Keep-alive 只能作为一种“兜底”机制，真正的核心在于应用层的心跳。

### 2. 应用层：双向心跳检测 (核心机制)

鉴于 Keep-alive 的局限性，我们在应用层，即 Netty 的 Pipeline 中设计了更精细、更高效的双向心跳机制。这不仅能快速检测连接状态，还能有效应对网络防火墙、NAT 超时等问题。

#### 2.1 Netty IdleStateHandler 配置

如你的代码所示，我们使用了 Netty 提供的 IdleStateHandler 来检测连接的空闲状态。

> **图片**：只读页面未提供可导出的图片内容。

~~~java
// 在 ChannelInitializer 中配置
pipeline.addLast(new IdleStateHandler(5 * 60, 0, 0, TimeUnit.SECONDS));
// readerIdleTime: 5 * 60 秒，表示如果服务器在 5 分钟内没有接收到客户端的任何数据，就会触发一个读空闲事件。
// writerIdleTime: 0，表示不关心服务器的写空闲状态。
// allIdleTime: 0，表示不关心读或写的总空闲状态。
~~~

这里的配置非常关键：我们在服务端只关心读空闲。因为对于服务器而言，只要能持续收到客户端的消息（哪怕只是心跳包），就说明连接是健康的。

#### 2.2 心跳处理流程 (Ping-Pong 模式)

仅仅检测到空闲并直接关闭连接是不够健壮的。网络可能会有暂时的抖动，直接关闭会导致误判。因此，我们采用了经典的 “Ping-Pong” 模式。

~~~java
@Override
public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof IdleStateEvent) {
        IdleStateEvent event = (IdleStateEvent) evt;
        if (event.state() == IdleState.READER_IDLE) {
            // 触发了读空闲
            log.warn("通道 [{}] 触发读空闲，准备发送 PING... 用户ID: {}", ctx.channel().remoteAddress(), ChannelManager.getUserId(ctx.channel()));

            // 1. 向客户端发送一个 PING 心跳包
            //    这个心跳包应该是我们自定义协议的一部分
            ctx.channel().writeAndFlush(new PingMessage());

            // 2. 可以在这里增加一个计数器，如果连续多次发送 PING 都未收到 PONG，再关闭连接
            //    int retryCount = getRetryCount(ctx);
            //    if (retryCount > MAX_RETRIES) {
            //        log.error("通道 [{}] 多次心跳无响应，主动关闭连接。", ctx.channel().remoteAddress());
            //        offline(ctx); // 执行下线逻辑
            //    } else {
            //        incrementRetryCount(ctx);
            //    }
~~~

#### 2.3 客户端心跳

这个机制需要客户端的配合。客户端也应该设置一个 IdleStateHandler，但它关心的是写空闲 (writerIdleTime)。当客户端在一定时间内没有向服务器发送任何业务消息时，它会自动发送一个 PING 包给服务器。这有两个好处：

1. 主动探测：主动告知服务器“我还活着”。

2. 链路保活：防止中间的路由器或防火墙因为连接长时间不活跃而关闭 NAT 映射，导致连接中断。

#### 2.4 流程图

下面是这个双向心跳机制的流程图：

> **图示（文本化）**

~~~text
Server
Client
Server
Client
WebSocket 连接已建立
4 分钟内无业务消息发送
loop
[客户端写空闲检测]
5 分钟内未收到任何数据(包括 PING)
等待PONG超时 (例如 10 秒)
alt
[Client 正常在线]
[Client 掉线或应用卡死]
loop
[服务端读空闲检测]
启动 writerIdle 定时器 (例如 4 分钟)
发送 PING 心跳包
(无需回复，收到数据即重置读空闲计时器)
启动 readerIdle 定时器 (例如 5 分钟)
触发 IdleStateEvent (READER_IDLE)
发送 PING 心跳包 (进行最后确认)
立即回复 PONG 心跳包
收到PONG，连接恢复正常
关闭 TCP 连接
执行用户下线逻辑(清除Redis路由等)
~~~

### 3. 技术难点与考量

1. 心跳间隔设置：心跳间隔是一个权衡。间隔太短会增加服务器和客户端的流量与电量消耗；间隔太长则无法及时发现断连。通常客户端心跳间隔（如4分钟）应略小于服务端检测间隔（如5分钟），以确保在服务端超时前，客户端能主动发送心跳。

2. "假死"与网络抖动：我们采用的 "Ping-Pong + 超时" 机制，而不是直接在 READER_IDLE 时关闭连接，就是为了应对网络抖动造成的“假死”现象，增加系统的鲁棒性。

通过这套组合拳，我们既利用了操作系统底层的保障，又在应用层实现了灵活、快速、可靠的连接状态管理，完全能够满足 IM 系统的实时性要求。

---

## 如何保证消息不丢失？

在保证消息不丢失方面，有以下几种方案和策略

1. 在消息转发模块收到一条消息时，会先将其放入到kafka中，再进行消息的转发，这样如果放入到kafka失败了，那么证明消息持久化存储就失败了，直接返回报错；

2. 在kafka层面上保证消息的不丢失，具体详情查看kafka如何保证消息不丢失；

3. 需要保证当用户收到一个消息时，他如何保证其前面所有的消息都已经收到了呢？这里我们可以让客户端在一个聊天室发送消息时，将上一条消息的 ID 也带上，这样当接受者收到一条消息时，他就可以对比起上一条收到的消息ID与本条消息中携带的上一条消息ID是否相同，如果不同则可以去离线消息模块中查询其未获得消息，保证消息的可靠性；

4. 当用户收到一条消息时，需要向服务端发送 ACK 信息，告诉服务端其已经收到消息，同时服务端应该在本地维护一个队列，存储尚未收到 ACK 的消息，并且可以设计的像 TCP 那样，如果没有收到 ACK 消息，对消息进行超时重传；

---

## 项目中有没有实现一个客户端断线重连的功能，在项目中要怎么基于 Netty 实现这个断线重连功能

在基于 Netty 的 IM 项目中实现客户端断线重连功能，可以通过 Ping-Pong 心跳机制 + 指数退避重连策略来实现，客户端定时（如每30秒）发送 Ping 消息，服务端收到后回复 Pong 响应；若客户端连续 3 次未收到 Pong（约 90 秒超时），则先关闭当前连接，随后触发指数退避重连（首次1秒，之后2秒、4秒、8秒...逐步延长，避免重连风暴），同时需注意在连接失效时取消心跳任务防止内存泄漏。这里的指数退避重连是指间隔多久客户端触发重连机制，可以避免加重服务器压力、适应网络恢复的渐进性和节省客户端资源。

---

## 我们用户登录后会去批量拉取离线消息，假设这个消息很多，用户获取消息的时间可能要几秒钟，这时候有其他用户持续给这个用户发送消息。要怎么保证用户实际拉取的消息要早于消息拉取期间收到的消息。

这个问题是 IM 系统中一个非常经典且核心的挑战，它涉及到分布式ID生成、消息同步协议和客户端状态管理等多个方面。我们设计的方案，核心是 “基于会话的单调递增序列号（Sequence ID）” 配合 “客户端拉取-确认” 模型来解决。

### 1. 核心设计：基于会话的 Sequence ID

首先，我们需要一个机制来为每一条消息打上一个绝对有序的标记。

- 摒弃雪花算法的直接使用：雪花算法（Snowflake）生成的 ID 虽然全局唯一且趋势递增，但它并不能保证在分布式环境下对同一个会话（单聊或群聊）的消息ID是严格单调递增的。比如，处理同一个会话消息的两台服务器发生了微小的时钟回拨，就可能导致后到的消息获得一个更小的 ID。

- 引入会话级 Sequence ID (seq)：我们的方案是为 每一个会话（Session） 维护一个独立的、原子递增的计数器。这个 seq 只用于保证单个会话内的消息顺序，而全局唯一的 message_id（可以使用雪花算法生成）则用于数据库主键和全局消息追踪。

#### 1.1 seq 的生成与存储

推荐使用 Redis 的 INCR 命令来实现 seq 的原子递增，因为它性能极高且能保证原子性。

- Redis Key 设计: seq:session:{session_id}

- 消息处理流程:
  a. MessagingService 收到一条发往 session_id 的新消息。
  b. 执行 Redis 命令 INCR seq:session:{session_id}，获得这条消息的 seq。
  c. 将 seq 和 message_id (雪花算法生成) 一同存入消息体。
  d. 将完整的消息对象（包含 seq）投递到 Kafka，并持久化到 MySQL。

#### 1.2 数据库表结构优化建议

为了支持高效的 seq 拉取，message 表的结构需要优化。目前的 idx_session_id_created_at 索引在消息量巨大时，基于时间的分页效率不高。可以修改如下：

~~~sql
-- 建议的 message 表结构
CREATE TABLE `message` (
  `message_id` bigint NOT NULL COMMENT '消息全局唯一ID (雪花算法)',
  `session_id` bigint NOT NULL COMMENT '会话ID',
  `seq` bigint NOT NULL COMMENT '会话内单调递增序列号',
  -- ... 其他字段 ...
  PRIMARY KEY (`message_id`),
  -- 核心索引，用于高效拉取离线消息
  UNIQUE KEY `uk_session_seq` (`session_id`, `seq`)
) ENGINE=InnoDB;
~~~

这个联合唯一索引 uk_session_seq 是整个方案高效运作的基石。

### 2. 消息同步流程：拉取与实时并行处理

下面是用户登录后，如何保证“先处理完离线消息，再展示实时消息”的详细流程。更推荐客户端侧缓冲的方案，因为它对服务端更友好，扩展性更强。

#### 2.1 流程图解

> **图示（文本化）**

~~~text
消息服务
其他用户
Redis
实时通信服务 (Netty)
离线存储服务
认证服务
客户端
消息服务
其他用户
Redis
实时通信服务 (Netty)
离线存储服务
认证服务
客户端
--- 开始同步离线消息 ---
par
[在拉取期间，有新消息到达]
收到离线消息: 直接处理并渲染UI
收到实时消息 (seq > max_seq): 存入内存缓冲区(Buffer)
更新本地 last_synced_seq
--- 离线消息同步完成 ---
登录(user, pass)
登录成功, 返回 Token
与 RTC_Svc 建立 WebSocket 连接并认证
存储用户路由信息 (user_id -> channel_id)
拉取离线消息(携带各会话的 last_synced_seq)
获取各会话当前的最大 seq (max_seq)
计算需要拉取的消息范围 (last_synced_seq, max_seq]
返回 (last_synced_seq, max_seq] 之间的离线消息
发送实时消息
INCR seq:session:{id} (获得 new_seq > max_seq)
推送实时消息 (含 new_seq)
推送实时消息
**核心处理逻辑**
离线消息全部渲染完毕
从内存缓冲区中取出暂存的实时消息
按 seq 排序后，渲染到UI
~~~

#### 2.2 客户端实现细节

客户端的逻辑是关键，它需要维护几个状态：

1. last_synced_seq Map: 一个 Map<Long, Long>，存储每个 session_id 已经同步到的最大 seq。此状态需要本地持久化。

2. sync_target_seq Map: 一个 Map<Long, Long>，记录本次登录时，服务端告知的每个会话的最新 seq。

3. realtime_buffer Map: 一个 Map<Long, List<Message>>，用于暂存同步期间收到的实时消息。

伪代码逻辑：

~~~java
// 客户端消息处理伪代码
class MessageHandler {
    Map<Long, Long> lastSyncedSeq; // 本地已同步的seq
    Map<Long, Long> syncTargetSeq; // 本次同步的目标seq
    Map<Long, List<Message>> realtimeBuffer; // 实时消息缓冲区

    // 收到消息（包括离线拉取和实时推送）
    void onMessageReceived(Message msg) {
        long sessionId = msg.getSessionId();
        long seq = msg.getSeq();

        // 如果该会话的离线消息还未同步完成
        if (lastSyncedSeq.get(sessionId) < syncTargetSeq.get(sessionId)) {
            if (seq > syncTargetSeq.get(sessionId)) {
                // 这是新来的实时消息，seq超过了本次同步的目标，暂存
                bufferMessage(msg);
            } else {
                // 这是离线消息，直接处理
                renderMessage(msg);
~~~

### 3. 对比服务端缓冲方案

服务端缓冲方案也是可行的，但它有几个缺点：

- 状态维护复杂: 服务端需要为每个正在同步的用户维护一个临时消息队列，这会增加 Real-TimeCommunicationService 的内存压力和状态管理的复杂度。

- 可靠性问题: 如果 Real-TimeCommunicationService 实例在推送缓冲消息前宕机，这部分消息可能会丢失（除非做了持久化，但这会进一步增加系统复杂度）。

- 扩展性差: 这种有状态的设计不利于服务的水平扩展。

相比之下，客户端缓冲方案将状态下放到客户端，服务端是无状态的，只需负责生成 seq 和推送消息，架构更清晰，扩展性也更好。

通过以上“会话级 Seq + 客户端拉取确认”的方案，我们可以完美解决在拉取离线消息时，与新收到的实时消息之间的顺序性问题，确保用户消息流的体验是连贯且正确的。

## 消息丢失了怎么排查？

问题描述：
想象一个场景：线上用户反馈，IM聊天时偶尔会发生消息丢失，即A发送了消息，但B没有收到。如果交给你来排查这个问题，你的排查思路和步骤是怎样的？你会关注哪些关键日志或监控指标？

参考答案：
我会遵循自顶向下、分段排查的原则，从用户端到服务端，逐一排查消息流经的各个环节。

Step 1: 明确问题范围，收集信息

- 向用户/客服收集信息：
  - 丢失消息的具体时间点？
  - 是单聊还是群聊？
  - 发送方和接收方的用户ID？
  - 当时的网络环境如何（WiFi/4G）？
  - 是个例还是普遍现象？

Step 2: 全链路日志追踪

- 引入分布式追踪系统：最好系统已接入SkyWalking或Zipkin。为每条消息生成一个唯一的traceId，从客户端发出时就带上，贯穿整个后端链路。

- 查询日志：根据traceId或用户ID和时间点，查询整个链路的日志：
  a. 发送端A日志：确认消息是否已从客户端成功发出，是否收到服务端的ACK。
  b. 接入网关日志：确认网关是否收到A的消息，并成功投递到消息队列。记录投递到Kafka的Topic和Partition。
  c. 消息队列(Kafka)监控：检查Kafka Broker当时是否正常，该Topic和Partition是否有积压或错误。
  d. 业务逻辑服务日志：确认消费端是否从Kafka成功消费到这条消息，并进行了处理（如持久化到数据库、查询接收方B的路由信息）。

Step 3: 分段排查可能的原因

- 发送端 -> 网关：
  - 可能原因：A网络问题，消息未发出；A的连接已“假死”，服务端未感知。
  - 排查点：查看A的本地日志，查看网关的心跳日志。

- 网关 -> Kafka：
  - 可能原因：网关投递Kafka失败，但没有妥善处理（如重试、告警）。
  - 排查点：网关日志中是否有Kafka生产异常。

- Kafka -> 业务服务：
  - 可能原因：Kafka消息积压严重，消费延迟；消费者发生异常（如反序列化失败）并跳过了该消息。
  - 排查点：Kafka监控（Lag），消费者日志。

- 业务服务 -> 推送网关：
  - 可能原因：路由信息查询失败或错误，找不到B所在的网关；业务逻辑bug。
  - 排查点：业务服务日志，检查对Redis的调用。

- 推送网关 -> 接收端：
  - 可能原因：B的连接已断开，但路由信息未及时更新；B的网络问题，消息已发出但客户端未收到；TCP滑动窗口或缓冲区问题。
  - 排查点：推送网关日志，B客户端日志。

Step 4: 监控指标辅助

- 客户端：网络状态、消息发送成功率。

- 网关：在线连接数、消息收发QPS、Kafka投递成功率。

- Kafka：Broker状态、Topic消息积压（Lag）、ISR同步状态。

- 业务服务：CPU/内存使用率、GC情况、RPC调用成功率和延迟。

总结与复盘

- 定位到根本原因后，修复bug，增加必要的日志和监控。

- 复盘整个排查过程，思考如何优化日志规范、完善监控告警体系，以便未来能更快地定位问题。例如，强制要求所有跨服务调用都传递traceId。

## Kafka 相关

### 5. 消息队列的作用什么？

  a. 解耦
  b. 异步通信
  c. 削峰填谷

#### 5.1

Kafka 集群的架构是什么样子的？

> **图片**：只读页面未提供可导出的图片内容。

#### 5.2

Kafka 消费者组和生产者组是什么？

##### 定义与核心作用

- 定义：消费者组由多个消费者实例组成，共同订阅并消费一个或多个主题（Topic）的消息。

- 核心作用：实现消息的并行消费和负载均衡，确保高吞吐量和容错性。

##### 关键机制

1. 分区分配：
  - 每个分区（Partition）只能被同一消费者组内的一个消费者消费。
  - 若消费者数量 > 分区数，多余的消费者处于闲置状态。
  - 示例：主题有3个分区，消费者组有3个消费者 → 每个消费者负责1个分区。

2. 重平衡（Rebalance）：
  - 触发条件：消费者加入/离开、分区数量变化。
  - 过程：重新分配分区所有权，确保负载均衡。

3. 偏移量（Offset）管理：
  - 消费者组通过提交偏移量记录消费进度（存储于 __consumer_offsets 主题）。
  - 支持自动提交（默认）或手动提交（精确控制）。

##### 典型场景

- 横向扩展：增加消费者实例提升消费速度。

- 容灾恢复：消费者故障后，其他消费者接管其分区。

- 多租户隔离：不同消费者组独立消费同一主题（如日志分发）。

！！切记：一个主题中的一个 Partion 只能被同一个消费者组中的一个消费者消费！！！如果一个消费者组中的消费者数量大于 Partion 数量，则会导致多出来的消费者无法消费到消息！！

##### 常见问题解答

###### Q1：消费者组内多个消费者如何避免重复消费？

- 答案：每个分区仅由一个消费者消费，偏移量由组统一管理，天然避免重复。

###### Q2：生产者如何实现消息的顺序性？

- 答案：对同一分区（如相同Key的消息），Kafka保证顺序写入；跨分区无法保证。

###### Q3：消费者组能否消费多个主题？

- 答案：可以，消费者组可订阅多个主题，按分区分配策略统一管理。

#### 5.3

Kafka 如何保证消息的的不丢失？

从以下三个方面进行保证：

1. 生产者Producer，使用带回调通知的send(msg,callback)方法，并且设置acks = all 。它的消息投递要采用同步的方式。Producer要保证消息到达服务器，就需要使用到消息确认机制，也就是说，必须要确保消息投递到服务端，并且得到投递成功的响应，确认服务器已接收，才会继续往下执行。

2. 设置broker中的配置项unclean.leader.election.enable = false，保证所有副本同步。同时，Producer将消息投递到服务器的时候，我们需要将消息持久化，也就是说会同步到磁盘。注意，同步到硬盘的过程中，会有同步刷盘和异步刷盘。如果选择的是同步刷盘，那是一定会保证消息不丢失的。就算刷盘失败，也可以即时补偿。但如果选择的是异步刷盘的话，这个时候，消息有一定概率会丢失。

3. 就是消费者Consume。设置enable.auto.commit为false。在Kafka中，消息消费完成之后，它不会立即删除，而是使用定时清除策略，也就是说，我们消费者要确保消费成功之后，手动ACK提交。如果消费失败的情况下，我们要不断地进行重试。所以，消费端不要设置自动提交，一定设置为手动提交才能保证消息不丢失。

#### 5.4

Kafka 为什么性能高？比 RocketMQ 吞吐率高的根本原因是什么？

1. 顺序磁盘 I/O 的极致利用

- 日志追加写入（Append-Only Log）：
  Kafka 将消息按顺序追加到磁盘文件，完全避免随机写。
  优势：顺序 I/O 性能远超随机 I/O（机械硬盘顺序写速度可达 100MB/s，随机写仅 1MB/s）。

- RocketMQ 的妥协：
  虽然 RocketMQ 也采用顺序写入（CommitLog），但需维护 索引文件（ConsumeQueue） 支持消息查询，引入轻微随机读，降低 I/O 效率。

2. 零拷贝（Zero-Copy）技术（核心、根本）：

> **飞书画板**：只读页面未提供可提取的画板文本。

由于 Kafka 仅提供基础功能，并不提供死信队列、延迟队列等功能

- Kafka 的实现：
  使用 sendfile （函数返回字节数）系统调用，数据直接从 页缓存（Page Cache） 传输到网卡，跳过用户态与内核态的数据拷贝。
  效果：减少 2 次内存拷贝（传统流程需 4 次），降低 CPU 和内存占用。

- RocketMQ 的局限：
  虽然 RocketMQ 也采用了零拷贝技术，但是需要由于 RocketMQ 实现了死信队列、延迟队列等高级功能，所以必然需要从内核态将数据拷贝到用户态，因此其系统调用使用的是 mmap（函数返回了消息的具体内容），得到的具体消息内容，因此其零拷贝实际进行了三次数据的拷贝，而 kafka 仅进行了两次。

3. 页缓存（Page Cache）优先策略

- Kafka 的设计哲学：
  - 数据读写直接依赖操作系统的页缓存，避免 JVM 堆内存管理开销（如 GC 停顿）。
  - 消息写入时仅追加到页缓存，由操作系统异步刷盘，最大化 I/O 吞吐。

- RocketMQ 的取舍：
  默认使用 堆外内存（DirectBuffer） 存储消息，减少 GC 影响，但需手动管理内存，复杂度高。

4. 批量处理与高效压缩

- 批量写入与拉取：
  - 生产者积累一批消息后批量发送，减少网络请求次数。
  - 消费者批量拉取消息，提升单次请求效率。

- RocketMQ 的平衡点：
  默认配置更偏向低延迟（如批量阈值较小），牺牲部分吞吐量。

5. 分区（Partition）并行模型

- 无锁化设计：
  每个 Partition 独立处理读写，天然支持水平扩展。
  生产者、消费者在 Partition 级别无竞争，避免锁开销。

- RocketMQ 的队列（Queue）模型：
  类似 Kafka 的 Partition，但事务消息、顺序消息需额外协调，可能引入锁竞争。

6. 异步副本与一致性权衡

- Kafka 的 ISR 机制：
  异步复制数据到 Follower，只需 Leader 确认写入即可响应生产者，高吞吐优先。
  允许副本短暂不一致（通过 min.insync.replicas 控制可靠性）。

- RocketMQ 的同步双写：
  主从副本同步写入后才返回确认（SYNC_MASTER 模式），强一致性优先，吞吐量降低。

#### 5.5

Kafka 如何保证消息的顺序消费？

Kafka只能保证单个分区内的消息顺序，而无法保证跨分区的顺序。这一点很重要，因为很多用户可能会误以为只要在一个主题下就能保证全局顺序，但实际上需要依赖分区的设计。

接下来需要详细解释如何利用分区来保证顺序。比如生产者通过指定消息键（Key）将相关消息发送到同一分区，消费者则按分区顺序消费。这时候可能会提到生产者发送消息时使用相同的Key，确保进入同一分区，消费者单线程处理每个分区的消息，或者使用多线程但确保同一分区的消息由同一线程处理。

同时，用户可能想知道如何应对消费者组内的多个消费者实例，这时候需要解释分区的分配机制，每个分区只能被一个消费者实例消费，从而保证顺序。如果有多个消费者实例，每个实例处理不同的分区，而每个分区内的消息是顺序的。

## WebSocket 相关

### 1. 什么是 WebSocket？与 Http 协议的区别是什么？

WebSocket 是一种应用层协议，用于在 Web 应用程序中创建实时、双向的通信通道。传统的 HTTP 请求通常是一次请求一次响应的，而 WebSocket 则可以建立一个持久连接，允许服务器即时向客户端推送数据，同时也可以接收客户端发送的数据。WebSocket 相比于传统的轮询或长轮询方式，能够显著减少网络流量和延迟，提高数据传输的效率和速度。

WebSocket 可以在浏览器和服务器之间建立一条双向通信的通道，实现服务器主动向浏览器推送消息，而无需浏览器向服务器不断发送请求。其原理是在浏览器和服务器之间建立一个“套接字”，通过“握手”的方式进行数据传输。由于该协议需要浏览器和服务器都支持，因此需要在应用程序中对其进行判断和处理。

WebSocket 建立在 HTTP 协议之上，所有的 WebSocket 请求都会通过普通的 HTTP 协议发送出去，然后在服务器端根据 HTTP 协议识别特定的头信息 Upgrade，服务端也会判断请求信息中 Upgrade 是否存在。 这里面 HTTP 是必不可少的，不然 WebSocket 根本无法建立。特别的，WebSocket 在握手时采用了 Sec-WebSocket-Key 加密处理，并采用 SHA-1 （hash）签名。

一旦建立了 WebSocket 连接，客户端和服务器端就可以互相发送二进制流或 Unicode 字符串。所有的数据都是经过 mask 处理过的，mask 的值是由服务器端随机生成的。在数据进行发送之前，必须先进行 mask 处理，这样可以有效防止数据被第三方恶意篡改。

最后需要说明一下的是，WebSocket 的通信协议是基于帧（数据包）的。在数据发送时，一个完整的数据包可以分为多个帧进行发送（拆包/沾包），而每一个帧都包含了数据的一部分，同时还包含了帧头信息。

> **图片**：只读页面未提供可导出的图片内容。

> **图片**：只读页面未提供可导出的图片内容。

WebSocket 与 Http 协议的区别

1. HTTP (Hypertext Transfer Protocol) 是一个基于请求和响应模式的协议，最早用于 Web 应用。

2. WebSocket 是一种双向通信协议，可以在客户端和服务器之间建立持久的连接，以实现实时通信。

3. WebSocket 协议在建立连接时需要使用 HTTP 协议。 具体来说，当客户端想要建立 WebSocket 连接时，它们需要通过 HTTP 请求发送一个握手请求。如果服务器同意握手，它将发送一个握手响应，HTTP 协议随后会升级到 WebSocket 协议。

4. HTTP 和 WebSocket 协议在用途上也有所不同。HTTP 协议主要用于客户端和服务器之间的请求和响应通信，而 WebSocket 协议主要用于实现实时通信和服务器推送。通俗的说，HTTP 协议是“一问一答”，WebSocket 协议是“对话”。

### 2. Http 是如何升级为 WebSocket 的？

> **图片**：只读页面未提供可导出的图片内容。

> **附件**：webSocket.pcapng · 13.92KB

### 3. 为什么 WebSocket 可以进行全双工模式的消息传输，而 Http 不可以？

你可能会问，既然HTTP 与WebSocket 底层都是通过 TCP 进行消息传输的，为什么 Http 协议却不能服务端主动给客户端进行主动进行消息推送？其实这并不是 TCP 不支持全双工的工作模式，HTTP的设计初衷是文档传输，不是实时通信，所以没有考虑持续的双向通信需求。因此这是由于应用层协议设计的问题导致的 Http 协议不支持全双工的工作模式。

### 4. 什么是 TCP 的粘包和拆包？

在回答这个问题之前我们需要明确沾包和拆包的定义是什么，如下

- 粘包（Sticky Packets）
  发送方连续发送多个数据包时，接收方可能一次性收到多个包"粘"在一起的数据（如：发送 A|B，接收AB）。

- 拆包（Unpacking）
  发送方的一个数据包可能被拆分成多次接收（如：发送 C，接收为 C1 和 C2）

为什么会出现沾包和拆包这个问题呢？其根本原因是 TCP 面向的是字节流（字节流是你可以认为是一个一直在流水的水管）的协议，TCP 压根就不知道你想发送的每个数据包之间的边界是什么，那么如果你发送的一段信息过长，超过了 TCP 的缓冲区，那就会导致你的一个大的数据包被拆分为若干小的数据包进行发送，这就是拆包；当你的一个数据包过小，那么 TCP 会认为频繁发送小数据包会频繁进行网络传输，因此会使用 Nagle 算法合并小数据包，这就是沾包。

解决方案

###### (1) 固定消息长度

- 每个数据包长度固定（如1024字节），不足部分用空位填充。

- 缺点：浪费带宽，不灵活。

###### (2) 分隔符协议

- 用特殊字符（如\n、\r\n）标记消息边界。

- 示例：Redis协议使用\r\n分割命令。

###### (3) 消息头声明长度

- 在消息头部添加长度字段（如4字节int），明确后续数据的长度。

- 示例：HTTP协议通过Content-Length头指定正文长度。

在本项目中 netty 框架已经帮我们在应用层方面解决了这个问题，Netty框架：内置解码器如LengthFieldBasedFrameDecoder 自动处理粘包/拆包。

### 5. WebSocket 与 Socket 套接字的区别是什么？

首先，Socket套接字，属于传输层的API，像TCP和UDP都是基于Socket实现的。它允许应用程序通过网络进行通信，是操作系统提供的接口。而WebSocket是一种应用层协议，建立在HTTP之上，提供全双工通信，通常用于浏览器和服务器之间的实时交互。换句话说二者不属于同一层面的东西。

### 6. keep-alive 是什么？

TCP Keepalive机制 是一种用于检测空闲连接是否存活的传输层保活策略。它通过定期发送探测报文，确认对端是否仍在线，避免因网络中断或对端崩溃导致连接长期占用资源。

1. Keepalive 的工作原理

###### (1) 触发条件

- 空闲超时：当连接在 tcp_keepalive_time 时间内无数据交互，触发Keepalive探测。

- 探测过程：发送空数据包（ACK标志，序列号为当前期望值减1），强制对端响应。

###### (2) 探测流程

1. 发送探测包：每隔 tcp_keepalive_intvl 秒发送一次探测包。

2. 等待响应：若收到有效ACK，重置计时器，连接保持。

3. 失败判定：连续 tcp_keepalive_probes 次无响应，判定连接死亡，发送RST断开。

~~~bash
# 临时生效（重启后失效）
sysctl -w net.ipv4.tcp_keepalive_time=600    # 空闲10分钟后探测
sysctl -w net.ipv4.tcp_keepalive_intvl=30    # 每隔30秒发送探测
~~~

### 7. 网线被一脚踢断了，TCP 连接会发生什么事情？

首先，我们要回忆一下 TCP 的基础知识，TCP 在会在什么情况下断开一个链接呢？

1. 连接双方的一端主动进行 TCP 连接的关闭，就是我们常说的发送 FIN 包，进行四次挥手；

2. 重传次数达到阈值，TCP有重传机制，如果多次重传失败，导致 TCP 连接断开；

3. Keepalive 机制如果启用，在探测无响应后也会断开连接；

4. 接收到 RST 终止报文

......

既然我们已经知道了 TCP 链接可能被关闭的情况，那么我们就可以对这个问题进行回答。首先分情况进行讨论，如果是网线被踢开了，那么我们软件系统其实是无法马上感知物理链路已经断开了，TCP 连接状态仍然ESTABLISHED状态，此时如果你马上把断掉的网线插回去，那么其实 TCP  会当作什么都没发生，因为他根本没有感知到刚才的那次网线的断开。如果你在网线断开的时候，进行了消息的发送，那么由于物理网络的断开，那么你这条消息一定是发送不出去的，会触发 TCP 的重传机制，进行重传，如果你在重传时，将网线插回，那么这个 TCP 连接仍然不会断开，因为重传成功了，否则 TCP 连接将会被断开。那么如果网线一直没有被插回去，并且客户端与服务端一直没有通过这个 TCP 进行通信，那么这个 TCP 连接将会一直存在，那有没有一个好的方法解决这个问题呢？答案是开启Keepalive机制，Keepalive 机制简单的说，就是定期的向 TCP 连接中发送探测数据包，来确保这条 TCP 连接是正常存活的，就像我们在 IM 项目的长链接模块中，加入了心跳机制一样。如果 Keepalive 探测数据包没有得到响应，会帮我们自动关闭这条连接。

### 8. SSE 协议了解吗？为什么用 websocket 而不用 SSE？

SSE是一种基于HTTP协议的服务器推送技术，允许服务器单向地向客户端推送事件流数据。它的主要特点是：

- 单向通信： 数据只能从服务器流向客户端。

- 基于HTTP： 无需新的协议，复用HTTP连接。

- 文本协议： 主要传输UTF-8编码的文本数据。

- 自动重连： 浏览器标准中定义了断线重连机制。

- 实现相对简单： 相比WebSocket，服务器端实现可能更简单一些。

我们项目选择WebSocket而不是SSE，主要是基于以下考虑：

1. 核心需求是双向通信： 我们的项目是一个即时通讯（IM）系统。用户不仅需要接收来自服务器的消息推送（如新消息、在线状态更新），还需要能够实时地向服务器发送消息、发送已读回执、发送正在输入状态等操作。WebSocket提供了全双工通信能力，允许客户端和服务器在建立连接后，随时互相发送数据。而SSE是单向的，客户端无法通过同一个SSE连接向服务器发送数据，如果需要客户端发送，就必须发起新的HTTP请求，这违背了IM系统低延迟、高实时性的要求。

2. 协议开销和效率： 虽然WebSocket的握手阶段比SSE复杂，但在连接建立后，其数据帧的协议开销相对较小，尤其适合频繁、小数据包的传输场景，这与IM的消息传输特性相符。如果使用SSE推送、HTTP发送，会增加额外的连接建立和请求开销。

3. 功能丰富性： WebSocket不仅支持文本数据，还原生支持二进制数据传输，这为未来可能扩展的功能（如传输图片、文件、音视频信令等）提供了更好的基础。

总结来说， 对于需要客户端和服务端进行高频实时双向交互的IM应用场景，WebSocket的全双工通信能力是其相比SSE最核心的优势，也是我们选择它的根本原因。SSE更适用于只需要服务器向客户端单向推送信息的场景，例如股票行情更新、新闻推送等。

### 9. Websocket如何保证消息可靠性？（重点强调不是安全性）

WebSocket协议本身是建立在TCP之上的，TCP协议提供了传输层的可靠性，包括数据包的顺序保证和丢包重传机制。但这并不等同于应用层消息的可靠性。比如，TCP连接可能意外断开，或者服务器/客户端在处理消息时崩溃，或者网络长时间抖动导致TCP认为连接超时。因此，为了确保WebSocket上的应用层消息传递真正可靠（即消息不丢失、不重复、基本有序），我们在应用层（也就是在 Real-TimeCommunicationService 和客户端之间）需要实现额外的机制：

1. 消息确认机制 (ACK): 这是最核心的机制。
  - 发送方： 每发送一条需要保证可靠性的消息时，会附带一个唯一的、单调递增的消息ID（或序列号）。同时启动一个定时器，等待接收方的确认应答（ACK）。
  - 接收方： 收到消息后，进行处理。处理成功后，向发送方回复一个ACK消息，其中包含收到的消息ID。
  - 超时与重传： 如果发送方在定时器超时前没有收到对应的ACK，就认为消息可能丢失，会进行重传（可能需要设定最大重传次数和退避策略）。

2. 消息序列号 (Sequence Number): 为了保证消息的基本有序性（尤其是在重传发生时），发送方可以为每个会话（或用户连接）维护一个递增的序列号。接收方可以根据序列号来判断消息是否重复或失序。对于失序的消息，可以先缓存，等待前面的消息到达后再处理，或者直接丢弃（取决于业务要求）。

3. 幂等性保证： 由于存在重传机制，接收方可能会收到重复的消息。接收方需要具备幂等处理能力。通常可以通过检查收到的消息ID是否已经被处理过来实现。例如，在Redis中记录最近处理过的一批消息ID，或者在数据库存储消息时利用唯一ID约束。

4. 心跳机制 (Heartbeat/Ping-Pong): 定期由客户端或服务器（或双方）发送心跳消息（Ping），对方收到后回复Pong。如果在一定时间内没有收到对方的心跳响应，就认为连接已经失效。这有助于及早发现“假死”连接，触发重连和后续的消息同步/重发逻辑。Netty本身提供了 IdleStateHandler 来方便地实现心跳检测。

5. 重连后的消息同步： 当连接断开并重新建立后，客户端需要与服务器同步状态，拉取在离线期间可能错过的消息。在我们的设计中，这部分主要是通过登录时（包括重连后的自动登录）与 OfflineDataStore 通信拉取离线消息来实现。对于重连前的“飞行中”消息（已发出但未确认ACK的），可以通过上述的ACK和序列号机制来决定是否需要重发。

通过这些应用层的机制组合，我们在 TCP 提供的基础传输可靠性之上，构建了更强的应用层消息可靠性保障，确保IM 消息能够尽可能地准确、完整、有序地送达。

## 面试问题整理：

### 1. 这些微服务模块是怎么部署的，是部署在多个服务器上吗，每个模块部署了几个实例？给你两台服务器，怎么部署这些服务？

部署我们可以通过为每一个服务编写 Dockerfile 文件，然后通过 Docker Compose 一键部署。所有微服务每个模块至少部署 2 个实例，实现负载均衡和故障转移。Redis 哨兵集群 ＋ MySQL 主从集群 ＋ Kafka 集群实现高可用。

### 2. 这个项目是落地的应用吗，还是学习项目，是你自己设计的吗，项目的架构是什么样的，为什么要用 Netty 和 WebSocket

这个是一个学习项目，我对 IM 项目比较感兴趣。架构上我采用的是 Spring Boot + Spring Cloud Alibaba 微服务架构，服务注册与发现使用 Nacos，配置中心也是 Nacos，服务间调用通过 OpenFeign ，网关使用 Spring Cloud Gateway。系统需要支持高并发、低延迟的双向实时通信。传统 HTTP 轮询效率低、延迟高、服务器压力大。因此，我选择 WebSocket 作为实时通信协议，而为了更好地控制连接生命周期、提升并发性能，我基于 Netty 实现了 WebSocket 服务模块。使用 Netty 的好处是因为它是基于 Reactor 线程模型，支持数万级并发长连接；能够更灵活地实现自定义协议、心跳检测、断线重连、消息广播/点对点等逻辑；

### 3. 用户如果在 A 实例发消息 1，但是挂了，这个时候后端把用户放到 B 实例重发消息，同一个消息会不会因为不同机器带来 ID 不同的问题

### 4. 消息能否可靠送达？服务端是否知道消息是否送达用户？如果消息因为网络原因没有推送给目标用户，这个时候 MySQL 又成功消费了消息，后端是否有补救措施？

### 5. 大量用户同时访问 MySQL 拉取离线消息如何减轻服务器压力

缓存 + 分页 + 索引优化。Redis 缓存离线消息，用户下线时，离线消息不仅存 MySQL，也写入 Redis，等到用户上线时，优先从 Redis 拉取，避免打 MySQL。同时 Redis 中离线消息设 TTL（如 7 天），过期自动删除，节省内存。要实现分页拉取 + 限制条数：如果用户的离线消息过多，存入 Redis 中的已经过期了，用户就应该去 MySQL 里面获取剩余的离线消息了，每次可以只拉最近 50 条，后续按需分页；避免一次性加载大量历史消息。还有读写分离 + 索引优化，离线消息查询可以走从 MySQL, 消息表通过 userId + sessionId + createdTime 创建联合索引。

### 6. Websocket 心跳时间是怎么设置的，为什么设置 5 分钟，这是基于什么考虑的？过短或者过长会带来什么问题？

项目中我们是设置读空闲为 5 分钟的，我们设置读空闲超时为 5 分钟，主要参考了主流 NAT 设备和防火墙的 TCP 空闲连接超时策略（通常在 3~10 分钟之间）。为避免连接被中间设备静默断开，心跳间隔需小于该超时值，5 分钟是一个在连接保活与资源开销之间取得平衡的常见工程实践。心跳时间设置过短的话会使心跳更加频繁，造成服务端 CPU/网络压力增大；移动端耗电增加，用户体验下降；而且无实际收益，用户并不需要秒级在线感知；在高并发场景下，频繁心跳可能触发 TCP 小包问题，反而降低吞吐；在 Netty 中，频繁触发 IdleStateEvent 会增加事件循环负担。心跳设置过长的话如果用户连接断开后无法及时感知，及用户"假在线"的情况，会使消息推送失败。而且心跳过长无法及时断开已经存在的 Channel，会占用无效连接资源。

### 7. 离线时间是怎么维护的？Redis 记录上下线是否有 ACK 机制，比如 WebSocket 告知用户下线，但是 Redis 出问题了，没有成功修改用户在线信息？

### 8. HTTP/1.0、HTTP/1.1、WebSocket 的区别

### 9. IM 项目中，每条消息都分配了一个 msgId 保存在消息表中，那五百人的用户量，一天每人发 80 条消息，很快就会有几十万条，你有考虑过怎么优化吗?

### 10. Redis 单线程，如果你通过 HSET 在修改值时，进行 RDB 快照了，会不会数据不一致吗？

不会出现半更新的数据不一致。 Redis 是单线程，HSET 命令本身是原子执行的；在做 RDB 快照时，Redis 会 fork 子进程，子进程基于 fork 那一刻的内存做快照，内部是自洽一致的。但从时间点上看，当前内存数据和已经生成好的 RDB 文件肯定会有差异，因为快照只反映某个历史时刻的状态，这是 RDB 的设计取舍，类似数据库的定时全量备份。可以考虑使用 AOF + RDB 混合持久化方案。

### 11. 为什么 WebSocket 用 TCP 而不是 UDP?

  - 可靠性要求：WebSocket 常用于需要确保消息完整送达的场景。TCP 提供了可靠、有序、无重复的字节流传输，天然保证了消息不丢失、不乱序，避免了在应用层实现复杂的重传、确认和排序机制。
  - 面向连接与全双工：WebSocket 是一种全双工通信协议，建立在持久连接之上。TCP 的面向连接特性正好支持这种长期、稳定的双向通道，而 UDP 是无连接的，难以直接支撑 WebSocket 所需的会话状态管理。
  - 兼容现有 Web 基础设施：WebSocket 在握手阶段复用了 HTTP/HTTPS 协议，而 HTTP 本身运行在 TCP 之上。这使得 WebSocket 能无缝穿越防火墙、代理和负载均衡器，充分利用现有的 Web 生态。

### 12. 保证发消息的顺序性

核心：消息的发送顺序最终要体现为接收者看到的顺序。因此，顺序性保障应以接收者视角为中心：只要接收方按正确时序展示消息即可。这需要服务端与客户端协同完成，服务端提供严格有序的标识，客户端负责最终排序呈现。

### 13. IM 项目中我们使用了 Redis 来存储每个用户在哪个 Netty 服务器上，但是我们在推送群聊消息的时候还是采用的广播的形式，而不直接推送具体的服务器呢

### 14. 缓存一致性（双写一致性）

方案一：旁路缓存。读的时候先查缓存，命中直接返回，未命中查 DB 后回写缓存；写的时候先更新 DB，再删除缓存。理论上存在不一致窗口，读线程在写线程更新 DB 和删缓存之间把旧值回写，但概率极低。

方案二：延迟双删。在旁路缓存基础上加强，用来解决主从延迟和并发读写下的旧值回种问题。流程是：删缓存 → 更新 DB → sleep 几百毫秒 → 再删一次缓存。第二次删除清掉主从同步窗口里被回种的旧值，sleep 时长按主从延迟上界估。生产里通常把第二次删除丢到 MQ 延迟队列做，既不阻塞业务线程，删失败还能重试。

方案三：订阅 Binlog。业务代码只写 DB，由 Canal 监听 MySQL binlog 异步把变更同步到 Redis。优点是业务零侵入、缓存逻辑和业务代码彻底解耦，串行消费 binlog 天然保证写顺序；缺点是链路长、有几十到几百毫秒延迟，且要额外运维 Canal。

### 15. 有哪些分布式事务方案，开源的模型有哪些？

| 方案 | 核心原理 | 特点 |
| --- | --- | --- |
| 2PC | 准备阶段+提交阶段，协调者统一调度 | 强一致，性能差，存在单点故障 |
| 3PC | 在 2PC 基础上增加预提交阶段 + 超时机制 | 减少阻塞，但实现复杂，仍有脑裂风险 |
| TCC | 业务层面实现三阶段：资源预留→确认→取消 | 高性能，但侵入性强，需手动编写补偿逻辑 |
| Saga | 长事务补偿模式，失败时按逆序执行补偿操作 | 适合长事务，最终一致性 |
| 可靠事件队列 | 基于消息队列（Kafka/RocketMQ）实现最终一致性 | 异步解耦，需处理幂等性 |
| XA | 数据库厂商对 2PC 的工业标准实现，基于数据库原生XA协议的两阶段提交 | 一致性最强，性能极差，依赖数据库支持 |

主流开源框架

- Apache Seata：支持 AT（自动补偿，默认模式）、TCC、Saga、XA 四种模式，覆盖绝大多数微服务场景

- Apache ShardingSphere：通过反向SQL技术自动生A成补偿操作，支持 XA 和 BASE（Saga）模式

- RocketMQ：内置事务消息。

### 16. 事务失效的情况

- 跨多个数据源：@Transactional 默认只能管理单个数据库连接的事务，操作多个库时无法保证原子性

- 跨服务调用（RPC/HTTP）：本地事务无法覆盖远程服务操作，远程调用成功但本地失败会导致数据不一致

- 类内部方法调用：Spring 事务基于 AOP 代理实现，this.method() 内部调用会绕过代理，导致 @Transactional 不生效

- 异常被捕获未抛出：try-catch 吞掉异常后，事务管理器认为一切正常，会提交事务而非回滚

- 异常类型不匹配：Spring 默认只对 RuntimeException 和 Error 回滚，对 Exception 等已检查异常不回滚（需配置 rollbackFor）

- 方法非 public：代理只增强 public 方法，标在 protected / private / 包级方法上不生效。

- 方法或类被 final / static 修饰：CGLIB 通过生成子类做代理，final 方法/类无法被覆盖，事务失效。

- Bean 未被 Spring 管理：自己 new 出来的对象没有代理，注解自然失效。
