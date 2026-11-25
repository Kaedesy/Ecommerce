# Mall Swarm 参考技术亮点改造方案

## 1. 方案目标

参考目标项目的四类技术亮点，对 Mall Swarm 现有实现进行有边界的增强：

1. 秒杀并发与失败处理：Redis Lua 原子预占、异步创建订单、消费者幂等及失败补偿。
2. 商品变更与索引同步：通过 Transactional Outbox 将商品数据库变更可靠地同步到 Elasticsearch。
3. 多实例缓存协同：按实际热点为商品数据补充本地缓存、分布式缓存和跨实例失效机制。
4. 对账与异常恢复：定时核对订单、库存、优惠券和消息状态，留存差异并安全修复。

改造遵循“数据库是交易事实来源、消息至少一次投递、业务操作可幂等重放”的原则。不以引入组件数量为目标；每项实现都要有故障场景和自动化验证支撑。

## 2. 当前基础与边界

根据此前对 Mall Swarm 代码的梳理，目前已有以下可复用基础：

| 领域 | 当前基础 | 本方案中的处理 |
| --- | --- | --- |
| 普通订单库存 | MySQL 条件更新预占库存，多个 SKU 按 ID 排序，并在事务中处理 | 保持为普通订单的库存主链路，不替换成 Redis 锁 |
| 普通下单幂等 | `Idempotency-Key`、会员维度唯一约束及 SHA-256 请求摘要 | 保持并用于识别重复下单请求 |
| 支付与取消 | 带预期状态的条件状态迁移，成功方处理库存确认或释放 | 继续作为订单状态竞争的互斥入口 |
| 订单 Outbox | 订单事务写入延迟取消事件，后台发布器重试并发送 RabbitMQ | 复用 Outbox 认领、确认和退避重试的实现经验 |
| 秒杀库存 | 秒杀链路已有 Redis Lua 预占及补偿设计 | 与普通订单库存链路分开；检查当前代码落地情况后补齐消息与幂等闭环 |
| 商品搜索 | 已有 Elasticsearch 搜索、筛选和聚合能力 | 本次重点解决商品写入到 ES 的可靠同步，不重做搜索功能 |
| 缓存与调度 | 项目使用 Redis；本地缓存、跨实例失效及对账任务需结合当前依赖和部署再核实 | 先审计现状，不把尚未落地的 Caffeine、Redisson、XXL-JOB 写成已有能力 |

秒杀和普通订单的库存语义不同：普通订单以 MySQL 事务和条件更新为准；秒杀可以先在 Redis 中原子预占，再通过消息异步落单，并用请求状态、唯一约束和补偿处理 Redis 与 MySQL 之间的故障窗口。

## 3. 总体实施顺序

建议按以下顺序推进，前一阶段的接口、状态和测试稳定后再进入下一阶段：

1. **阶段 0：代码盘点与基线测试。** 确认秒杀链路、商品写接口、ES 同步入口、缓存读写和调度设施的真实位置，补充改造前测试基线。
2. **阶段 1：商品变更 Outbox 同步 ES。** 复用已有订单 Outbox 思路，先打通数据库事务到搜索索引的可靠事件链路。
3. **阶段 2：秒杀异步下单与失败闭环。** 在 Lua 预占的基础上接入可靠消息、消费者幂等、死信处理和补偿。
4. **阶段 3：多实例缓存协同。** 根据访问热点和缓存一致性要求，再决定本地缓存、分布式锁和布隆过滤器的实际组合。
5. **阶段 4：对账与异常恢复。** 设计只处理明确差异的核对任务、审计记录和幂等修复入口。

若更重视和目标项目简历亮点的对齐，阶段 1、2、4 优先；阶段 3 需先有缓存热点或穿透问题的证据。

## 4. 阶段 0：代码盘点与基线

### 4.1 盘点内容

- 找出秒杀活动校验、Redis Key、Lua 脚本、请求记录、订单创建及补偿入口。
- 找出商品新增、修改、上下架、删除的服务方法和事务边界。
- 确认 Elasticsearch 索引创建、更新、删除目前由谁触发，失败后是否有重放方式。
- 统计 Redis 缓存的 Key、TTL、写入和删除位置，识别是否存在多实例部署场景。
- 检查 XXL-JOB 或其他调度设施是否已在项目中运行，确认生产环境可用性。
- 记录现有测试、消息拓扑和本地启动依赖；没有实测的性能数据保持空缺。

