# ZCSLIB 实现路径 v0.1.0

## 总原则

1. **坚壳软芯。** 每个 Phase 结束时产物可编译、可启动、可通过 NeoForge 加载。
2. **向下不依赖。** 严禁后一 Phase 完成后回头改前一 Phase 的公开接口。
3. **S 级先行。** 每实现一个子系统，立即补齐信任分级拦截逻辑，不拖到后期补。
4. **order() 驱动。** 所有功能入口通过 `kernel.order()` 暴露，内部子系统不直接对插件公开。

---

## 依赖拓扑

```
Phase 1  内核骨架
   │
Phase 2  PluginContext + Logger
   │
   ├── Phase 3  PEC 解析 + 插件加载器
   │      │
   │      ├── Phase 4  资源管理器
   │      │      │
   │      │      └── Phase 6  配置 + PDC 持久化
   │      │
   │      ├── Phase 7  服务注册表
   │      │
   │      └── Phase 8  事件总线
   │
   ├── Phase 5  异步调度器
   │
   └── Phase 9  网络抽象层
          │
          └── Phase 10  审计 + 崩溃日志
                    │
                    └── Phase 11  Daemon 守护进程
                           │
                           └── Phase 12  自适应演化
```

---

## Phase 1 — 内核骨架（预计 1 个 BUILD）

**目标：** 模组可被 NeoForge 加载，输出一行日志证明存在。

### 产出文件

```
src/main/java/com/dlzstudio/zcslib/
├── ZCSLIB.java              # @Mod 主类，NeoForge 入口
└── kernel/
    └── ZCSKernel.java       # 空壳内核，暂只输出启动日志

src/main/resources/
└── META-INF/
    └── neoforge.mods.toml   # 模组元数据
```

### 验证标准

```
[INFO] [ZCSLIB] [N/zcslib] [Main]: ZCSLIB Kernel v0.1.0 initialized.
```

### 不做的

- 不读任何配置
- 不加载任何插件
- 不接受任何 order()

---

## Phase 2 — PluginContext + Logger（预计 1-2 个 BUILD）

**目标：** 日志系统跑通，PluginContext 接口定型。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
├── api/
│   ├── PluginContext.java       # 7 方法接口
│   ├── TrustLevel.java          # N / R / A / S 枚举
│   └── OrderResult.java         # ok + error + data
├── log/
│   └── ZCSLogger.java           # 双轨制：[ZCSLIB] 前缀 + 独立文件
└── kernel/
    └── ZCSKernel.java           # 新增 order() 空壳方法体 + dispatch 路由骨架
```

### 关键决策

- **ZCSLogger** 构造函数签名为 `ZCSLogger(String pluginId, TrustLevel level, Path logDir)`
- 双轨输出在构造时决定，不在每次调用时判断
- 格式锁定：`[{TIME}] [{LEVEL}] [{TRUST}/{PLUGIN_ID}] [{THREAD}]: {MESSAGE}`

### 验证标准

```java
// 硬编码测试（Phase 3 之前手动构造 PluginContext）
ZCSLogger log = new ZCSLogger("test", TrustLevel.N, Path.of("logs/zcslib"));
log.info("Logger online");
// →
// [2026-06-13 16:00:00.000] [INFO] [N/test] [Main]: Logger online
// 同时写入 logs/zcslib/test.log
```

---

## Phase 3 — PEC 解析 + 插件加载器（预计 2-3 个 BUILD）

**目标：** 从 `plugins/` 目录发现并加载 Native Plugin。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
├── pec/
│   ├── PECScanner.java          # 4 路径优先级扫描（/META-INF/zcslib/PEC.json → /PEC.json）
│   ├── PECSchema.java           # PEC JSON → Java 对象映射
│   └── PECValidator.java        # 环境校验 → PASS / SOFT_FAIL / HARD_FAIL
├── loader/
│   ├── PluginClassLoader.java   # 隔离 ClassLoader（禁止访问内核私有包）
│   ├── PluginDescriptor.java    # 插件元数据（id/version/trust/classloader/pec）
│   └── PluginLoader.java        # 扫描 → 分类 → 加载 → 注入 PluginContext
└── kernel/
    └── ZCSKernel.java           # 整合：启动时 PluginLoader.scan() → 日志输出加载结果
```

