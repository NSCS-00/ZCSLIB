# ZCSLIB M7-M10 评估报告

> **评估日期**: 2026-06-27  
> **评估人**: Bob (Architect)  
> **当前基线**: ZCSLIB v0.3.0 (M6 完成) / NeoForge 21.1 / Java 21  
> **评估范围**: M7 (Mixin 适配器) → M8 (物品注册接管) → M9 (API 冻结) → M10 (发布)

---

## 一、总览

| 里程碑 | Phase | 难度 | MC/NF 新代码依赖数 | 需新建独立文件 | 综合风险 |
|--------|-------|------|-------------------|---------------|---------|
| **M7** | 17 (Mixin 适配器) | **Medium** | **0** | 0 (mixin/ 为纯 Java + 数据) | 🟢 低 |
| **M8** | 18 (物品注册接管) | **Hard** | **~12 个类** | 3-4 个 (mcapi/ 新文件) | 🟡 中 |
| **M9** | API 冻结 | **Easy** | **0** | 0 (文档/决策) | 🟢 极低 |
| **M10** | 发布 | **Easy-Medium** | **0** | 0 (文档/工具链) | 🟢 极低 |

---

## 二、各 Phase 详细评估

### Phase 17 — Mixin 适配器（M7）

**实现难度**: **Medium**

**理由**:

Mixin 适配器的核心逻辑是：扫描 JAR → 解析 JSON → 字符串查表重写 → 合并 JSON → 调用 Mixin API 注册。**完全不涉及 Minecraft 或 NeoForge 代码 API**。所有操作都是文件 I/O、字符串匹配和 JSON 操作。

难度分解：
- **MappingTable** (Easy)：纯 JSON 加载 + HashMap 查询。标准 CRUD。
- **RefmapResolver** (Medium)：需理解 Mixin refmap JSON 格式（Mojang 名 `@` 注解 → S/R 签名 → obf 名映射），做字符串级替换。
- **MixinConfigMerger** (Easy-Medium)：合并多个 mixin config JSON，去重混合列表，处理冲突。
- **MixinAdapter** (Medium)：编排上述三步 + 调用 `MixinBootstrap.addConfig()`。集成到 ZCSLIB.java 的 `@Mod` 构造期。
- **映射表生成** (Medium-Hard)：需要获取 NeoForge 各版本的 Mojang→obf 映射数据，提取差异项。可借助 Agent 自动化，但首次手动验证需要了解映射格式。

**MC/NeoForge API 依赖清单**:

| API 类 | 用途 | 来源 | 归属文件 | 跨版本风险 |
|--------|------|------|---------|-----------|
| `org.spongepowered.asm.mixin.Mixins` | Mixin config 注册 | Mixin 库 (第三方) | `mixin/MixinAdapter.java` | 🟢 极低 — Mixin 库 API 高度稳定 |
| — | — | — | — | — |

> **关键结论：M7 零个 `net.minecraft.*` 或 `net.neoforged.*` 依赖。**  
> Mixin 库 (`org.spongepowered.asm.*`) 是独立的第三方库，由 NeoForge 在运行时提供。  
> 映射表 JSON 是纯数据文件，不同版本只需替换数据文件，无需改代码。

**唯一集成点**：`ZCSLIB.java` 构造函数中新增一行 `MixinAdapter.bootstrap(pluginsDir)`。这是对已有独立文件的调用追加，不引入新的 MC API。

**现有文件变更**：

| 文件 | 变更 | 说明 |
|------|------|------|
| `ZCSLIB.java` | +1 行调用 | `MixinAdapter.bootstrap(...)` 在构造期 |
| `build.gradle` | 新增 Mixin 依赖 | `compileOnly 'org.spongepowered:mixin:...'` |

**新建文件（全部在 `zcslib/mixin/`，零 MC 依赖）**:

| 文件 | MC 依赖 |
|------|---------|
| `mixin/MappingTable.java` | 无 |
| `mixin/RefmapResolver.java` | 无 |
| `mixin/MixinConfigMerger.java` | 无 |
| `mixin/MixinAdapter.java` | 仅 `org.spongepowered.asm.mixin.Mixins` |
| `config/DLZstudio/ZCSLIB/mappings/21.1.json` | 数据文件 |
| `config/DLZstudio/ZCSLIB/mappings/26.1.json` | 数据文件 |

