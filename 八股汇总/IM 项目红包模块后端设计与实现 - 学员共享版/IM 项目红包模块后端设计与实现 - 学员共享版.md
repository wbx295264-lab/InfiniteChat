# IM 项目红包模块后端设计与实现 \- 学员共享版

# 红包模块整体流程图：

![红包新新流程图 \.png](图片和附件/红包新新流程图%20.png)

## 一、需求分析

在添加红包功能前，需要明确以下功能需求：

1. **发送红包**：用户可以在聊天界面发送红包，支持单聊和群聊。

2. **领取红包**：接收者可以点击红包进行领取，金额随机或固定。

3. **红包展示**：未领取的红包在聊天界面展示，已领取的红包显示领取状态。

4. **红包记录**：需要记录红包的发送和领取信息，供用户查询。

5. **金额处理**：由于不接入第三方支付，红包金额可视为虚拟货币或积分。

性能需求：

**场景分析**：

- 当一个红包（尤其是群聊里的拼手气红包）被发出后，短时间内可能会有大量的用户同时点击领取。

- 这种瞬时的高并发请求对系统的挑战主要有两点：

    - **数据一致性**：如何确保红包不被超额领取（超发）、单个用户不重复领取？

    - **系统性能**：如何保证在高并发下系统依然能快速响应，数据库不会被瞬时压力打垮？

## 二、数据库设计

1. 新增红包相关表

#### （1）红包主表 `red_packet`

```SQL
CREATE TABLE red_packet (
  red_packet_id BIGINT  NOT NULL COMMENT '红包ID唯一标识',
  sender_id BIGINT NOT NULL COMMENT '发送者用户ID',
  session_id BIGINT NOT NULL COMMENT '会话ID（单聊或群聊）',
  red_packet_wrapper_text VARCHAR(50) NOT NULL DEFAULT '恭喜发财，大吉大利' COMMENT '红包封面文案' ,
  red_packet_type TINYINT NOT NULL COMMENT '红包类型：1普通红包，2拼手气红包',
  total_amount DECIMAL(10,2) NOT NULL COMMENT '红包总金额',
  total_count INT NOT NULL COMMENT '红包总个数',
  remaining_amount DECIMAL(10,2) NOT NULL COMMENT '剩余金额',
  remaining_count INT NOT NULL COMMENT '剩余个数',
  status TINYINT NOT NULL DEFAULT '1' COMMENT '状态：1未领取完，2已领取完，3已过期',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (red_packet_id),
  INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='红包主表';
```

#### （2）红包领取记录表 `red_packet_receive`

```SQL
CREATE TABLE red_packet_receive (
  red_packet_receive_id BIGINT NOT NULL COMMENT '记录ID',
  red_packet_id BIGINT  NOT NULL COMMENT '红包ID',
  receiver_id BIGINT NOT NULL COMMENT '领取者用户ID',
  amount DECIMAL(10,2) NOT NULL COMMENT '领取金额',
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  PRIMARY KEY (red_packet_receive_id),
  INDEX idx_red_packet_id (red_packet_id),
  INDEX idx_receiver_id (receiver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='红包领取记录表';
```

2. 修改消息表 `message`

在 `message` 表中增加对红包消息的支持：

- 将 `type` 字段增加值 `5`，表示红包消息。

- `content`字段存储红包id。

## 三、功能实现

### 发送红包

#### （1）客户端请求

用户在客户端填写红包信息并发送，包括：

- 红包类型（普通红包、拼手气红包）

- 红包金额

- 红包数量

- 留言

#### （2）后端处理

在 `MessagingService` 中处理发送红包的请求：

- **防止重复提交**：Spring Boot 自定义注解 \+ AOP 防止重复提交

- **参数验证**：验证红包金额、数量等参数的合法性。

- **余额扣减**：从发送者的虚拟货币账户中扣除相应金额。

- **红包记录创建**：在 `red_packet` 表中插入一条新的红包记录。

- **消息记录创建**：在 `message` 表中插入一条红包消息，`red_packet_id`字段存储 `red_packet_id`。

- **消息推送**：通过 `Real-TimeCommunicationService` 将红包消息推送给接收者。

### 领取红包

#### （1）客户端请求

用户点击红包，客户端发送领取请求，携带 `red_packet_id`。

#### （2）后端处理

在 `MessagingService` 中处理领取红包的请求：

- **并发控制**：

    - **V1：**

        - **使用分布式锁**（如 Redis 锁）以 `red_packet_id` 为键，防止并发超领。

    - **V1\.5（目前实现方案）：**

        - **创建红包时在Redis中初始化红包信息**，红包剩余个数。

        - **使用Redis Lua脚本**，原子性地执行以下操作：

            - 判断红包是否还有剩余数量。

            - 如果有，减少一个红包数量。返回true

            - 如果无，则返回false

    - **V2：**

        1. **红包创建时**

        - **在Redis中初始化红包信息**，红包剩余个数，红包金额列表。

        ```Java
        // 生成红包金额列表（对于拼手气红包）
        List<BigDecimal> amountList = RedPacketUtil.divideRedPackage(totalAmount, totalCount);
        // 将红包金额列表存入Redis的List
        redisTemplate.opsForList().leftPushAll("red_packet:" + redPacketId + ":amounts", amountList);
        // 设置剩余个数
        redisTemplate.opsForValue().set("red_packet:" + redPacketId + ":count", totalCount);
        ```

        2. **用户领取红包**

        - **使用Redis Lua脚本**，原子性地执行以下操作：

            - 判断红包是否还有剩余数量。

            - 如果有，弹出一个红包金额。

            - 将领取信息存入Redis的List，供后续异步处理。

        3. **异步更新数据库**

        - **监听Redis list**：创建一个独立的组件（如Spring Bean），在后台线程中持续监听Redis List的变化。一旦有新的领取记录被添加，就将其取出并发送到Kafka。

        - **使用消息队列**：kafka消费者将消息批量插入数据库。将领取红包的操作异步化，进一步提高系统的吞吐量。

