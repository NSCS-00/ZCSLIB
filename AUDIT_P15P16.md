# P15+P16 代码深度审查与修复报告

**审查人**: 寇豆码 (auditor-new)  
**审查日期**: 2025-07-14  
**审查范围**: monitor/ (5 files) + security/ (4 files) + ZCSKernel + WorldAPI  

---

## 一、审查概要

| 维度 | 发现问题数 | 已修复 | 严重程度分布 |
|------|-----------|--------|------------|
| Null pointer 风险 | 0 | — | — |
| 线程安全缺陷 | 4 | 4 | CRITICAL ×4 |
| 资源泄漏 | 0 | — | — |
| 边界条件 | 2 | 2 | MEDIUM ×1, LOW ×1 |
| 逻辑错误 | 10 | 10 | CRITICAL ×3, HIGH ×4, MEDIUM ×3 |
| 集成正确性 | 2 | 2 | HIGH ×1, MEDIUM ×1 |
| **合计** | **18** | **18** | CRITICAL:7, HIGH:5, MEDIUM:4, LOW:2 |

---

## 二、发现与修复清单

### 🔴 CRITICAL (7)

#### C1. LagGuard — timedOut[0] 竞态条件
- **文件**: `monitor/LagGuard.java:103`
- **问题**: `timedOut[0]` 在 watchdog 线程 `synchronized(completed)` 块内写入，主线程在块外读取，缺少 happens-before 关系，JMM 不保证可见性
- **修复**: 将 `timedOut[0]` 的读取包裹在 `synchronized(completed)` 块中

#### C2. CrashGuard — recentIdx / recentCount 非 volatile
- **文件**: `monitor/CrashGuard.java:43-44`
- **问题**: 环形缓冲的读写索引未声明 volatile，当 `getRecentCrashes()` 从命令线程调用时，可能看到过期的 `recentIdx` / `recentCount` 值
- **修复**: 声明 `recentIdx` 和 `recentCount` 为 `volatile`

#### C3. PerfMonitor — 环形缓冲并发读写无同步
- **文件**: `monitor/PerfMonitor.java:144-151 vs 264-269`
- **问题**: `sample()` (主线程) 写环形数组与 `snapshot()` (任意线程) 读环形数组并发，无同步。可能导致 snapshot 读到不完整/损坏数据
- **修复**: 引入 `ringLock` 对象，`sample()` 写入和 `snapshot()` 读取都在 `synchronized(ringLock)` 内完成

#### C4. NetworkAudit — 环形缓冲并发读写无同步
- **文件**: `security/NetworkAudit.java:94-98 vs 188-197`
- **问题**: 同 PerfMonitor，网络线程写入、任意线程读取，无同步
- **修复**: 引入 `ringLock`，`writeEntry()` 和 `getRecent()` 均在锁内执行

#### C5. PermissionNode — hasPermission() 完全忽略 node 参数
- **文件**: `security/PermissionNode.java:91-96`
- **问题**: `hasPermission(CommandSourceStack src, String node)` 无论 node 是什么，始终只检查 `src.hasPermission(2)`。权限节点系统形同虚设
- **修复**: 先检查 `registeredNodes.contains(node)` 再检查 op 等级

#### C6. CommandWhitelist — isCommandAllowed 信任门控未强制执行
- **文件**: `security/CommandWhitelist.java:138-144` + `kernel/ZCSKernel.java`
- **问题**: `isCommandAllowed()` 方法定义了信任级别限制但从未被调用。A/S/BLACKLISTED 插件可通过 `security:cmd-register` 注册命令
- **修复**: 在 `ZCSKernel.dispatchSecurity()` 的 `cmd-register` 分支中调用 `isCommandAllowed()`，拒绝未授权插件

#### C7. BanHammer — 区块泄漏评分使用全局指标错误归因
- **文件**: `security/BanHammer.java:118`
- **问题**: `leakDetector.getChunkDelta()` 返回全局 chunk 增量，被归因到每个插件。任一个插件造成泄漏，所有插件都受惩罚
- **修复**: 改用 `leakDetector.getPluginChunkContrib().get(pluginId)` 获取每个插件的独立贡献值