---

### Phase 18 — 物品注册接管（M8）

**实现难度**: **Hard**

**理由**:

M8 是 M7-M10 中技术复杂度最高的里程碑。它需要同时对接 MC 的 **Item 体系**、**Registry 系统**、**Network Payload 管道** 和 **NBT 持久化**。每个子系统都有版本敏感的 API，且 Proxy Item 模式要求直接继承 `net.minecraft.world.item.Item`。

难度分解：
- **ZcsProxyItem / ProxyItemBase** (Hard)：继承 `net.minecraft.world.item.Item` 是硬需求。必须在 `use()`、`getDescriptionId()` 等方法中正确拦截并路由到虚拟物品元数据。`Item.Properties` 在版本间可能增减字段。
- **ZcsRegistryManager / RegistryBridge** (Medium)：利用 NeoForge 公开的 `DeferredRegister` + `RegisterEvent` API。但代理 Item 的注册时机和 ID 映射需要仔细处理。
- **ZcsNetworkSync / NetworkPayloadBridge** (Medium-Hard)：NeoForge 21.1 的网络 API（`CustomPacketPayload`、`RegisterPayloadHandlersEvent`、`IPayloadHandler`）在 22.x+ 版本中有调整。需同时处理客户端注入逻辑。
- **ZcsItemCatalog** (Easy-Medium)：纯元数据存储，可用 NBT（通过 NbtBridge）或纯 Java Map。无新增 MC 依赖。
- **ZcsPersistence** (Easy)：ID 映射的存档持久化，完全通过已有 NbtBridge 路由。无新增 MC 依赖。

**MC/NeoForge API 依赖清单**:

#### A. Item 体系（ProxyItemBase）

| API 类 | 用途 | 归属文件 | 跨版本风险 |
|--------|------|---------|-----------|
| `net.minecraft.world.item.Item` | 代理 Item 必须 extends | **`mcapi/ProxyItemBase.java`** (新建) | 🟡 中 — 方法签名在版本间稳定，但 `Properties` 构造参数的字段名可能变化 |
| `net.minecraft.world.item.Item.Properties` | Item 构造参数 | 同上 | 🟡 中 — 1.21.2+ 有 `stacksTo()` → `stacksTo` 字段名变化；26.1 引入 DataComponents 重构 |
| `net.minecraft.world.item.ItemStack` | use() 参数/返回值 | 同上 | 🟡 中 — 构造方式在 1.21.2+ 变化（`ItemStack(item, count)` → 新工厂方法） |
| `net.minecraft.world.InteractionHand` | use() 参数 | 同上 | 🟢 低 — 枚举，基本不变 |
| `net.minecraft.world.InteractionResultHolder` | use() 返回值 | 同上 | 🟢 低 — 泛型包装类 |
| `net.minecraft.world.entity.player.Player` | use() 参数 | 同上 | 🟢 低 |
| `net.minecraft.world.level.Level` | use() 参数 | 同上 | 🟢 低 |

#### B. Registry 体系（RegistryBridge）

| API 类 | 用途 | 归属文件 | 跨版本风险 |
|--------|------|---------|-----------|
| `net.neoforged.neoforge.registries.DeferredRegister` | 启动期物品注册 | **`mcapi/RegistryBridge.java`** (新建) | 🟡 中 — NeoForge 公共 API，但 `DeferredRegister.create()` 泛型签名可能微调 |
| `net.minecraft.core.registries.Registries` | 注册表类型标识 (`Registries.ITEM`) | 同上 | 🟢 低 — Mojang 注册表键，稳定 |
| `net.minecraft.resources.ResourceLocation` | 注册 ID | 同上 | 🟡 中 — 在 MC 1.21.11 (26.1) 重命名为 `Identifier`（已知破坏性变更） |
| `net.neoforged.neoforge.registries.RegisterEvent` | 运行时动态注册 | 同上 | 🟡 中 — 事件类可能调整 |
| `net.minecraft.core.registries.BuiltInRegistries` | 注册表查询 | 同上 | 🟢 低 — 标准 API，但内部结构在 deobf 后可能微调 |