### 关键决策

- **PECScanner 命中即止**：按白皮书 4 个路径顺序扫描，找到第一个就停
- **PluginClassLoader 黑名单**：禁止访问 `com.dlzstudio.zcslib.kernel.internal.*`
- **虚拟 PEC 生成**：R/A/S 级无 PEC 的组件，内核自动生成虚拟 PEC
- **加载顺序**：按 PEC 中 `priority` 字段升序加载（-100 最先，100 最后）

### 验证标准

```
[INFO] [ZCSLIB] [N/zcslib] [Main]: Scanning plugins/ ...
[INFO] [ZCSLIB] [N/zcslib] [Main]: Found 2 plugin(s)
[INFO] [ZCSLIB] [N/zcslib] [Main]: [N/iems] Loaded (PEC verified)
[INFO] [ZCSLIB] [N/zcslib] [Main]: [R/external-mod] Loaded (Virtual PEC)
```

### 不做的

- 不实现 Standalone Mod 的虚拟 PEC（那是 Phase 7-8 后的扩展）
- 不实现 Auto-Adapt 标记扫描

---

## Phase 4 — 资源管理器（预计 2-3 个 BUILD）

**目标：** 目录结构创建 + 沙箱路径映射 + 磁盘配额。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
└── resource/
    ├── ZCSResourceManager.java  # 虚拟路径 → 物理路径映射
    ├── ResourceSandbox.java     # 路径规范化 + .. 阻断 + 敏感目录拒止
    └── DiskQuota.java           # S 级 500MB / 其他 2GB 配额检查
```

### 关键决策

- **根目录锁定**：所有路径强制解析到 `./config/DLZstudio/ZCSLIB/plugins/{id}/`
- **S 级逃逸检测**：`new File("../..")` → `ResourceSandbox.canonicalize()` 抛安全异常
- **配额实现**：`java.nio.file.FileStore.getUsableSpace()` 检查分区剩余，非精确字节计数（性能优先）

### 目录结构（自动创建）

```
config/DLZstudio/ZCSLIB/
├── global/           # 内核全局（PDC 后端）
├── shared_res/       # 共享资源（只读）
├── cache/            # 全局缓存
└── plugins/
    └── {plugin_id}/
        ├── config/
        └── data/
```

### 验证标准

```java
ctx.kernel().order("resource:file", "/config/server.json");
// → 返回 File("config/DLZstudio/ZCSLIB/plugins/test/config/server.json")

// S 级逃逸测试
ctx.kernel().order("resource:file", "../../../saves/world/player.dat");
// → OrderResult.error = "SANDBOX: Path escape denied"
```

---

## Phase 5 — 异步调度器（预计 2-3 个 BUILD）

**目标：** L0-L3 线程模型 + 舱壁隔离 + 批量同步队列。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
└── scheduler/
    ├── ZCSScheduler.java        # order() 路由到的调度实现
    ├── ComputePool.java         # L3 计算线程池（FixedThreadPool, per-plugin 队列）
    ├── SyncQueue.java           # 批量同步队列（tick 末合并执行）
    └── Bulkhead.java            # 舱壁：每插件独立有界队列 + 超时熔断
```

### 关键决策

- **ComputePool 线程数**：`CPU 核心数 × 2`，每插件最大占用 `min(核心数, 4)` 线程
- **SyncQueue 合并**：同一 tick 内同一插件的多次 queueSync → 合并为一次执行
- **熔断阈值**：插件单次 compute 超 50ms → WARN；连续 3 次 → 熔断该插件 L3 权限 30 秒
- **S 级拦截**：`scheduler:compute` → 直接返回 `"FORBIDDEN:S"`

### 验证标准