---

### 🟠 HIGH (5)

#### H1. CrashGuard — CrashInfo.tickCounter 恒为 0
- **文件**: `monitor/CrashGuard.java:122`
- **问题**: `new CrashInfo(..., 0, now)` — tickCounter 字段总是 0，崩溃时间轴信息丢失
- **修复**: 新增 `currentTick` volatile 字段 + `setCurrentTick()` 方法；ZCSKernel.onTick() 每 tick 注入值

#### H2. LeakDetector — detectOrphanedListeners() 永远返回 0
- **文件**: `monitor/LeakDetector.java:207`
- **问题**: 核心功能 stub 实现，"保守返回 0"使 listener 泄漏检测完全失效
- **修复**: 实现启发式检测 — 计算平均 handler/listener 比率，超过 50 则报告所有 listener 为可疑

#### H3. LeakDetector — entityDelta 死代码
- **文件**: `monitor/LeakDetector.java:131`
- **问题**: `entityDelta` 声明后从未被赋值，`LeakReport` 的 entityDelta 字段始终为 0
- **修复**: 新增 `WorldAPI.getLoadedEntityCount()`，在 `fullScan()` 中追踪实体增量

#### H4. BanHammer — isDreamWorkerFlagged() 无实现
- **文件**: `security/BanHammer.java:374`
- **问题**: 方法永远返回 false，DreamWorker 评分规则 (35 分) 永远不会触发
- **修复**: 实现三条检测线索：(1) A 级(无 PEC) → 可疑；(2) PEC contractSchema 含 "dreamworker"；(3) fallbackLabel 含 "dream"

#### H5. BanHammer — unbanPlugin 丢失原始信任级别
- **文件**: `security/BanHammer.java:222`
- **问题**: 解封时固定恢复为 `TrustLevel.S`，若插件原为 N 级，权限被错误降级
- **修复**: 新增 `originalTrustLevels` Map，`banPlugin()` 时保存原始级别，`unbanPlugin()` 时恢复

---

### 🟡 MEDIUM (4)

#### M1. AutoSave — tick 0 触发存档
- **文件**: `monitor/AutoSave.java:84`
- **问题**: `0 % saveIntervalTicks == 0` 为 true，启动首个 tick 触发不必要的世界存档
- **修复**: 添加 `tickCounter > 0 &&` 条件

#### M2. NetworkAudit — detectLargePayload 只检查首个匹配
- **文件**: `security/NetworkAudit.java:156-166`
- **问题**: `detectLargePayload()` 搜索最近 entry 中该插件的第一个匹配后立即返回，未检查是否真的超过阈值（原逻辑找到条目即返回 `sizeBytes > THRESHOLD`，但与函数语义不符）
- **修复**: 遍历所有 entry，找到任何一条超过阈值的才返回 true。同时纳入 ringLock 保护

#### M3. CommandWhitelist — 本地 CommandSourceStack 接口遮蔽 MC 类
- **文件**: `security/CommandWhitelist.java:37-41`
- **问题**: 自定义 `CommandSourceStack` 接口与 MC 同名类不兼容，插件无法使用 MC 命令反馈
- **修复**: 新增 `wrap(net.minecraft.commands.CommandSourceStack)` 静态适配方法

#### M4. BanHammer — 签名黑名单不持久化
- **文件**: `security/BanHammer.java`
- **问题**: `signatureBlacklist` 仅在内存中，重启丢失。`PluginLoader.scanAndLoad()` 会检查签名黑名单（第93行），但重启后黑名单为空
- **修复**: 将 `saveBans()` 改为 JSON 对象格式 `{"banned":[...], "signatures":[...]}`；`loadBans()` 兼容新旧格式解析

---

### 🟢 LOW (2)

#### L1. BanHammer — behaviorScore int 溢出风险
- **文件**: `security/BanHammer.java:163`
- **问题**: `behaviorScores.merge(pluginId, points, Integer::sum)` — 长期运行后可能溢出
- **修复**: 将 `Map<String, Integer>` 改为 `Map<String, Long>`，`merge` 使用 `Math::addExact`，所有关联常量改为 long

