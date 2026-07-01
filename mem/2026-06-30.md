# 2026-06-30

### [17:54] 代可行 — M6 归档：Phase 13-16 全部完工（BUILD.00000040，82 .java 文件）

**修改内容**：

#### 架构层
- **ZCSLIBCommon.java（新建）**: 将内核生命周期从 ZCSLIB.java @Mod 入口抽离为版本无关层。ZCSLIB.java 变为薄壳（仅对 NeoForge 21.1 编译，可运行于 21.1-26.1）。常量 `MOD_ID`/`VERSION` 集中到 Common。
- **TrustLevel.java**: 新增 `BLACKLISTED` 和 `UNKNOWN` 枚举值。BLACKLISTED → 所有 order() 调用立即拒绝；UNKNOWN → 无识别特征的裸 jar，加载时拒绝。
- **身份合并完成**: 所有 dispatch 方法签名从 `(command, args)` 统一为 `(pluginId, trust, command, args)`。pluginId 由 SimplePluginContext 注入，调用方不再自己传入。`order()` 改为 private，`orderTraced()` 为唯一公共入口。

#### Phase 13 mcapi/（MC 集成，6 个文件新建）
- **McPort.java**: 三重安全门禁（package-private 构造 + StackWalker 调用方验证 + PluginClassLoader 拒绝 mcapi 导入）。工厂类，产出 WorldAPI/PlayerAPI/TickAPI/BlockAPI 四个子 API。
- **WorldAPI.java**: 只读世界查询（chunks/gametime/daytime/weather/dimension/seed），新增 `getServerLevel()` 供 sandbox 使用。
- **PlayerAPI.java**: 在线玩家查询
- **TickAPI.java**: TPS/MSPT 环形缓冲区
- **BlockAPI.java**: BlockState 查询
- **CommandAdapter.java**: `/zcslib` 命令树（plugins/plugin reload/debug tps|perf|audit）

#### Phase 14 sandbox/（世界沙箱，3 个文件新建）
- **VirtualSave.java**: BlockState 快照（长方体/球形），纯 in-memory
- **DryRunContext.java**: trial() 干跑执行，基于 VirtualSave
- **AutoRollback.java**: 线性修改日志，3 tick 窗口自动回滚

#### Phase 15 monitor/（运行时监控，5 个文件新建）
- **AutoSave.java**: 定时存档（5 分钟/6000 tick）+ TPS 紧急存盘（连续 5 tick < 10 TPS）
- **CrashGuard.java**: 单插件 try-catch 隔离，5 次/60s 崩溃自动降级为 S
- **LagGuard.java**: 超时门控（默认 50ms），watchdog 线程中断
- **LeakDetector.java**: GC 泄漏扫描 + 事件总线监听器计数
- **PerfMonitor.java**: TPS 历史 + per-plugin 延迟统计

#### Phase 16 security/（安全子系统，4 个文件新建）
- **BanHammer.java**: 签名黑名单 + 5 规则行为评分（崩溃/超时/泄漏/网络异常/审计违规），自动 ban S→BLACKLISTED
- **CommandWhitelist.java**: 插件命令白名单注册，信任门控（仅 N/R/A 可注册）
- **NetworkAudit.java**: 出站 HTTP 全量审计（size, latency, trust）
- **PermissionNode.java**: MC 权限节点注册

#### 内核接线（ZCSKernel.java，+641/-? 行）
- `initMcPort()`: ServerStartedEvent 后延迟初始化 McPort+CommandAdapter，注入 TickAPI/WorldAPI 到 monitor 子系统
- `dispatch0()`: 新增 mcapi:/sandbox:/monitor:/security: 路由
- `orderTraced()`: P15 LagGuard→CrashGuard→order() 三明治保护 + P16 BLACKLISTED 前端拒绝 + PerfMonitor 全量记录
- `onTick()`: 7 个新钩子（tickAPI, autoRollback.age, perfMonitor.sample, leakDetector.onTick, autoSave.tick, crashGuard.setCurrentTick, banHammer.autoReview/20tick）
- `shutdown()`: autoSave.forceSaveAll + banHammer.saveBans + network.shutdown + scheduler.shutdown

#### 网络层加固（ZCSNetwork.java，+117 行）
- S 级 send:main → DEGRADE_TO_STANDARD（POST /api/zcnet/degrade/{pluginId}）
- send:standard 和 flushMainPacket 接入 NetworkAudit（出站前/后延迟跟踪）
- AggregatorHealthCheck.stop() 支持优雅关闭

#### 调度器扩展（ZCSScheduler.java，+59 行）
- 新增 `task`（主线程同步执行，全信任等级可用）和 `io`（异步 IO 线程池，CPU/2 核心，S 级禁止）
- shutdown() 增加 ioPool.shutdown()

#### 服务注册表跨信任审计（ServiceRegistry.java，+33 行）
- 新增 `get(Class<T>, callerId, callerTrust)`: S→N SECURITY / N→S WARN / N→N INFO 三级跨信任审计

#### 插件加载器信任管理（PluginLoader.java，+59 行）
- `demotePlugin()`: 运行时降级（CrashGuard/BanHammer 调用），同步更新 SimplePluginContext.trust
- `markAsBanned()`: → BLACKLISTED
- P16 签名黑名单：扫描时跳过签名黑名单 JAR
- 提前注册 PluginDescriptor（修复 resolveTrust() 时序问题）

#### 辅助层
- **NbtBridge.java（新建）**: FMLPaths 抽象层，使 ZCSLIBCommon 脱离 NeoForge API 直接依赖
- **Cancelable.java / ZCSLEventBus.java**: 跨信任事件安全加固
- **ZCSResourceManager.java**: shared/global/external/studio 四条虚拟路径补齐
- **PECValidator.java**: UNKNOWN 判断修正（null → TrustLevel.UNKNOWN）

#### 文档与测试
- docs/: 类图 + 时序图 + M7-M10 评估
- docx/11-m6-mc-integration.md, docx/17-mixin-adapter.md（新建）
- tests/SmokeTest.java + run-smoke.ps1 + server.py（模拟聚合器）
- ROADMAP.md 重写（-627/+? 行）

**原因**:
- M6 是 ZCSLIB 深度 MC 集成的关键里程碑，补齐了 M5 中全部缺失的 mcapi/monitor/security/sandbox 四个子系统
- ZCSLIBCommon 拆分解决了跨 NeoForge 版本适配问题——ZCSLIB.java 只对 21.1 编译，Common 无 NeoForge API 依赖可通过版本适配团队在不同 NeoForge 版本上替换入口类
- 身份合并（所有 dispatch 统一注入 pluginId）是 P0 架构分歧的最终解决——之前每个 dispatch 自己从 args[0] 提取 pluginId，存在调用方可伪造自身 ID 的安全漏洞
- BLACKLISTED/UNKNOWN 信任级别是 BanHammer 自动隔离的基石——没有运行时 trust 降级能力，autoban 就是空谈
- LagGuard→CrashGuard→order() 三明治是 defense-in-depth：超时→崩溃隔离→业务逻辑，任何一层失败不渗透到其他插件
- DEGRADE_TO_STANDARD 是 S 级网络策略的具体落地——不直接拒绝而是降级到可审计的标准 HTTP POST

### [17:47] 代可行 — 全项目 82 .java 文件完整列表确认
确认 M6 完工，待归档。