#### C. Network Payload 体系（NetworkPayloadBridge）

| API 类 | 用途 | 归属文件 | 跨版本风险 |
|--------|------|---------|-----------|
| `net.minecraft.network.protocol.common.custom.CustomPacketPayload` | 自定义 Payload 接口 | **`mcapi/NetworkPayloadBridge.java`** (新建) | 🔴 高 — 22.x+ 引入了 `StreamCodec` 替代旧的 `FriendlyByteBuf` 序列化；`Type` 定义方式变化 |
| `net.minecraft.network.FriendlyByteBuf` | Payload 序列化 | 同上 | 🟡 中 — 写入/读取方法稳定，但 26.1 变为 `RegistryFriendlyByteBuf` |
| `net.minecraft.network.RegistryFriendlyByteBuf` | 26.1+ 序列化 | 同上 | 🔴 高 — 26.1 新增类，21.1 不存在 |
| `net.minecraft.resources.ResourceLocation` | Payload ID | 同上 | 🟡 中 — 同 Registry 部分风险 |
| `net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent` | Payload 注册 | 同上 | 🟡 中 — 事件 API 可能调整 |
| `net.neoforged.neoforge.network.registration.PayloadRegistrar` | Payload 配置 | 同上 | 🟡 中 — 22.x+ 引入的新 API |

#### D. 持久化（已有基础设施）

| API 类 | 用途 | 归属文件 | 跨版本风险 |
|--------|------|---------|-----------|
| `net.minecraft.nbt.CompoundTag` | ID 映射序列化 | **已有 `persistence/NbtBridge.java`** | 🟡 中 — 已评估 |
| `net.neoforged.fml.loading.FMLPaths` | 存档路径 | **已有 `persistence/NbtBridge.java`** | 🟢 低 — 已评估 |

#### E. Mixin 注入类（M8 Mixin 目标）

| Mixin 目标 | 用途 | 文件 | 说明 |
|------------|------|------|------|
| `net.minecraft.world.item.Item` | 拦截物品创建/使用 | `mixin/zcslib/MixinItem.java` | 可选 — 如用纯公共 API 则不需要 |
| `net.minecraft.world.item.ItemStack` | 拦截 Stack 操作 | `mixin/zcslib/MixinItemStack.java` | 可选 — 增强代理 Item 的透明性 |

> **注**：Mixin 注入类的 `.java` 文件不直接出现在 registry/ 包中，它们在编译时通过 Mixin 注解处理器生成 refmap，运行时由 M7 适配器注册。Mixin 类本身也放在 `mixin/zcslib/` 下，属于 M7 文件体系的扩展。

---

### M9 — API 冻结

**实现难度**: **Easy**

**理由**: 纯决策/文档里程碑。需要做出 3 个关键决策并将结果文档化。无代码变更。

**关键决策项**:

| 决策项 | 选项 A | 选项 B | 影响 |
|--------|--------|--------|------|
| Typed Interfaces vs order() | 强类型接口（`ctx.items().register(...)`） | 字符串 order（`ctx.order("item:register", ...)`） | 影响 PluginContext 的 API 设计方向 |
| PluginContext 最终方法集 | 追加 item/registry/network 领域方法 | 仅通过 order 动态路由 | 影响插件开发者的调用方式 |
| PEC Schema v1.0 | 新增 item/registry/mixin 声明段 | 保持现有结构，通过扩展字段支持 | 影响 PEC 验证器 |

**MC/NeoForge API 依赖**: **零**

**现有文件变更**: `api/PluginContext.java` 可能需要新增方法签名（但无 MC 依赖）

---

### M10 — 发布

**实现难度**: **Easy-Medium**

**理由**: 文档和工具链工作。量大但不涉及复杂技术挑战。

**工作项**:

| 工作项 | 类型 | 说明 |
|--------|------|------|
| 完整 Javadoc | 文档 | 对所有公开 API 类/方法添加标准 Javadoc |
| 3 个示例插件 | 示例代码 | 服务注册型 / 事件驱动型 / 网络发包型 |
| 错误码字典 | 文档 | 所有 `OrderResult.error()` 码的完整列表 + 排查指南 |
| Gradle Plugin | 工具链 | ZCSLIB 插件开发模板 + 构建集成 |
| 迁移指南 | 文档 | 21.1 → 26.1 迁移步骤（基于已有迁移评估报告） |

**MC/NeoForge API 依赖**: **零**

---

## 三、依赖收敛分析

### 3.1 MC/NeoForge API 依赖按文件归属分布

| 独立文件 | 已有/新建 | 包含的 MC/NF 依赖 | M7 | M8 | 总数 |
|---------|----------|-------------------|----|----|------|
| `ZCSLIB.java` | 已有 | `@Mod`, `IEventBus`, `ModContainer`, `NeoForge`, 3×Event, `Logger` | +1 (MixinAdapter 调用) | — | 8→9 |
| `mcapi/McPort.java` | 已有 | `MinecraftServer` | — | — | 1 |
| `mcapi/WorldAPI.java` | 已有 | `MinecraftServer`, `ServerLevel`, `BlockPos`, `ChunkPos`, `BlockState`, `AABB`, `Entity` | — | — | 7 |
| `mcapi/PlayerAPI.java` | 已有 | `MinecraftServer`, `ServerPlayer` | — | — | 2 |
| `mcapi/TickAPI.java` | 已有 | `MinecraftServer` | — | — | 1 |
| `mcapi/BlockAPI.java` | 已有 | `MinecraftServer`, `BlockPos`, `CompoundTag`, `BlockEntity`, `BuiltInRegistries` | — | — | 5 |
| `mcapi/CommandAdapter.java` | 已有 | `CommandDispatcher`, `StringArgumentType`, `CommandSourceStack`, `Commands`, `Component` | — | — | 5 |
| `persistence/NbtBridge.java` | 已有 | `CompoundTag`, `NbtAccounter`, `NbtIo`, `FMLPaths`, `FMLLoader` | — | 已复用 | 5 |
| `persistence/PDCBackend.java` | 已有 | `CompoundTag` (全部路由到 NbtBridge) | — | 已复用 | 1 |
| **`mcapi/ProxyItemBase.java`** | **新建 (M8)** | `Item`, `Item.Properties`, `ItemStack`, `InteractionHand`, `InteractionResultHolder`, `Player`, `Level` | — | 7 | 7 |
| **`mcapi/RegistryBridge.java`** | **新建 (M8)** | `DeferredRegister`, `Registries`, `ResourceLocation`, `RegisterEvent`, `BuiltInRegistries` | — | 5 | 5 |
| **`mcapi/NetworkPayloadBridge.java`** | **新建 (M8)** | `CustomPacketPayload`, `FriendlyByteBuf`, `RegistryFriendlyByteBuf`, `ResourceLocation`, `RegisterPayloadHandlersEvent`, `PayloadRegistrar` | — | 6 | 6 |
| `mixin/MixinAdapter.java` | 新建 (M7) | `org.spongepowered.asm.mixin.Mixins` (第三方，非 MC) | 1 | — | 1 |

### 3.2 收敛判定

```
✅ ZCSLIB.java           — 已有独立文件，M7 追加 1 行调用，不新增 MC 导入
✅ mcapi/ 目录            — 已有 6 个文件，M8 新增 3 个文件
✅ persistence/NbtBridge  — 已有独立文件，M8 完全复用
✅ persistence/PDCBackend — 已有，全部路由到 NbtBridge，M8 复用

新独立文件 (M8):
✅ mcapi/ProxyItemBase.java      — 继承 Item，收敛 7 个 MC 导入
✅ mcapi/RegistryBridge.java     — 收敛 5 个 NeoForge/MC 注册 API
✅ mcapi/NetworkPayloadBridge.java — 收敛 6 个网络 Payload API

无需收敛的:
✅ zcslib/mixin/                  — 4 个纯 Java 文件，零 MC 依赖
✅ zcslib/registry/               — 纯业务逻辑，通过 mcapi/ 间接访问 MC
✅ config/DLZstudio/ZCSLIB/mappings/  — 纯 JSON 数据文件
```