### 4.2 基线产物

- 秒杀请求与订单状态流转图。
- 商品写入到 ES 的当前调用链和故障窗口。
- 缓存 Key 与失效关系表。
- 对账数据来源及可执行修复动作清单。
- 改造前自动化测试结果和本地环境说明。

## 5. 阶段 1：商品变更与 Elasticsearch 索引可靠同步

### 5.1 目标

商品数据写入 MySQL 成功后，即使应用在提交后崩溃、RabbitMQ 暂时不可用或 ES 更新失败，索引事件仍可重试，最终使 ES 与 MySQL 收敛。MySQL 仍是商品数据权威来源，ES 是可重建的搜索模型。

### 5.2 建议流程

1. 商品服务在同一个 MySQL 事务中写商品数据和 Outbox 事件。事件包含事件 ID、商品 ID、事件类型、必要版本或更新时间、创建时间和投递状态。
2. 事务提交后由 Outbox 发布器认领待发事件，发送 RabbitMQ 并等待 Publisher Confirm；发送成功后标记已发送。
3. 商品索引消费者按事件类型执行 ES 新增/覆盖更新或删除。消费逻辑按事件 ID 幂等，并避免旧版本事件覆盖较新商品数据。
4. 消费成功后 ACK；短暂失败重试，达到上限进入死信或失败事件表，保留人工重放入口和告警。
5. 对无法确定商品完整内容的事件，消费者按商品 ID 回查 MySQL 当前数据构建索引，避免依赖过期事件快照。

### 5.3 关键约束

- Outbox 事件必须与商品变更处于同一个数据库事务，不能在事务提交后直接“尽力发送”。
- Publisher Confirm 只说明 Broker 接收确认，不等于消费者已完成 ES 更新。
- RabbitMQ 为至少一次投递；消费者和 ES 写入需要可重复执行。
- 商品删除事件要能删除 ES 文档；重复删除应当安全。
- 事件需有可观测状态、重试次数、最后错误和时间戳；敏感信息不写入事件。

### 5.4 验收标准

- 商品新增、修改、上下架和删除最终反映到 ES。
- 在数据库提交后、发布确认前、消费更新 ES 前分别模拟进程中断，恢复后事件能补发或重放。
- 重复投递不产生重复业务副作用；旧事件不会覆盖新数据。
- 能从事件 ID 和商品 ID 查询投递状态、失败原因及重试记录。

## 6. 阶段 2：秒杀异步下单与失败处理

### 6.1 目标

保留 Redis Lua 对活动库存和用户限购的原子校验，在预占后异步创建订单；通过数据库唯一约束、请求状态和消费者幂等，处理消息重投与并发请求，并在无法落单时归还预占。

### 6.2 建议流程

1. 接口校验登录身份、活动时间、商品状态、购买数量和用户限购条件。
2. Lua 在一个原子操作内检查活动库存及用户限购，并写入预占/请求标识；重复请求返回已有请求状态或稳定结果。
3. 记录唯一秒杀请求。请求表至少包含请求 ID、活动 ID、SKU ID、会员 ID、数量、状态、关联订单 ID、失败原因及时间戳；唯一键应能阻止同一业务请求重复创建。
4. 可靠发布秒杀下单消息。若消息发布失败，保留可重试状态；若请求记录失败或明确无法继续，调用补偿逻辑释放 Redis 预占。
5. 消费者通过请求 ID 幂等处理，校验请求状态和活动规则，创建订单并完成必要的数据库库存处理；成功后记录订单 ID 和完成状态。
6. 消费者遇到可恢复故障进行有限重试；达到上限进入死信/失败记录，并由补偿任务或人工重放处理。补偿必须先确认请求未成功落单，再释放 Redis 预占。

### 6.3 状态与一致性

建议秒杀请求状态至少区分：`RESERVED`（已预占）、`PROCESSING`（消费处理中）、`ORDERED`（订单已创建）、`FAILED`（明确失败）、`COMPENSATED`（预占已释放）。状态更新使用条件更新，禁止从终态回到处理中。订单创建与请求成功状态应在同一数据库事务中提交，并设置订单关联或请求 ID 唯一约束。

