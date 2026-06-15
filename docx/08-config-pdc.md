# 08 — 配置与持久化

ZCSLIB 提供两种持久化方案：

| 组件 | 格式 | 适用场景 | 原子写入 |
|:---|:---|:---|:---:|
| ConfigManager | JSON | 人类可编辑的配置文件 | ✅ |
| PDCBackend | NBT (.dat) | 程序内部键值存储 | ✅ |

---

## 配置管理 (ConfigManager)

### 加载配置

```java
OrderResult r = ctx.order("config:load", ctx.getPluginId(), "settings.json");
if (r.isOk() && r.getData() != null) {
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) r.getData();

    boolean enabled = (boolean) config.getOrDefault("enabled", true);
    double maxPower = ((Number) config.getOrDefault("maxPower", 1000)).doubleValue();
    String mode = (String) config.getOrDefault("mode", "normal");

    ctx.getLogger().info("配置: enabled=%s maxPower=%.1f mode=%s", enabled, maxPower, mode);
}
```

文件路径：`config/DLZstudio/ZCSLIB/plugins/{pluginId}/config/settings.json`

文件不存在时返回 `r.getData() == null`。

### 保存配置

```java
Map<String, Object> config = Map.of(
    "enabled", true,
    "maxPower", 1500.0,
    "mode", "turbo",
    "nodes", List.of("node-1", "node-2")
);

ctx.order("config:save", ctx.getPluginId(), "settings.json", config);
```

**原子写入流程：**
1. 写 JSON 到 `settings.json.tmp`
2. 原子重命名 `settings.json.tmp` → `settings.json`

如果中途崩溃，`.tmp` 文件残留不会影响正式文件。下次保存会覆盖 `.tmp`。

### 热重载

```java
ctx.order("config:reload", ctx.getPluginId(), "settings.json");
```

强制从磁盘重新读取，不使用任何缓存。

### 支持的类型

通过 Gson 序列化：
- 基本类型：String, int, long, double, boolean
- 集合：List, Map (键必须是 String)
- 嵌套：Map 内可包含 Map 和 List
- 不支持：自定义 Java 对象（需先转换为 Map）

---

## NBT 持久化 (PDCBackend)

PDCBackend 使用 Minecraft 原生的 NBT (Named Binary Tag) 格式。

### 为什么是 NBT 而非 JSON？

- Minecraft 内部大量使用 NBT（物品堆、方块实体、玩家数据等）
- 与 MC 生态无缝集成 — BlockPos、ItemStack 等类型可直接序列化
- 压缩存储，比 JSON 更省空间

### 保存数据

```java
import net.minecraft.nbt.CompoundTag;

CompoundTag tag = new CompoundTag();
tag.putString("player_name", "Steve");
tag.putInt("level", 42);
tag.putDouble("health", 20.0);
tag.putBoolean("is_op", false);

// 嵌套
CompoundTag position = new CompoundTag();
position.putInt("x", 100);
position.putInt("y", 64);
position.putInt("z", -200);
tag.put("spawn_point", position);

ctx.order("pdc:save", ctx.getPluginId(), "player_data", tag);
```

文件路径：`config/DLZstudio/ZCSLIB/plugins/{pluginId}/data/player_data.dat`

同样原子写入：先写 `.tmp`，再 rename。

### 读取数据

```java
OrderResult r = ctx.order("pdc:load", ctx.getPluginId(), "player_data");
CompoundTag tag = (CompoundTag) r.getData();

if (!tag.isEmpty()) {
    String name = tag.getString("player_name");      // → "Steve"
    int level = tag.getInt("level");                // → 42
    CompoundTag pos = tag.getCompound("spawn_point");
    int x = pos.getInt("x");                        // → 100
}
```

键不存在时返回**空 CompoundTag**（`isEmpty() == true`），不是 null。

### 删除

```java
ctx.order("pdc:delete", ctx.getPluginId(), "player_data");
```

### Key 安全性

Key 中的危险字符（`\`, `/`, `:`, `*`, `?`, `"`, `<`, `>`, `|`）被自动替换为 `_`。

```
"player/data" → "player_data"   // / → _
"cache:v1"    → "cache_v1"      // : → _
```

---

## 选择 Config vs PDC

| | ConfigManager (JSON) | PDCBackend (NBT) |
|:---|:---|:---|
| 可读性 | ✅ 人类可读可编辑 | ❌ 二进制格式 |
| 类型安全 | ⚠️ Gson 自动推断 | ✅ NBT 强类型 |
| 嵌套深度 | 任意 | 受 NBT 限制 (512) |
| MC 类型 | ❌ 不支持 ItemStack/BlockPos | ✅ 原生支持 |
| 性能（大文件）| 中等 | 较好（压缩）|
| 适合场景 | 用户编辑的配置 | 程序自动存储的状态 |

**决策规则：** 用户需要编辑的 → JSON。程序内部状态 → NBT。