### 3.3 最终判定

> **✅ 所有 MC/NeoForge API 依赖均可收敛在独立文件边界内。**
>
> M7 零新增 MC 依赖，仅需在 `ZCSLIB.java`（已有独立文件）追加 1 行调用。  
> M8 新增的 ~18 个 MC/NeoForge 导入分散在 3 个新建的 `mcapi/` 文件中，每个文件职责单一、边界清晰。  
> `zcslib/registry/` 包内的业务逻辑文件（ZcsRegistryManager、ZcsItemCatalog、ZcsNetworkSync、ZcsPersistence）不直接引用任何 `net.minecraft.*` 或 `net.neoforged.*` 类。

---

## 四、跨版本风险矩阵

### 4.1 核心 API 风险详表 (21.1 → 26.1)

| API | 21.1 路径 | 26.1 变更 | 风险等级 | M8 影响 |
|-----|----------|----------|---------|---------|
| `Item` | `net.minecraft.world.item.Item` | 构造参数 `Properties` 字段名变化；DataComponents 体系引入 | 🟡 | ProxyItemBase 构造需适配 |
| `ItemStack` | `net.minecraft.world.item.ItemStack` | 1.21.2+ 构造方式变化（`new ItemStack(item,count)` → 工厂方法） | 🟡 | use() 返回值需适配 |
| `ResourceLocation` | `net.minecraft.resources.ResourceLocation` | **26.1 重命名为 `Identifier`** | 🔴 | RegistryBridge + NetworkPayloadBridge 两处受影响 |
| `CustomPacketPayload` | `net.minecraft.network.protocol.common.custom.CustomPacketPayload` | 22.x+ `StreamCodec` 替代 `FriendlyByteBuf`；`Type` 定义变化 | 🔴 | NetworkPayloadBridge 需版本分支 |
| `FriendlyByteBuf` | `net.minecraft.network.FriendlyByteBuf` | 26.1 变为 `RegistryFriendlyByteBuf`（含 registry access） | 🟡 | NetworkPayloadBridge 序列化逻辑 |
| `DeferredRegister` | `net.neoforged.neoforge.registries.DeferredRegister` | NeoForge 公共 API，跨版本稳定 | 🟢 | 无 |
| `Registries.ITEM` | `net.minecraft.core.registries.Registries` | Mojang 标准注册表键，稳定 | 🟢 | 无 |
| `RegisterEvent` | `net.neoforged.neoforge.registries.RegisterEvent` | 事件类可能调整参数 | 🟡 | RegistryBridge 事件处理 |
| `CompoundTag` | `net.minecraft.nbt.CompoundTag` | 1.21.6 引入通用编解码范式 | 🟡 | 已收敛在 NbtBridge |
| `NbtIo` | `net.minecraft.nbt.NbtIo` | 方法签名可能微调 | 🟡 | 已收敛在 NbtBridge |
| Mixin target 类名 | 如 `ItemStack` | 类名稳定（Mojang 名不变，obf 名变化由 M7 处理） | 🟢 | M7 映射表自动处理 |

### 4.2 高风险项缓解策略

| 高风险 API | 缓解方案 |
|-----------|---------|
| `ResourceLocation → Identifier` | 在 `NbtBridge.java` 中新增静态版本检测方法 `is26Plus()`；`RegistryBridge` 和 `NetworkPayloadBridge` 通过条件编译或运行时反射选择导入路径 |
| `CustomPacketPayload` 体系变更 | `NetworkPayloadBridge` 内部做版本适配：21.1 路径用 `FriendlyByteBuf`，26.1 路径用 `StreamCodec`。可参考 NbtBridge 的 `HAS_VERSION_INFO` 模式 |
| `RegistryFriendlyByteBuf` | 仅在 26.1 编译时引用；21.1 编译路径不导入此类。通过 `NetworkPayloadBridge` 内部条件分支处理 |

