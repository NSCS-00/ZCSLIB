# M6 MC深度集成 — 详细设计 v1.1 (Phase 13-16)

> **安全边界**: 全部 MC API 仅对 DreamWorker（梦擎）开放。访问控制: StackWalker 验证调用方 → 非 DreamWorker 拒绝。
> **版本**: ZCSLIB v0.2.0

---

## Phase 13: MC API 绑定层 (`zcslib/mcapi/`)

### McPort — 梦擎门禁

```
McPort (package-private constructor, ZCSKernel 初始化)
├── static open() → McPort    // StackWalker: caller == DreamWorker 才放行
├── world()  → WorldAPI
├── players() → PlayerAPI
├── tick()   → TickAPI
├── blocks() → BlockAPI
└── commands() → CommandAdapter
```

**访问控制:**
```java
public static McPort open() {
    Class<?> caller = StackWalker.getInstance(RETAIN_CLASS_REFERENCE).getCallerClass();
    if (caller != zcslib.daemon.DreamWorker.class) {
        throw new SecurityException("McPort reserved for DreamWorker");
    }
    if (instance == null) throw new IllegalStateException("McPort not initialized");
    return instance;
}
```

**初始化:** `McPort.init(mcServer)` 在 `ZCSLIB.onServerStarting` 事件中调用。

---

### WorldAPI — 世界只读

```
int getLoadedChunkCount()
List<ChunkPos> getLoadedChunks()
Set<ChunkPos> getForceLoadedChunks()

boolean isChunkLoaded(int x, int z)
List<EntitySnapshot> getEntitiesInAABB(double x1,y1,z1, x2,y2,z2)
List<EntitySnapshot> getEntitiesByType(String entityType, int radius, BlockPos center)

BlockStateSnapshot getBlockState(BlockPos pos)
boolean canSeeSky(BlockPos pos)
int getLightLevel(BlockPos pos)

long getGameTime()
long getDayTime()
boolean isThundering()
boolean isRaining()
String getDimensionKey()
long getSeed()
```

### PlayerAPI — 不可变快照

```
List<PlayerSnapshot> getOnlinePlayers()
int getPlayerCount()
Optional<PlayerSnapshot> getPlayer(UUID uuid)
Optional<PlayerSnapshot> getPlayer(String name)

record PlayerSnapshot(UUID uuid, String name, String displayName,
    double x,y,z, String dimension, float health, float maxHealth,
    int foodLevel, int experienceLevel, String gameMode,
    boolean isOp, boolean isAlive) {}
```

### TickAPI — TPS/相位

```
long getServerTickCount()
double getAverageTPS()
double getMinuteTPS()
double getMSPT()
double getPercentile95MSPT()

TickHealth getTickHealth()
long getSkippedTickCount()
long getTickOvershootCount()

void onTickStart(Runnable r)
void onTickEnd(Runnable r)
void onServerTickPre(Runnable r)
void onServerTickPost(Runnable r)

enum TickHealth { HEALTHY, BEHIND, SKIPPING, FROZEN }
```

### BlockAPI — 方块状态只读

```
BlockStateSnapshot getBlock(BlockPos pos)
Optional<TileEntitySnapshot> getTileEntity(BlockPos pos)
boolean isLoaded(BlockPos pos)
int getRedstonePower(BlockPos pos)
boolean isSolid(BlockPos pos)

record TileEntitySnapshot(BlockPos pos, String type, CompoundTag nbt) {}
```

### CommandAdapter — MC命令

```
void registerRoot()
// /zcslib plugins                 → plugin list + trust + status
// /zcslib plugin <id> info        → descriptor
// /zcslib plugin <id> reload      → hot-reload
// /zcslib debug tps               → live TPS/memory dump
// /zcslib debug audit             → recent audit (10 lines)
// /zcslib ban <id>                → BanHammer
// /zcslib unban <id>              → lift ban
```

---

## Phase 14: 真实存档 + 假人测试 (`zcslib/sandbox/`)

> **设计原则**: 不是内存快照——创建一个真实 MC 世界存档。
> 梦擎在存档里通过假人执行视角转动、移动、E键等必要操作，采集指标后销毁存档并分析日志。

### TestWorld — 世界存档管理