#### L2. LagGuard — timeoutMs 无上下界
- **文件**: `monitor/LagGuard.java:43-46`
- **问题**: timeoutMs = 0 或负数会导致 watchdog 立即触发；过大值会导致永不超时
- **修复**: `Math.max(1, Math.min(timeoutMs, 60_000))` — 限制在 1ms ~ 60s

---

## 三、受影响的非 P15/P16 文件

| 文件 | 修改内容 | 原因 |
|------|---------|------|
| `kernel/ZCSKernel.java` | dispatchSecurity 增加信任门控；onTick 注入 crashGuard.setCurrentTick | C6, H1 |
| `mcapi/WorldAPI.java` | 新增 getLoadedEntityCount() | H3 |

---

## 四、全局一致性验证

### 跨文件导入检查
- ✅ LagGuard → LagGuard 内部修改，无新增导入
- ✅ CrashGuard → 新增 `volatile` 字段，无新增导入
- ✅ PerfMonitor → 新增 `ringLock` 字段，无新增导入
- ✅ NetworkAudit → 新增 `ringLock`，无新增导入
- ✅ PermissionNode → hasPermission 新增 node 检查，调用已有 API
- ✅ CommandWhitelist → 新增 `wrap()` 静态方法，新增 `net.minecraft.commands.CommandSourceStack` / `net.minecraft.network.chat.Component` 引用
- ✅ BanHammer → 新增 `originalTrustLevels` Map (ConcurrentHashMap)，新增 `Math.addExact`，新增多方法解析
- ✅ ZCSKernel → `dispatchSecurity.cmd-register` 调用 `commandWhitelist.isCommandAllowed()`，`onTick` 调用 `crashGuard.setCurrentTick()`
- ✅ WorldAPI → 新增 `getLoadedEntityCount()` 使用 `server.getAllLevels()` + `EntityLookup.getAll()`

### 接口契约验证
- ✅ `LeakDetector.fullScan()` 返回的 `LeakReport.entityDelta` 现在被正确填充
- ✅ `BanHammer.getBehaviorScore()` 返回类型从 `int` 改为 `long`，调用方 ZCSKernel 的 `ban-score` 分支使用 `OrderResult.success(Long)` 兼容
- ✅ `CrashGuard.setCurrentTick(long)` 新增方法，在 ZCSKernel.onTick() 中被调用，不会影响其他调用方
- ✅ `CommandWhitelist.wrap()` 新增静态方法，不影响现有接口契约

### 数据流验证
- ✅ BanHammer.autoReview() → leakDetector.getPluginChunkContrib().get(pluginId) → 返回 Integer or null → 正确处理 null → 获取 int 值 → 检查 > 50
- ✅ BanHammer.banPlugin() → originalTrustLevels.put(id, pd.getTrustLevel()) → unbanPlugin() → originalTrustLevels.getOrDefault(id, S) → demotePlugin(id, restoreLevel)
- ✅ CrashGuard.recordCrash() → currentTick (由 ZCSKernel 每 tick 更新) → 写入 CrashInfo

### 无循环依赖
- ✅ 所有修改均为单向依赖，无新增循环引用

---

## 五、最终判定

```
IS_PASS: YES
```

所有 18 个问题已修复。monitor/ 和 security/ 子系统的线程安全性、逻辑正确性和功能完整性已达到可交付标准。

### 已知限制（非本次修复范围）

1. **LeakDetector 精确 listener 泄漏检测** — 需要 ZCSLEventBus 暴露 ownerId 映射，当前为启发式检测
2. **CommandWhitelist.CommandSourceStack 桥接** — `wrap()` 方法提供了基本适配，但 `sendSuccess`/`sendFailure` 依赖 Component 类型匹配
3. **BanHammer.saveBans() JSON 格式变更** — 新格式向后兼容旧格式的读取，但旧版本无法读取新格式
4. **getLoadedEntityCount() 性能** — 遍历所有维度的所有实体，在大型服务器可能较重，建议在 fullScan (5分钟间隔) 中使用