### 4.3 总体跨版本风险评级

| 维度 | M7 | M8 | 整体 |
|------|----|----|------|
| API 稳定性 | 🟢 零 MC 依赖 | 🟡 3 个高风险点 | 🟡 |
| 缓解可行性 | — | ✅ 可参考 NbtBridge 模式 | ✅ |
| 迁移工作量 | 0 人·天 | 1-2 人·天（NetworkPayloadBridge 版本适配） | 1-2 人·天 |

---

## 五、推荐推进顺序与建议

### 5.1 推荐推进顺序

```
推荐: M7 → M9 (并行启动) → M8 → M10

  M7 (Mixin 适配器)          M9 (API 冻结开始)
  ┌──────────────────┐       ┌──────────────────────┐
  │ 无 MC 依赖        │       │ 无代码依赖            │
  │ 纯 Java + 数据    │       │ 可与 M7/M8 并行讨论   │
  │ 4-5 人·天         │       │ Typed vs order() 决策 │
  └──────┬───────────┘       └──────────┬───────────┘
         │                              │
         │    M7 完成后解锁 M8          │  决策完成后锁定 API
         ▼                              ▼
  M8 (物品注册接管)              PluginContext 最终化
  ┌──────────────────┐
  │ 依赖 M6 + M7      │
  │ ~18 个 MC API     │
  │ 7-10 人·天        │
  └──────┬───────────┘
         │
         ▼
  M10 (发布)
  ┌──────────────────┐
  │ Javadoc + 示例    │
  │ 工具链 + 迁移指南  │
  │ 5-7 人·天         │
  └──────────────────┘
```

**理由**：
1. **M7 先行** — 零 MC 依赖、风险最低、为 M8 提供 Mixin 基础设施
2. **M9 并行** — 纯决策/文档，不需要等 M7/M8 完成即可启动讨论。Typed Interfaces vs order() 决策应在 M8 实现前定案，因为 M8 的 Item/Registry/Network API 需要通过 PluginContext 暴露给插件
3. **M8 在 M7+M9 决策后推进** — M8 是技术最密集的里程碑，需要 M7 的 Mixin 适配器可用 + M9 的 API 范式定案来指导 `registry/` 的对外接口设计
4. **M10 收尾** — 在所有核心代码完成后统一输出文档和工具链

### 5.2 关键设计建议

#### 建议 1：M8 ProxyItemBase 使用 NbtBridge 版本检测模式

```java
// mcapi/ProxyItemBase.java
public class ProxyItemBase extends Item {
    private static final boolean IS_26_PLUS = NbtBridge.hasVersionInfo() == false;
    // ...根据 IS_26_PLUS 适配 Properties 字段名
}
```

> 将版本检测结果暴露为 package-private static flag，让 `mcapi/` 内其他新文件复用。

#### 建议 2：M8 NetworkPayloadBridge 做双版本编译路径

```java
// mcapi/NetworkPayloadBridge.java
// 方案 A: 两个内部实现类，运行时选择
if (IS_26_PLUS) {
    handler = new PayloadHandlerV26();
} else {
    handler = new PayloadHandlerV21();
}

// 方案 B: 条件编译（Gradle variant）
// src/main/java21/ → 编译到 21.1
// src/main/java25/ → 编译到 26.1
```

> 推荐方案 A（运行时选择），与 NbtBridge 模式一致，避免 Gradle 多源码集复杂性。

#### 建议 3：M8 RegistryBridge 中 ResourceLocation 预埋 Identifier 适配

```java
// mcapi/RegistryBridge.java
// 内部统一用 String id，封装构造逻辑
static Object createId(String namespace, String path) {
    if (IS_26_PLUS) {
        return Identifier.of(namespace, path);  // 26.1
    } else {
        return new ResourceLocation(namespace, path);  // 21.1
    }
}
```

> 所有 `ResourceLocation` 构造收敛到一个工厂方法，26.1 迁移时只需改此处。

#### 建议 4：M9 Typed Interfaces vs order() 决策建议

当前 ZCSLIB 使用 `ctx.order("verb:target", args...)` 范式（字符串路由）。M8 的 item/registry/network 子系统需要考虑是继续保持该范式，还是引入 Typed Interfaces：