```java
// 插件 A 正常调度
ctx.kernel().order("scheduler:compute", () -> heavyCalculation());
// → 日志: [ZCSLIB] [N/plugin_a] [L3-Compute-1]: Task started
// → 日志: [ZCSLIB] [N/plugin_a] [L3-Compute-1]: Task completed (42ms)

// S 级被拒
suspiciousCtx.kernel().order("scheduler:compute", task);
// → OrderResult.error = "FORBIDDEN:S compute"
```

---

## Phase 6 — 配置 + PDC 持久化（预计 2 个 BUILD）

**目标：** 插件可读写私有配置文件和持久化键值对。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
├── config/
│   └── ConfigManager.java       # JSON/TOML 配置加载 + 热重载
└── persistence/
    └── PDCBackend.java          # NBT 序列化 + 磁盘读写
```

### 关键决策

- **配置格式**：优先 JSON，后续支持 TOML（与白皮书 `network.toml` 一致）
- **PDC 后端**：Minecraft `CompoundTag` → NBT 文件，自动处理 `BlockPos`、`ItemStack` 等 MC 类型
- **原子写入**：先写 `.tmp` → `Files.move(tmp, target, ATOMIC_MOVE)`，防止崩溃损毁数据

### 验证标准

```java
// 配置
ctx.kernel().order("config:save", "server.json", Map.of("port", 8080));
Map cfg = (Map) ctx.kernel().order("config:load", "server.json").data;
// → {port: 8080}

// PDC
ctx.kernel().order("pdc:save", "player_homes", homeData);
HomeData loaded = (HomeData) ctx.kernel().order("pdc:load", "player_homes").data;
```

---

## Phase 7 — 服务注册表（预计 2 个 BUILD）

**目标：** 插件间通过接口松耦合交互，信任分级拦截 + 审计。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
└── service/
    ├── ServiceRegistry.java     # register / get / getWithMeta
    ├── ServiceWrapper.java      # instance + providerId + providerLevel
    └── ServiceSecurityFilter.java  # S 级注册黑名单（Kernel/Admin/PlayerData/NetworkMain）
```

### 关键决策

- **注册表存储**：`ConcurrentHashMap<Class<?>, ServiceEntry>`，线程安全
- **S 级黑名单关键词**：`Kernel`、`Admin`、`PlayerData`、`NetworkMain` — 含任一即拒
- **跨信任调用日志**：N→S 记 WARN，S→N 记 SECURITY

### 验证标准

```java
// N 级注册服务
iemsCtx.kernel().order("service:register", IEMSService.class, new IEMSServiceImpl());

// 另一个 N 级获取
ServiceWrapper<IEMSService> w = mi2Ctx.kernel().order("service:get:meta", IEMSService.class).data;
w.getInstance().getEnergyData();  // N→N, 正常使用

// S 级尝试注册核心服务
suspiciousCtx.kernel().order("service:register", PlayerDataService.class, impl);
// → OrderResult.error = "FORBIDDEN:S core service 'PlayerDataService'"
```

---

## Phase 8 — 事件总线（预计 2-3 个 BUILD）

**目标：** @Subscribe 驱动的事件系统，自动线程投递，S 级审计。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
└── event/
    ├── Event.java                # 基类（cancelled + setCancelled）
    ├── ZCSEventBus.java          # register / post / unregister
    ├── EventDispatcher.java      # 线程检测 → 自动投递到主线程同步队列
    ├── SystemEvent.java          # 系统事件标记接口（PluginLoadedEvent 等）
    └── Subscribe.java            # 注解
```

### 关键决策

- **线程安全**：L3 计算池中 post() → 自动入队 → 下一 tick 主线程执行监听器
- **S 级系统事件拦截**：注册时检查监听器方法参数类型，`instanceof SystemEvent` → 拒绝注册
- **S 级玩家事件审计**：`PlayerBreakBlockEvent` 等 → 自动写入 `logs/zcslib/audit/S/{id}_audit.log`

### 验证标准

```java
// 插件注册监听
ctx.kernel().order("event:register", new Object() {
    @Subscribe void onEnergyUpdate(EnergyUpdateEvent e) {
        // 此处永远在主线程，可安全修改世界
    }
});

