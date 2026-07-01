# ZCSLIB 实现路径 v0.2.0

## 总原则
1. **坚壳芯片** 每个 Phase 结束时产物可编译、可启动、可通过 NeoForge 加载。
2. **向下不依赖** 严禁后一 Phase 完成后回头改前一 Phase 的公开接口。
3. **S 级先行** 每实现一个子系统，立即补齐信任分级拦截逻辑，不拖到后期补。
4. **order() 驱动** 所有功能入口通过 `ctx.order()` 暴露，内部子系统不直接对插件公开。
5. **梦擎专属** MC 深度集成（M6）仅对 DreamWorker 开放，插件不可直接访问 MC 内部。

---

## 依赖拓扑

```
Phase 1  内核骨架
    ├── Phase 2  PluginContext + Logger
    │    ├── Phase 3  PEC 解析 + 插件加载器
    │    │    ├── Phase 4  资源管理器
    │    │    │    └── Phase 6  配置 + PDC 持久化
    │    │    ├── Phase 7  服务注册表
    │    │    │    └── Phase 8  事件总线
    │    │    └── Phase 5  异步调度器
    │    └── Phase 9  网络抽象层
    │         └── Phase 10  审计 + 崩溃日志
    │              └── Phase 11  Daemon + 自演化包
    │                   └── Phase 12  认知与自演化体系
    │                        └── Phase 13  MC API 绑定层 ─┐
    │                             └── Phase 14  虚拟存档 ─┤ M6
    │                                  └── Phase 15  性能监控 ─┤ MC深度集成
    │                                       └── Phase 16  安全加固 ─┘
    │
    └── Phase 17  Mixin 适配器 ─── M7 ─── 插件 Mixin 声明 + refmap 版本重写
         └── Phase 18  物品注册接管 ─── M8 ─── ZcsRegistryManager + 代理壳 + 网络同步
```

---

## M1: 内核骨架 + API 基础（Phase 1-2）

### Phase 1 — 内核骨架
**目标：** 模组可被 NeoForge 加载，输出一行日志证明存在。

**产出文件：**
```
src/main/java/zcslib/
├── ZCSLIB.java              # @Mod 主类，NeoForge 入口
└── kernel/
    └── ZCSKernel.java       # 空壳内核，暂只输出启动日志
src/main/resources/
└── META-INF/
    └── neoforge.mods.toml   # 模组元数据
```

**验证标准：**
```
[INFO] [ZCSLIB] [N/zcslib] [Main]: ZCSLIB Kernel v0.1.0 initialized.
```

---

### Phase 2 — PluginContext + Logger
**目标：** 建立插件与内核的最小双向接口，提供日志系统。

**产出文件：**
```
zcslib/
├── api/
│   ├── PluginContext.java   # 7 方法
│   └── OrderResult.java     # 统一返回类型 (ok/error + data)
├── log/
│   └── ZCSLogger.java       # SLF4J 双轨：MC 控制台 + logs/zcslib/{id}.log
└── kernel/
    └── ZCSKernel.java       # 追加空 order() 框架
```

---

## M2: 插件框架 + 资源调度（Phase 3-6）

### Phase 3 — PEC 解析 + 插件加载器
4 路径扫描优先级：`META-INF/zcslib/PEC.json > zcslib.plugin.json > zcslib.PEC.json > PEC.json`

信任分类：N (Native) / R (Recognized) / A (Auto-Adapt) / S (Suspicious)

### Phase 4 — 资源管理器
虚拟路径映射，路径沙箱防逃逸，磁盘配额（S 级 500MB / 其他 2GB）

### Phase 5 — 异步调度器
L3 线程池（CPU×2，per-plugin cap min(cores,4)，S 级禁止）
SyncQueue（tick-end 批量合并），Bulkhead（3 次失败锁定 30s）

### Phase 6 — 配置 + PDC 持久化
JSON 配置热重载（原子写入 tmp→rename），NBT 键值持久化（每 key 独立 .dat）

---