| 维度 | order() (现状) | Typed Interfaces |
|------|---------------|-----------------|
| 一致性 | ✅ 与 M1-M6 一致 | ❌ 需要新增 PluginContext 方法 |
| 类型安全 | ❌ 运行时字符串匹配 | ✅ 编译期类型检查 |
| 扩展性 | ✅ 新 order 无需改 PluginContext | ❌ 每个新领域需加接口方法 |
| MC API 隔离 | ✅ 字符串路由不泄露 MC 类型 | ❌ 接口返回值可能泄露 MC 类型 |

> **建议：保持 order() 范式**。M8 的新功能通过 `ctx.order("item:register", ...)`, `ctx.order("network:send", ...)` 等暴露。Typed Interfaces 放在 PluginContext 中将迫使 PluginContext 依赖 mcapi/ 的类型（如 ItemSnapshot），破坏当前 PluginContext 的纯 Java 属性。

#### 建议 5：M8 Mixin 注入策略 — 优先公共 API，Mixin 作为后备

M8 的 RegistryBridge 优先使用 NeoForge 公共 API（`DeferredRegister` + `RegisterEvent`）完成物品注册。仅当公共 API 无法满足需求时（如需要在 Registry 冻结后动态插入物品），才引入 Mixin 注入。这降低了 M8 对 M7 的强依赖，也减少了跨版本维护的 Mixin 目标数量。

---

## 六、附录

### A. 预估工作量汇总

| 里程碑 | Phase | 核心代码 | MC 适配 | 映射表/配置 | 测试 | 文档 | **总计** |
|--------|-------|---------|---------|------------|------|------|---------|
| M7 | 17 | 3 人·天 | 0 | 1 人·天 | 0.5 人·天 | 0.5 人·天 | **5 人·天** |
| M8 | 18 | 5 人·天 | 2 人·天 | 0 | 1.5 人·天 | 1 人·天 | **9.5 人·天** |
| M9 | — | 0 | 0 | 0 | 0 | 2 人·天 | **2 人·天** |
| M10 | — | 0 | 0 | 0 | 1 人·天 | 5 人·天 | **6 人·天** |
| **总计** | | **8** | **2** | **1** | **3** | **8.5** | **22.5 人·天** |

### B. M8 MC API 依赖收敛文件—完整清单

```
mcapi/
├── McPort.java                # 已有 — Gatekeeper 单例
├── WorldAPI.java              # 已有 — 世界只读
├── PlayerAPI.java             # 已有 — 玩家快照
├── TickAPI.java               # 已有 — TPS 监控
├── BlockAPI.java              # 已有 — 方块只读
├── CommandAdapter.java        # 已有 — /zcslib 命令
├── ProxyItemBase.java         # 新建 — 代理 Item（extends Item）
├── RegistryBridge.java        # 新建 — 注册 API 适配
└── NetworkPayloadBridge.java  # 新建 — 网络 Payload 适配

persistence/
├── NbtBridge.java             # 已有 — NBT I/O + 版本桥
└── PDCBackend.java            # 已有 — 持久化（路由到 NbtBridge）

zcslib/
└── ZCSLIB.java                # 已有 — @Mod 入口（+1 行 M7 调用）

zcslib/mixin/
├── MixinAdapter.java          # 新建 — 入口（无 MC 依赖）
├── RefmapResolver.java        # 新建 — refmap 重写（无 MC 依赖）
├── MappingTable.java          # 新建 — 映射表查询（无 MC 依赖）
└── MixinConfigMerger.java     # 新建 — config 合并（无 MC 依赖）

zcslib/registry/
├── ZcsRegistryManager.java    # 新建 — 注册调度（纯 Java，通过 mcapi/）
├── ZcsItemCatalog.java        # 新建 — 元数据存储（纯 Java）
├── ZcsNetworkSync.java        # 新建 — 网络同步（纯 Java，通过 mcapi/）
└── ZcsPersistence.java        # 新建 — 存档持久化（纯 Java，通过 NbtBridge）
```