```
// 超平坦世界，种子固定（可复现）
Path createWorld(String preset)  // 默认 "minecraft:the_void;minecraft:grass_block;minecraft:air"
Path createWorld()               // 默认超平坦
Path getWorldPath()
boolean exists()

// 存档命名：saves/zcslib_verify/{pluginId}_{timestamp}/
// 独立目录，与主世界零冲突

void destroyWorld()              // 直接删除 saves/zcslib_verify/ 下本次测试目录
                                 // Files.walk() + delete，不用 trash（确保干净）

// 内部：通过 MinecraftServer 注册新维度，
// LevelStem 指向独立 saves 目录
```

### BotPlayer — 假人（ServerPlayer 子类，零渲染）

```
// 核心：ServerPlayer 子类，不注册 skin/floodgate/PacketDistributor
// 所有发包路径无操作，不做渲染
class BotPlayer extends ServerPlayer {
    join(TestWorld world)                  // 登录 → spawn (0, 64, 0)
    void disconnect()                      // 正确清理，不留 ghost player

    // —— 必需动作集 ——
    void look(float yaw, float pitch)      // 转动视角（setYRot/setXRot + sync）
    void move(Direction, int blocks)       // 移动 N 格（teleportTo 递增）
    void jump()                            // 空格
    void sneak(boolean on)                 // Shift
    void toggleInventory()                 // E 键 — 触发 ContainerOpenCounter tick
    void interact(BlockPos pos)            // 右键（useItemOn，确保目标已加载）
    void attack(BlockPos pos)              // 左键
    ChatStatus chat(String message)        // 发送消息/命令

    // 只读
    Vec3 getPosition()
    float getYaw()
    float getPitch()
    boolean isOnline()
}
```

### TestRunner — 编排

```
TestResult verify(String pluginId, List<L3Rule> candidates) {

    // 1. 创建独立世界
    TestWorld world = TestWorld.create("superflat");

    // 2. 启动前基线
    PerfSnapshot t0 = McPort.open().monitor().snapshot("pre-verify");

    // 3. 加载插件到这个世界
    kernel.loadPlugin(pluginId);

    // 4. 假人执行最少必要动作
    BotPlayer bot = new BotPlayer(world);
    bot.join();
    bot.look(0, 0);
    bot.move(Direction.NORTH, 3);   // 走 → 加载 chunk → 触发 tick
    bot.jump();
    bot.toggleInventory();          // E 键 → 触发 player tick + 背包逻辑
    bot.look(90, 0);
    bot.move(Direction.EAST, 5);
    bot.jump();
    bot.interact(new BlockPos(5, 64, -3));
    bot.disconnect();

    // 5. 结束指标
    PerfSnapshot t1 = McPort.open().monitor().snapshot("post-verify");

    // 6. 日志分析
    LogAnalysis analysis = LogAnalyzer.parse(world.getWorldPath());

    // 7. 销毁存档
    world.destroyWorld();

    // 8. 构建返回
    return buildResult(t0, t1, analysis);
}
```

### LogAnalyzer — 日志分析

```
LogAnalysis parse(Path worldDir) {
    Path logFile = worldDir.resolve("logs/latest.log");

    // 解析指标：
    int crashCount;           // 异常退出信号
    int tpsDrops;             // "Can't keep up" / "Running behind" 次数
    int pluginErrors;         // ZCSLIB 关键词匹配（ERROR/WARN级别）
    int tickSkips;            // 跳帧计数
    int entityLeaks;          // 假人 disconnect 后仍有残留实体引用
    int chunkLeaks;           // 世界销毁前后 chunk 数对比
    String crashTrace;        // 原始堆栈（如有）

    // 返回结构化结果
}

// 注意：日志是文件级分析，不需要 McPort
// 可以同时被梦擎 daemon 模式（子进程 stdout）和 Mod 模式（日志文件）复用
```

### Phase 14 目录

```
zcslib/sandbox/
├── TestWorld.java        # 世界创建/销毁
├── BotPlayer.java        # 假人（extends ServerPlayer）
├── TestRunner.java       # 编排器（通过 McPort 驱动）
└── LogAnalyzer.java      # 日志解析（纯文件分析，无 MC 依赖）
```

---

## Phase 15: 性能监控 + 自保护 (`zcslib/monitor/`)

### PerfMonitor — 实时指标