## M3: 事件服务（Phase 7-8）

### Phase 7 — 服务注册表
ConcurrentHashMap 单例，S 级保护命名空间（Kernel/Admin/PlayerData/NetworkMain）

### Phase 8 — 事件总线
@Subscribe 注解扫描，优先级排序，ignoreCancelled，S 级阻塞系统事件

---

## M4: 网络层（Phase 9）

多插件子包合并（MainPacketAssembler），签名/加密/序列号，离线队列（50MB 硬顶 + DEGRADE_TO_STANDARD），聚合器健康检查

---

## M5: 审计 + Daemon + 认知（Phase 10-12）

### Phase 10 — 审计 + 崩溃日志
N/R/A/S 四级审计，日志轮转，L1 RingBuffer 崩溃快照

### Phase 11 — Daemon 双入口 + 自演化包
`java -cp ZCSLIB.jar zcslib.Launcher dream` 启动梦擎
DreamWorker（L2→MC 验证闭环），RestartEngine（MC 子进程管理）
L3Merger / TrainingSetPacker / TrainingSetImporter / ParamFreezer

### Phase 12 — 认知与自演化体系
L1-L4 四阶记忆，参数体系（Global/Local/Bilateral/Attention），隔离体系（QuarantineDecider/StubReplacer/KernelCache/CollateralDegrader/TimelineRollback）

---

## M6: MC 深度集成（Phase 13-16）← NEW

**定位：** 从"框架能跑"到"带框架的服能玩"。
**安全边界：** 全部 MC API 仅对 DreamWorker（梦擎）开放，插件不可直接访问 MC 内部。

### Phase 13 — MC API 绑定层 (`mcapi/`)
```
zcslib/mcapi/
├── McPort.java              # 梦擎专属入口（Gatekeeper 单例）
├── WorldAPI.java            # 世界读写、区块加载状态、实体查询
├── PlayerAPI.java           # 玩家在线列表、UUID↔Entity 映射、背包快照
├── TickAPI.java             # tick 相位注入（PRE/POST）、TPS/卡顿探针
├── BlockAPI.java            # 方块状态读写、TileEntity 访问（安全沙箱）
└── CommandAdapter.java      # /zcslib plugin list|reload|info|debug
```

### Phase 14 — 虚拟存档 (`sandbox/`)
```
zcslib/sandbox/
├── VirtualSave.java         # 世界快照（不落盘），插件可干跑测试
├── DryRunContext.java       # 插件请求"试运行一次"，回滚副作用
└── AutoRollback.java        # 插件崩溃后 3 tick 内自动回滚其写入
```

### Phase 15 — 性能监控 + 自保护 (`monitor/`)
```
zcslib/monitor/
├── PerfMonitor.java         # 实时 TPS/MSPT/memory/chunk-load/实体数
├── LagGuard.java            # 插件单次 order() 超时 Xms → 中断 + 审计
├── LeakDetector.java        # chunk 泄漏检测、listener 未注销告警
├── CrashGuard.java          # 单插件崩不崩服（try-catch 隔离 + 降级）
└── AutoSave.java            # 定时世界快照 + 崩溃前紧急存盘
```

### Phase 16 — 安全加固 (`security/`)
```
zcslib/security/
├── PermissionNode.java      # 插件级权限节点，映射 MC 权限系统
├── CommandWhitelist.java    # 插件可暴露 /zcslib run <plugin> <cmd>
├── NetworkAudit.java        # 每包入/出记录 IP/插件/大小/耗时
└── BanHammer.java           # 恶意插件自动隔离（禁止加载，拉黑签名）
```

### M6 完成标准
| 指标 | 当前 | 目标 |
|---|---|---|
| 单插件崩溃 | 可能带崩服 | 服继续跑，审计记录 |
| TPS 可见性 | 无 | 实时监控 + 超标告警 |
| 世界数据安全 | 无保护 | 虚拟存档 + 崩溃回滚 |
| 恶意插件防护 | TrustLevel 静态 | 动态行为检测 + 自动隔离 |
| 玩家交互 | 零 | 命令接口 + 权限系统 |