Redis 与 MySQL 无法用普通本地事务原子提交，因此该链路是最终一致：Redis 负责快速预占，MySQL 负责最终订单和交易记录，失败由显式状态及补偿闭环恢复。不可宣称端到端 Exactly Once。

### 6.4 验收标准

- 并发请求不突破活动库存和用户限购。
- 同一请求重复提交或消息重复投递只产生一张秒杀订单。
- 覆盖 Redis 预占后数据库写失败、消息发送失败、消费者重复投递、订单创建失败、死信和补偿重试场景。
- 补偿重复执行安全，不会把已成功订单对应的库存再次释放。
- 通过自动化并发测试验证库存、用户限购、请求状态和订单数量一致；性能数据需单独压测后记录。

## 7. 阶段 3：多实例缓存协同

### 7.1 目标与选型原则

在确认商品读取热点后，评估 Caffeine 本地缓存加 Redis 分布式缓存。缓存用于减轻读取压力，不作为商品、库存或订单的权威数据。先做命中率、延迟、热点和失效行为测量，再决定是否引入额外组件。

### 7.2 建议读写策略

- 读取顺序：Caffeine L1 → Redis L2 → MySQL；回源后按配置写入 L2 和 L1。
- 更新策略：商品事务提交后发布商品变更事件；各实例消费事件清理本地 L1，同时删除或刷新 Redis L2。事件可能重复，失效操作必须幂等。
- TTL：L1、L2 设置合理过期时间作为失效兜底，可为热点数据设置轻微随机过期，降低集中失效。
- 热点重建：缓存未命中时先双重检查，再使用 Redisson 等跨实例互斥机制限制并发回源；锁只用于保护缓存重建，不作为库存一致性手段。
- 缓存穿透：对确认不存在的商品使用短 TTL 空值缓存；只有在数据规模和访问模式确有需要时，再引入可维护的布隆过滤器。布隆过滤器有误判可能，不能作为最终存在性判断。
- 故障降级：Redis 或消息系统不可用时，定义可控回源、限流和告警策略，避免所有实例同时击穿数据库。

### 7.3 验收标准

- 多实例环境下商品更新后各实例本地缓存能够失效，且 TTL 可兜底。
- 并发冷启动热点 Key 时，数据库回源次数受控。
- 不存在商品的重复查询不会持续穿透到数据库。
- Redis、消息通知或互斥锁异常时具备超时、降级和监控，缓存失效不会影响交易正确性。
- 通过指标比较改造前后的缓存命中率、数据库回源次数和响应延迟；无测试数据不写性能提升结论。

## 8. 阶段 4：对账与异常恢复

### 8.1 核对范围

- 秒杀请求状态与关联订单：已预占但长期无终态、标记成功但找不到订单、失败请求仍占用预留等。
- 普通订单状态与库存锁：待支付订单锁定量、已支付订单确认量、已取消订单释放量是否满足业务规则。
- 订单与优惠券/积分使用记录：订单状态、优惠券核销状态及积分流水是否一致。
- Outbox 与 RabbitMQ/消费者处理记录：长期 PROCESSING、超出重试次数、死信事件及未确认事件。
- 商品数据库与 ES：以 MySQL 商品状态为依据抽样或分批比对，发现差异后可重建单商品索引或进入批次重建。

### 8.2 执行与修复原则

1. 优先使用项目已有调度设施；只有部署和运维环境支持时才引入 XXL-JOB。若现有 Spring 定时任务足以满足单实例或受控任务需求，不为简历额外堆叠调度组件。
2. 核对任务按批次分页、限速执行，支持任务互斥和断点续跑，记录批次号、对象 ID、差异类型、发现时间和处理结果。
3. 自动修复仅覆盖规则明确且可幂等重试的情况；不确定的差异只告警并标记人工处理，不自动改写订单、库存或支付事实。
4. 修复采用条件更新和独立审计记录，执行前检查对象当前状态，重复执行不重复扣库存、退库存或核销优惠券。
5. 对修复失败设置次数上限和告警；保留人工确认、重放和审计查询入口。

### 8.3 验收标准