- **红包状态检查**：验证红包是否未过期、未领完，用户是否已领取过。

- **金额计算**：

    - **普通红包**：固定金额＝总金额/总数量。

    - **拼手气红包**：随机金额，需确保剩余金额和数量正确。

- **更新红包记录**：更新 `remaining_amount` 和 `remaining_count`。

- **领取记录创建**：在 `red_packet_receive` 表中插入领取记录。

- **余额增加**：将领取的金额添加到用户的虚拟货币账户。

- **红包状态更新**：如果 `remaining_count` 为 0，更新红包状态为已领取完毕。

### 红包过期处理

- **Redis过期订阅**：创建红包的同时在 Redis 缓存一个 24h 过期的唯一标识，在更新红包状态为领取完毕时删除该标识，若 24h 缓存过期则订阅监听器收到消息，对该红包进行过期处理。

- **过期处理**：对过期且未领取完的红包，将剩余金额退还给发送者，更新红包状态为已过期。

- **余额返还**：更新发送者的虚拟货币账户，记录返还日志。

> 该方案在真实生产环境不可取，具体可见[红包过期处理工业级成熟解决方案](https://q0kyes8lnt5.feishu.cn/wiki/NhH0wnOxziVIAXkr9D3cwOmNnyf)
> 
> 

### 红包展示和查询

- **聊天界面**：展示红包消息，未领取显示“领取红包”，已领取或过期显示相应状态。

- **详情查看**：用户可查看红包的领取详情，包括领取者和金额。

## 四、虚拟货币系统

### 用户余额表 `user_balance`

```SQL
CREATE TABLE user_balance (
  user_id BIGINT NOT NULL COMMENT '用户ID',
  balance DECIMAL(10,2) NOT NULL DEFAULT '0.00' COMMENT '余额',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户余额表';
```

### 余额变动记录表 `balance_log`

```SQL
CREATE TABLE balance_log (
  balance_log_id BIGINT NOT NULL COMMENT '记录ID',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  amount DECIMAL(10,2) NOT NULL COMMENT '变动金额，正数为增加，负数为减少',
  type TINYINT NOT NULL COMMENT '变动类型：1发送红包，2领取红包，3红包退回',
  related_id BIGINT COMMENT '关联ID，如红包ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (balance_log_id),
  INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='余额变动记录表';
```

### 余额操作

- **发送红包**：扣减发送者余额，记录余额变动日志。

- **领取红包**：增加接收者余额，记录余额变动日志。

- **红包退回**：过期红包的剩余金额退回发送者余额。

## 五、与现有模块的集成

### APIGateway

- **路由配置**：新增红包相关的 API 路由，确保请求正确转发到 `MessagingService`。

- **权限验证**：确保红包相关操作需要用户登录。

### MessagingService

- **新增接口**：

    - **发送红包接口**：处理红包发送逻辑。

    - **领取红包接口**：处理红包领取逻辑。

    - **查询红包详情接口**：提供红包的详细信息。

    - **余额展示**：供客户端查询和展示。

- **消息处理**：在消息投递过程中，增加对红包消息类型的处理。

### Real\-TimeCommunicationService

- **消息推送**：支持红包消息和领取通知的实时推送。

### OfflineDataStore

- **消息存储**：保存红包消息，确保离线用户在上线后能收到红包信息。

## 六、测试与优化

### 测试

- **功能测试**：验证发送、领取、过期等功能是否正常。

- **并发测试**：模拟高并发场景，确保并发控制有效。

- **异常测试**：测试余额不足、红包已领取完毕等异常情况的处理。

### 性能优化

- **Redis**：

    - 将红包信息缓存到 Redis，提高读取效率。使用Redis的List、Set等数据结构存储红包信息。

    - 将红包领取操作的核心逻辑（如金额分配）放入Redis中进行原子操作。

- **过期机制优化**：利用 Redis 的过期特性，自动处理过期红包。**√**

- **MQ消息队列**：资金变动可以异步处理，使用Kafka来异步通知和更新账户余额。

- **MQ队列削峰**：使用消息队列对红包领取请求进行削峰处理。

## 七、安全性考虑

- **数据验证**：严格验证客户端传入的数据，防止非法操作。

- **防刷机制**：限制单位时间内的红包发送和领取次数，防止刷红包行为。

- **日志记录**：详细记录红包操作日志，便于问题追踪和审计。

- **限流**：可以使用sentinel，Redis或Guava实现限流，如漏桶或令牌桶算法。

## 八、总结

微信红包功能的设计需要处理高并发、资金安全、事务一致性等多个复杂问题。每个模块的设计都需要考虑性能和安全性，确保红包发放和领取过程中的流畅性与可靠性。

通过以上设计，我们在现有的 IM 系统中成功实现了微信红包功能，满足了发送、领取、展示和记录等需求。整个过程遵循了高内聚低耦合的原则，充分利用了现有模块，确保系统的可扩展性和稳定性。