---

## M7: Mixin 版本适配（Phase 17）

**定位：** Mixin 是插件访问 MC 内部的唯一合法路径。ZCSLIB 提供适配层而非让插件直接写 Mixin。

**生命周期：** @Mod 构造期，类加载前——早于所有子系统。

### Phase 17 — Mixin 适配器 (`mixin/`)

```
zcslib/mixin/
├── MixinAdapter.java        # 入口: scan → resolve → merge → register
├── RefmapResolver.java      # refmap 重写引擎（Mojang→当前版本 obf）
├── MappingTable.java        # 版本映射表加载/查询
└── MixinConfigMerger.java   # 多插件 Mixin config 合并

config/DLZstudio/ZCSLIB/mappings/
├── 21.1.json                 # Mojang → 21.1 obf 映射（基线）
├── 26.1.json                 # Mojang → 26.1 obf 映射
└── ...                       # 其他支持版本
```

**插件声明格式** (`zcslib.mixin.json`):
```json
{
  "pluginId": "my-plugin",
  "mixins": [
    "zcslib.myplugin.mixin.MixinItemStack",
    "zcslib.myplugin.mixin.MixinServerLevel"
  ]
}
```

**流程：** 扫描插件 JAR → 读 refmap → 查版本映射表重写 obf 名 → 合并生成单一 `mixins.zcslib.json` → MixinBootstrap 注册

**设计文档：** `docx/17-mixin-adapter.md`

---

## M8: 物品注册接管（Phase 18）

**依赖：** M6 (McPort/NetworkAPI) + M7 (Mixin 适配器)

**核心组件：**
```
zcslib/registry/
├── ZcsRegistryManager.java  # 注册调度器（启动期/运行时注册 + ID 映射）
├── ZcsItemCatalog.java      # 虚拟物品元数据存储
├── ZcsProxyItem.java        # 通用代理 Item（DataComponents 路由）
├── ZcsNetworkSync.java      # 自定义 Payload 广播 + 客户端注入
└── ZcsPersistence.java      # 存档 ID 映射持久化
```

**设计文档：** 2026-06-19 用户提交的《ZCSLIB核心注册管理器开发方案》

---

## M9: P0 决策 + API 冻结

- Typed Interfaces vs order() 范式定案
- PluginContext 最终方法集锁定
- PEC Schema 版本化（v1.0）
- 白皮书 v1.0 与代码完全对齐

---

## M10: 开发者体验 → 发布

- 完整 Javadoc
- 3 个示例插件（服务注册型 / 事件驱动型 / 网络发包型）
- 错误码字典 + 排查指南
- 构建工具链（模板项目 + Gradle plugin）
- 版本升级 / 迁移指南

---

## 信任分级速查

| 等级 | 标识 | 限制 |
|---|---|---|
| N (Native) | 有 PEC 签名 | 无限制 |
| R (Recognized) | 有 dlzstudio 包名 | 禁止 network:send:main, scheduler:compute |
| A (Auto-Adapt) | 无 PEC 有适配特征 | 同 R + 所有操作写入审计日志 |
| S (Suspicious) | 仅有 mods.toml | 禁止 compute, send:main, event:post, service:register 保护命名空间 |

---

## 版本历史

| 版本 | 里程碑 | BUILD 范围 |
|---|---|---|
| 0.1.0 | M1 | 01-02 |
| 0.2.0 | M2 | 03-09 |
| 0.2.0-M3 | M3 | 10-15 |
| 0.2.0-M4 | M4 | 16-22 |
| 0.2.0-M5 | M5 | 23-27 |
| 0.2.0-M5.1 | 身份合并 | 28-29 |
| 0.3.0 | M6 | 30+ |
| 0.4.0 | M7 | Mixin 适配器 |
| 0.5.0 | M8 | 物品注册接管 |
| 0.6.0 | M9 | API 冻结 |
| 1.0.0 | M10 | 发布 |