// L3 线程中发布
ctx.kernel().order("scheduler:compute", () -> {
    ctx.kernel().order("event:post", new EnergyUpdateEvent(500));
    // 事件未立即执行，已入主线程队列
});

// S 级试图监听系统事件 → 拒绝
suspiciousCtx.kernel().order("event:register", systemEventListener);
// → OrderResult.error = "FORBIDDEN:S SystemEvent 'PluginLoadedEvent'"
```

---

## Phase 9 — 网络抽象层（预计 3-4 个 BUILD）

**目标：** 统一出口管制 + 主包聚合 + 离线策略。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
└── network/
    ├── ZCSNetwork.java           # sendStandard / sendMain / setOfflineStrategy
    ├── MainPacketAssembler.java  # Tick 级缓冲 + 外壳生成 + 心跳注入
    ├── OfflineQueue.java         # RETRY_LATER 队列 + 磁盘持久化 + 50MB 上限
    └── AggregatorHealthCheck.java # HTTP Ping / TCP 连接检查
```

### 关键决策

- **主包缓冲**：每 tick 结束合并所有插件的 sendMain → 一个主包发出
- **序列号**：从 `global/network_seq.dat` 读取自增，防重放
- **离线队列上限**：50MB 硬顶，超出 → 强制 DISCARD + ERROR 日志
- **S 级强制 DEGRADE**：无论插件设置什么策略，S 级一律降级为标准包

### 验证标准

```java
// N 级正常使用主包
ctx.kernel().order("network:offline", OfflineStrategy.RETRY_LATER);
ctx.kernel().order("network:send:main", energyData);
// → 内核组装主包，聚合器在线则立即发送

// 聚合器离线 + RETRY_LATER
// → 数据存入 plugins/iems/data/offline_queue/1718313600.bin
// → 30 秒后重试连接，连接成功则重放队列

// S 级 sendMain → 拦截
suspiciousCtx.kernel().order("network:send:main", data);
// → OrderResult.error = "FORBIDDEN:S main packet"

// S 级 sendStandard → 允许但审计
// → 写入 logs/zcslib/audit/S/suspicious_network.log
```

---

## Phase 10 — 审计 + 崩溃日志（预计 1-2 个 BUILD）

**目标：** 分级审计存储 + 崩溃隔离 + 日志滚动。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
└── log/
    ├── AuditLogger.java          # 按信任等级分目录写入
    ├── CrashHandler.java         # 全局异常捕获 → 内核/插件分离
    └── LogRotator.java           # 策略：Audit 7天 / Kernel Crash 永久 / Plugin Crash 5次
```

### 目录结构

```
logs/zcslib/
├── audit/
│   ├── N/{plugin_id}_{date}.log
│   ├── R/{plugin_id}_{date}.log
│   ├── A/{plugin_id}_{date}.log
│   └── S/{plugin_id}_{date}.log
└── crash/
    ├── kernel_{timestamp}.log         # 内核崩溃（永久保留）
    └── plugins/
        ├── iems/
        │   └── crash_{timestamp}.log  # 最多保留 5 个
        └── mi2/
            └── crash_{timestamp}.log
```

### 验证标准

```java
// S 级插件触发玩家事件 → 自动审计
// logs/zcslib/audit/S/old_mod_2026-06-13_163000.log:
// [2026-06-13 16:30:00.123] [AUDIT] [S/old_mod] PlayerBreakBlockEvent by Steve (uuid: ...)