- 可通过构造数据稳定识别每类差异，且不会误报正常过渡状态。
- 修复任务支持重复运行、并发控制、暂停和断点续跑。
- 已自动修复和待人工处理的差异均可追溯。
- 失败记录会告警，不因修复任务异常影响在线下单和支付链路。

## 9. 横向质量要求

- **幂等：** 请求入口、Outbox 发布、RabbitMQ 消费、库存补偿和对账修复分别定义业务幂等键。
- **状态机：** 使用明确状态和条件迁移；禁止只依赖内存判断或先查后改。
- **可观测：** 记录 requestId、eventId、orderId、skuId 等关联字段，增加队列积压、重试次数、死信数量、补偿失败和对账差异指标。
- **安全：** 会员身份以服务端登录态为准，消息内容不信任前端提供的会员 ID 和价格。
- **数据兼容：** 新增表和索引提供可重复执行的迁移脚本；先部署兼容代码，再启用生产者/消费者，最后清理旧路径。
- **回滚：** 通过配置开关控制新消费者、缓存层和任务启用；保留安全的单商品索引重建及事件重放方式。

## 10. 测试与发布计划

### 10.1 测试分层

1. 单元测试：Lua 边界、状态迁移、事件映射、补偿条件、缓存过期和布隆过滤器判断。
2. 集成测试：MySQL 条件更新、事务回滚、Outbox 发布确认、RabbitMQ 重投/死信、ES upsert/delete 和消费者幂等。
3. 并发测试：秒杀库存和限购、重复下单、同一事件重复消费、缓存热点并发回源、任务并发认领。
4. 故障注入：Redis、RabbitMQ、ES 暂停；应用在关键提交点重启；构造超时、拒绝、重复和乱序消息。
5. 压测：记录并发数、数据规模、运行时长、机器资源、错误率、吞吐量和 P95/P99 延迟。只报告可复现的实测结果。

### 10.2 灰度顺序

- 先发布数据库迁移和兼容代码，确认旧交易链路正常。
- 小流量启用 Outbox 商品同步，观测积压、失败和 ES 延迟。
- 启用秒杀异步消费并从小规模活动开始，观察预占、落单、补偿和限购指标。
- 再逐实例启用缓存层和失效事件，确认缓存更新后逐步扩大流量。
- 对账任务先只读运行并核对误报，再开放白名单内的自动修复。

## 11. 简历表述边界

完成并验证后，可按实际上线的能力描述为：

- **秒杀并发与失败处理：** 基于 Redis Lua 原子完成库存预占和用户限购，结合 RabbitMQ 异步下单、数据库唯一约束与消费者幂等处理重复请求，并通过死信和补偿恢复失败预占。
- **商品变更与索引同步：** 商品写入事务内落 Outbox，由发布器等待 RabbitMQ Publisher Confirm 并按退避策略重试；搜索消费者按商品 ID 回查 MySQL 当前商品数据，幂等刷新或删除 Elasticsearch 文档。当前有 DEAD 事件记录，人工重放入口尚未实现。
- **商品详情缓存：** 使用 Caffeine 本地缓存和 Redis 共享缓存，配合短 TTL 空值缓存、Redis 重建锁及 Pub/Sub 失效通知；库存和可用优惠券仍按请求实时读取。本轮没有压测数据，也未实现布隆过滤器。
- **对账与异常恢复：** 使用 Spring 定时任务处理超时待支付普通订单，并核对库存锁、优惠券订单关联和 DEAD Outbox 事件；明确的超时订单复用已有条件取消事务，其他差异记录后转人工处理。真实 MySQL 对账查询、差异记录幂等更新和事务回滚已验证；Spring 调度器在完整应用上下文中的自动触发尚未验证。

上述描述必须按当前代码和验证范围使用。本轮验证了专用服务上的关键组件集成，但没有运行整个 Mall 微服务栈，也没有验证真实秒杀请求从 HTTP、数据库、RabbitMQ 消费到创建订单的完整端到端链路；不要写成已上线或性能提升成果。

## 12. 实施记录