```
double getTPS()
double getMSPT()
long getHeapUsedMB()
long getHeapMaxMB()
long getOffHeapUsedMB()
int getLoadedChunks()
int getLoadedEntities()
int getTileEntities()
int getForceloadChunks()

Map<String, PluginPerf> getPluginPerf()
void snapshot(String reason)  // 全量导出到审计

record PluginPerf(double avgLatencyMs, int timeoutCount,
    int errorCount, long lastActivityTick) {}
```

### LagGuard — 断路器

```
void beginCall(String pluginId, String subsystem, int maxMs)
void endCall(String pluginId, String subsystem)
// 超时 → auto-interrupt + audit + violation counter

boolean isViolating(String pluginId)  // > 3 violations in 60s
void resetViolations(String pluginId)
```

### LeakDetector — 资源泄漏

```
Set<ChunkPos> detectChunkLeaks()       // 每 5 分钟
List<String> detectListenerLeaks()     // 插件卸载时
Set<Thread> detectThreadLeaks(String pluginId)  // 每 10 分钟
```

### CrashGuard — 隔离罩

```
OrderResult execute(String pluginId, Supplier<OrderResult> action)
// try-catch → on crash: CrashHandler + return OrderResult.fail
```

### AutoSave — 紧急存盘

```
void tick()                    // 每 tick
void setInterval(int ticks)    // 默认 6000
void forceSave()               // TPS < 10 连续 5 tick → 紧急存盘
```

---

## Phase 16: 安全加固 (`zcslib/security/`)

### PermissionNode

```
String PERM_ROOT = "zcslib.plugin"
// zcslib.plugin.<id>
// zcslib.plugin.<id>.network
// zcslib.plugin.<id>.command
// zcslib.plugin.<id>.console
// zcslib.operator
```

### CommandWhitelist

```
void registerCommands(String pluginId, List<String> commands)
void unregisterAll(String pluginId)
OrderResult dispatch(String pluginId, String cmd, String[] args, CommandSourceStack src)
```

### NetworkAudit

```
void logOutbound(String pluginId, String target, int sizeBytes, long latencyMs)
void logInbound(String source, String packetType, int sizeBytes)
NetworkStats getStats(String pluginId)
boolean detectBurst(String pluginId)
boolean detectLargePayload(String pluginId)
```

### BanHammer — 5 条件自动隔离

```
void autoReview()     // tick 钩子
// 条件: >5 crash/60s | 3+ violation连续 | >50 chunk泄漏 | burst+large payload | DreamWorker标记

void banPlugin(String pluginId, String reason)
void unbanPlugin(String pluginId)
Set<String> getBannedPlugins()
boolean isBanned(String pluginId)
```

---

## 梦擎调用示例

```java
// DreamWorker.validateRules() 内:

// 拿到 MC 端口
McPort port = McPort.open();

// 采集基线
PerfSnapshot t0 = port.monitor().snapshot("pre-baseline");

// 创建测试世界 + 假人
TestWorld world = TestWorld.create("superflat");
BotPlayer bot = new BotPlayer(world);

try {
    bot.join();
    port.crashGuard().execute(pluginId, () -> {
        bot.look(0, 0);
        bot.move(Direction.NORTH, 3);
        bot.jump();
        bot.toggleInventory();
        bot.look(90, 0);
        bot.move(Direction.EAST, 5);
        bot.jump();
        bot.interact(new BlockPos(5, 64, -3));
        return OrderResult.ok();
    });
    bot.disconnect();

    // 分析
    PerfSnapshot t1 = port.monitor().snapshot("post-verify");
    LogAnalysis log = LogAnalyzer.parse(world.getWorldPath());

    if (log.crashCount > 0 || log.tpsDrops > 5) {
        port.security().banHammer().autoReview();
        return new ValidationResult(false, ...);
    }

    return new ValidationResult(true, ...);

} finally {
    world.destroyWorld();  // 无论成败，一定销毁
}
```

---

## 安全层（不变）

| 层 | 机制 | 拦截 |
|---|---|---|
| L1 编译期 | `McPort` 构造 package-private | 插件无法 new |
| L2 运行时 | StackWalker 调用方检查 | 非 DreamWorker → SecurityException |
| L3 类加载 | PluginClassLoader 拒绝 `zcslib.mcapi.*` | 插件无法 import |
| L4 网络 | NetworkAudit 包审计 | 异常流量自动告警 |
| L5 行为 | BanHammer 动态检测 | 恶意行为自动隔离 |