// 插件崩溃 → 隔离存储
// crash/plugins/suspicious_mod/crash_2026-06-13_163500.log 包含:
// - 插件 ID + 信任等级
// - 异常堆栈
// - 最近 50 条该插件的审计日志
```

---

## Phase 11 — Daemon 守护进程（预计 4-5 个 BUILD）

**目标：** 独立 JVM 进程，监控 Host 存活，自动重启，训练模式。

### 产出（独立项目，非 NeoForge mod）

```
zcslib-daemon/
├── build.gradle              # 纯 Java SE，零依赖（连 NeoForge 都不引）
├── src/main/java/com/dlzstudio/zcslib/daemon/
│   ├── ZCSDaemon.java        # main() 入口
│   ├── HostMonitor.java      # PID 监控（ProcessHandle）
│   ├── IPCBridge.java        # Socket 通信（Host ↔ Daemon）
│   ├── RestartEngine.java    # 崩溃→分析 MEM → 重启进程
│   └── ui/
│       └── TrainingUI.java   # Swing 黑底绿字终端风格
```

### 关键约束（白皮书 9.2.2 铁律）

1. **零依赖**：仅用 `java.base` 模块
2. **纯 Java SE**：不引用 Minecraft、NeoForge、任何第三方库
3. **单命令启动**：`java -jar zcslib-daemon.jar`
4. **常驻内存 < 50MB**

### IPC 协议

```
Host → Daemon:  "HEARTBEAT:{timestamp}:{mem_mb}:{tps}"
Daemon → Host:  "ACK" / "RESTART" / "LOCK:{plugin_id}" / "EVOLVE:{action}"
```

### 验证标准

```
> java -jar zcslib-daemon.jar
[DAEMON] ZCSLIB Daemon v0.1.0
[DAEMON] Monitoring host PID 12345 ...
[DAEMON] Host heartbeat: 60 TPS, 2048MB, 3 plugins
[DAEMON] Host crash detected at 16:45:32
[DAEMON] Analyzing crash logs ...
[DAEMON] Restarting host JVM ...
[DAEMON] Host restarted (PID 12389)
```

---

## Phase 12 — 自适应演化（预计 3-4 个 BUILD）

**目标：** MEM 记忆文件 + 三阶段生命周期 + 训练模式引擎。

### 产出

```
src/main/java/com/dlzstudio/zcslib/
├── evolution/
│   ├── EvolutionEngine.java   # Phase 1/2/3 状态机
│   ├── MEMFile.java           # .mem.json 读写
│   ├── CodeFolder.java        # 骨架/血肉拆分 + 惰性解压
│   └── Acclimatizer.java      # 跨环境适应协议（静默期→微调→转正）
└── daemon/
    └── Trainer.java           # 训练模式引擎（激进试探 → 崩溃 → 修复 → 循环）
```

### 关键决策

- **Phase 迁移**：新插件 Phase 1（Kitten，纯观察）→ 资源紧张 Phase 2（Adult，试探压缩）→ 多次验证 Phase 3（Elder，确定策略）
- **新插件冷却**：前 10 次调用禁止任何压缩（白皮书 9.7）
- **N 级护盾**：主线程逻辑永不被压缩或熔断

### 验证标准

```
# MEM 文件示例
{
  "pluginId": "mi2",
  "phase": "ELDER",
  "safe_actions": ["compress_shader_cache"],
  "unsafe_actions": ["compress_frame_buffer"],
  "crash_count": 3,
  "survival_hours": 120.5
}
```

---

## 里程碑与预计 BUILD 数

| 里程碑 | 包含 Phase | 预计 BUILD | 状态 |
|:---|:---|:---|:---|
| **M1: 骨架** | 1-2 | 3 | 内核可启动 + 日志可写 |
| **M2: 单机内核** | 3-6 | 9 | 插件可加载/调度/读写文件 |
| **M3: 多插件协同** | 7-8 | 5 | 服务注册 + 事件总线 |
| **M4: 联网底座** | 9-10 | 5 | 主包通信 + 完整审计 |
| **M5: 不死鸟** | 11-12 | 8 | Daemon 守护 + 自适应演化 |

**总计：约 30 BUILD**

---

## 不在此路径中的内容（明确推迟）

| 内容 | 原因 |
|:---|:---|
| Standalone Mod 自动适配（Auto-Adapt） | 虚拟 PEC 生成逻辑需 M2 稳定后再设计 |
| 配置管理器插件（ZCSConfigAdmin） | 需服务注册表成熟后作为"第一个 N 级特殊插件"实现 |
| Gradle 插件 / SDK | 内核 API 稳定后（M3 之后）再提供给外部开发者 |
| 可视化 Daemon UI 完整版 | Phase 11 先出 CLI 文本界面，Swing 窗口留到 M5 后期 |