| 阶段 | 代码变更 | 测试结果 | 状态 |
| --- | --- | --- | --- |
| 阶段 0 代码盘点 | 确认秒杀 Lua 预占、异步建单、订单 Outbox 已存在；确认商品写入没有可靠 ES Outbox，多实例商品详情缓存和交易对账需补齐。 | 记录模块边界与迁移要求；静态核对表结构、配置和服务拓扑。 | 已完成 |
| 阶段 1 商品 Outbox 与 ES | 商品变更事务写入 Outbox；admin 发布器使用 RabbitMQ Confirm、mandatory 路由检查、租约认领、退避重试和 DEAD 终态；search 消费后按商品 ID 回查 MySQL 当前数据刷新/删除 ES；同步发布 Redis 缓存失效通知。 | 发布器、消费者单测通过；在 validation MySQL 测试库执行两份迁移成功，并真实验证 Outbox 事件经 RabbitMQ Confirm 到达绑定队列；真实 ES 集成测试验证商品文档 upsert/delete。 | 核心生产、搜索消费集成已验证；未做完整 Mall 服务端到端 |
| 阶段 2 秒杀异步下单 | 保留已有 Lua 原子预占、异步建单、幂等和失败补偿实现，本轮未重复实现。 | 实际 Redis 执行 Lua：40 个并发会员竞争 10 个库存，仅 10 个成功；重放 request token 不重复扣库存，重复补偿只返还一次；未激活、Redis 库存键缺失、售罄、超限购四种拒绝均不改库存。真实 MyBatis 请求 Mapper + MySQL + RabbitMQ 验证 READY→PUBLISHING→PUBLISHED、Confirm、队列投递及不可路由退避重试；同一串联测试再从 Rabbit 临时队列取出 requestNo，调用真实消费者和订单/订单项 Mapper，重复投递仅建一单，库存预留与限购各更新一次，数据库事务回滚。Redis 库存键丢失时生命周期任务将活动设为 RECOVERY。新增 Portal 完整上下文 Listener 测试源码并编译通过，但 Docker Engine 本轮因 WSL `ERROR_NO_SYSTEM_RESOURCES` 未能启动，尚未执行。 | Redis、请求投递、Rabbit 消息到建单、幂等和故障关闭组件已集成验证；完整 Spring Listener 测试待 Docker 资源恢复后执行 |
| 阶段 3 多实例缓存 | 商品详情使用 Caffeine 本地缓存、Redis 共享缓存、短 TTL 空值缓存、Redis 重建锁和 Pub/Sub 本地缓存失效；库存及可用优惠券仍按请求动态读取。 | 2 项单测及 1 项真实 Redis 集成测试通过：第二实例命中 Redis L2，失效消息清理两个实例的本地缓存。 | 代码、单测和 Redis 集成完成 |
| 阶段 4 对账与恢复 | 新增定时任务复用订单条件取消事务处理超时订单，并记录库存锁、优惠券和 DEAD Outbox 差异。 | 单测通过；真实 MySQL 5.7 集成测试两次调用对账任务，确认 3 条 SKU 库存差异、7 条优惠券差异均记录且 `occurrence_count` 幂等递增，随后回滚测试事务。超时订单自动取消及 Spring 调度生命周期未在完整应用上下文验证。 | SQL、幂等记录与单测通过；完整调度上下文待验证 |

### 执行与部署注意事项

- 干净构建命令：`mvn -pl mall-admin,mall-search,mall-portal -am -Dtest=ProductOutboxPublisherTest,ProductDetailCacheTest,MallReconciliationWorkerTest,ProductIndexEventConsumerTest -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false clean test`，6 个 Maven 模块构建成功，7 项单测通过。
- Outbox 集成命令：`mvn -pl mall-admin -am -Dtest=ProductOutboxPublisherTest,ProductOutboxPublisherIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 clean test`；真实 RabbitMQ Confirm 和队列路由通过。
- Redis 集成命令：`mvn -pl mall-portal -am -Dtest=ProductDetailCacheTest,ProductDetailCacheIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；两个缓存实例共享/失效测试通过。两个集成测试默认受 `mall.test.integration=true` 控制。
- Elasticsearch 集成命令：`mvn -pl mall-search -am -Dtest=ProductIndexRefreshIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；在单独 ES 容器中真实验证索引 upsert/delete，容器内存上限 1 GiB、ES 堆 256 MiB。
- MySQL 对账集成命令：`mvn -pl mall-portal -am -Dtest=MallReconciliationWorkerTest,MallReconciliationWorkerIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；事务回滚保证验证不污染专用样例库。
- 秒杀 Lua 集成命令：`mvn -pl mall-portal -am -Dtest=FlashPromotionLuaIntegrationTest,ProductDetailCacheTest,ProductDetailCacheIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；并发预占、幂等重放、补偿幂等和缓存跨实例失效通过。
- 秒杀发布器/消息到建单集成命令：`mvn -pl mall-portal -am -Dtest=FlashOrderPublisherIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；真实 MyBatis 请求状态、RabbitMQ Confirm/临时队列投递、消费者真实建单和重复消息幂等，以及不可路由时进入重试均通过；使用临时隔离 exchange/queue，避免向既有业务验证队列写新测试消息。
- Portal 完整上下文 Listener 测试：`mall-portal/src/test/java/com/macro/mall/portal/component/FlashOrderListenerContextIntegrationTest.java`；`mvn -pl mall-portal -am -DskipTests clean test-compile` 编译通过，测试运行需 MySQL、Redis 与 RabbitMQ。尝试启动 Docker 时 WSL 返回 `ERROR_NO_SYSTEM_RESOURCES`，该用例本轮未执行。
- 秒杀建单消费者集成命令：`mvn -pl mall-portal -am -Dtest=FlashOrderCreateServiceIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；真实订单及订单项 Mapper，重复投递只建一次，事务回滚。
- 秒杀生命周期保护集成命令：`mvn -pl mall-portal -am -Dtest=FlashPromotionLifecycleIntegrationTest -Dmall.test.integration=true -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；数据库仍标记活动 ACTIVE、但 Redis 库存键缺失时，活动进入 RECOVERY。
- 本轮先前遇到的测试报错来自 `target` 中残留的 IDE 错误字节码；清理后重新由 javac 编译源码，问题消失。
- 两份迁移脚本已在专用 `validation-mall-mysql` 容器的 mall 测试库执行成功；RabbitMQ、Redis、ES 都分阶段单独启动并完成验证。未启动 Nacos 和完整 Mall 服务。
- 验证结束后已停止本轮启动的 MySQL、Redis、RabbitMQ 和 ES 容器，并关闭 Docker Desktop；复查确认 3306/13306、6379/16379、5672/15672、9200 和 8848 均未监听。Docker 启动时会按既有重启策略自动恢复其他项目容器，测试前已逐一停止这些非本次验证容器。
- 对账任务默认启用；如需灰度，可通过 `mall.reconciliation.enabled=false` 关闭。商品 Outbox 发布器通过 `mall.product-outbox.enabled=false` 关闭。部署前应先应用迁移脚本，再启动生产者和消费者。
- RabbitMQ 配置默认连接 `/mall` vhost、`mall` 用户；使用项目基础 compose 的原生镜像时需先创建对应 vhost/用户，或通过 `MALL_RABBIT_*` 环境变量覆盖。
- 静态检查发现 `document/sql/mall.sql` 样例数据包含负数 `pms_sku_stock.lock_stock`，以及 `use_status=1` 但没有关联订单的优惠券历史；在该样例库运行对账会产生待人工核查记录。集成验证时应区分已有样例异常与本次改造造成的问题。
- 最终验证执行 `mvn -pl mall-admin,mall-portal,mall-search -am -DskipTests test-compile`，6 个 reactor 模块及所有相关测试源码编译通过。尝试执行 `package` 时，项目绑定的 Fabric8 插件自动尝试构建 Docker 镜像并因 Docker Engine 不可用而失败；改用 `test-compile` 完成代码编译检查，没有触发镜像构建。
- 对账 worker 中两条用户可见中文诊断文本存在乱码，已修复为可读中文并重新通过测试编译。
- RabbitMQ 验证 vhost 已存在固定秒杀队列。最初一次发布器集成测试误用生产 exchange，发现标准队列中有 3 条本轮测试 requestNo 消息；后续测试已切到隔离 exchange。自动审批拒绝了不能按 payload 精确筛选的批量 ACK 清理命令，因此这 3 条已识别消息仍保留在验证队列，待用户确认后逐条清理。
