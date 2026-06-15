# 03 — PluginContext 插件上下文

**版本**: v0.2.0-M5
**关联**: ZCSLIB 插件开发者文档

---

## 3.1 概述

`PluginContext` 是插件与 ZCSLIB 内核交互的**唯一入口**。每个插件在加载时被注入自己的 `PluginContext` 实例，提供 8 个方法：

| 方法 | 返回类型 | 说明 |
|---|---|---|
| `getPluginId()` | `String` | 插件唯一标识符（PEC 中声明的 pluginId） |
| `getDataFolder()` | `File` | 插件持久化数据目录 `plugins/{id}/data/` |
| `getConfigFolder()` | `File` | 插件配置目录 `plugins/{id}/config/` |
| `getCacheDir()` | `File` | 插件缓存目录 `cache/{id}/` |
| `getLogger()` | `ZCSLogger` | 插件专属日志记录器 |
| `getTrustLevel()` | `TrustLevel` | 当前插件的信任等级（N/R/A/S） |
| `kernel()` | `ZCSKernel` | 内核访问器（用于非 order 接口） |
| `order()` | `OrderResult` | 内核指令调用快捷方法（推荐） |

---

## 3.2 各方法详解

### getPluginId()

```java
String id = ctx.getPluginId();
// → "iems"
```

插件加载时由 PEC 中的 `pluginId` 字段决定。运行时不可变。

### getDataFolder()

```java
File dataDir = ctx.getDataFolder();
// → config/DLZstudio/ZCSLIB/plugins/iems/data/
```

自动创建目录。用于存储插件运行时产生的持久化数据（如数据库文件、缓存索引）。

### getConfigFolder()

```java
File configDir = ctx.getConfigFolder();
// → config/DLZstudio/ZCSLIB/plugins/iems/config/
```

自动创建目录。用于存放插件配置文件。

### getCacheDir()

```java
File cacheDir = ctx.getCacheDir();
// → config/DLZstudio/ZCSLIB/cache/iems/
```

自动创建目录。内核可能定期清理。不要在此存放持久化数据。

### getLogger()

```java
ZCSLogger LOG = ctx.getLogger();
LOG.info("Plugin initialized");
// [2026-06-15 20:00:00.000] [INFO] [N/iems] [Main]: Plugin initialized
```

日志格式固定：`[{TIME}] [{LEVEL}] [{TRUST}/{PLUGIN_ID}] [{THREAD}]: {MESSAGE}`。
同时输出到 MC 控制台和独立日志文件 `logs/zcslib/iems.log`。

### getTrustLevel()

```java
TrustLevel level = ctx.getTrustLevel();
if (level == TrustLevel.N) {
    // 官方插件逻辑
} else if (level == TrustLevel.S) {
    // 降级运行
}
```

信任等级决定了插件可用的指令范围（详见 [10-trust-system.md](./10-trust-system.md)）。

---

## 3.3 kernel() 与 order()

`ZCSKernel` 是插件与 ZCSLIB 九大子系统（event/service/scheduler/config/pdc/resource/network/audit/memory）的交互通道。

**推荐使用 `ctx.order()` 而非 `ctx.kernel().order()`。**

`ctx.order()` 等价于 `ctx.kernel().order()`，但额外自动记录 L1/L2 调用链追踪，供梦擎分析使用。这是插件调用内核的**首选方式**。

只有在需要访问非 order 接口（如 `getPluginVersion()`）时才直接调用 `ctx.kernel()`。

```java
// ✅ 推荐
OrderResult r = ctx.order("service:get", IEMSService.class);

// ⚠️ 仅在需要非 order 方法时
String v = ctx.kernel().getPluginVersion("iems");
```

---

## 3.4 典型插件骨架

```java
package zcslib.myplugin;

import zcslib.api.*;

public class MyPlugin {

    private PluginContext ctx;

    public void onLoad(PluginContext ctx) {
        this.ctx = ctx;
    }

    public void onEnable() {
        // 1. 注册服务
        ctx.order("service:register", MyService.class, new MyServiceImpl());

        // 2. 订阅事件
        ctx.order("event:register", new Object() {
            @Subscribe
            void onTick(ServerTickEvent e) {
                ctx.order("event:post", new MyTickEvent());
            }
        });

        // 3. 加载配置
        OrderResult r = ctx.order("config:load", "settings.json");
        if (r.isOk()) {
            // parse config
        }

        ctx.getLogger().info("MyPlugin enabled");
    }

    public void onDisable() {
        ctx.order("config:save", "settings.json", settingsData);
        ctx.getLogger().info("MyPlugin disabled");
    }
}
```

---

## 3.5 调用示例（按子系统）

### resource: 文件操作

```java
// ✅ 安全：沙箱自动限制到插件目录
ctx.order("resource:file", "/config/server.json");
// → config/DLZstudio/ZCSLIB/plugins/myplugin/config/server.json

// ❌ 被拒绝：路径逃逸
ctx.order("resource:file", "../../../saves/world/player.dat");
// → OrderResult.fail("PATH_ESCAPE")
```

### scheduler: 调度

```java
// 异步计算（N/R/A 级可用，S 级禁止）
ctx.order("scheduler:compute", () -> heavyCalculation());

// 批量同步（所有等级可用）
ctx.order("scheduler:sync", () -> updateRenderState());
```

S 级插件调用 `scheduler:compute` 会收到 `OrderResult.fail("FORBIDDEN:S")`。

### config: 配置

```java
ctx.order("config:save", "server.json", Map.of("port", 8080));
Map cfg = (Map) ctx.order("config:load", "server.json").getData();
```

### pdc: 持久化

```java
ctx.order("pdc:save", "player_homes", homeData);
HomeData loaded = (HomeData) ctx.order("pdc:load", "player_homes").getData();
```

### service: 服务注册

```java
// N/R/A 级：注册任意服务
ctx.order("service:register", IEMSService.class, new IEMSServiceImpl());

// N/R/A 级：获取服务（含元数据）
OrderResult r = ctx.order("service:get:meta", IEMSService.class);
ServiceWrapper<IEMSService> w = (ServiceWrapper<IEMSService>) r.getData();

// S 级：禁止注册核心命名空间服务
// ctx.order("service:register", PlayerDataService.class, impl);
// → OrderResult.fail("FORBIDDEN:S")
```

### event: 事件

```java
// 注册监听器
ctx.order("event:register", new Object() {
    @Subscribe(priority = EventPriority.HIGH)
    void onEnergyUpdate(EnergyUpdateEvent e) {
        if (e.isCancelable() && e.isCancelled()) return;
        handleEnergy(e.getAmount());
    }
});

// 发布事件
ctx.order("event:post", new EnergyUpdateEvent(500));

// S 级：禁止发布非系统事件
// suspiciousCtx.order("event:post", customEvent);
// → OrderResult.fail("FORBIDDEN:S")
```

### network: 网络

```java
// 标准 HTTP 发送（所有等级可用，S 级会审计）
ctx.order("network:send:standard", "GET", "/api/status", null);

// 主包发送（S 级禁止）
ctx.order("network:send:main", energyData);

// 离线重试
ctx.order("network:offline", "RETRY_LATER");
```

---

## 3.6 注意事项

1. **线程安全**：除 `scheduler:compute` 外，所有 `order()` 调用建议在主线程执行
2. **空值处理**：`service:get` 未找到时返回 `OrderResult` 的 `getData()` 为 `null`
3. **信任降级**：S 级插件调用受限指令不会抛异常，而是返回 `OrderResult.fail("FORBIDDEN:S")`
4. **路径隔离**：所有文件操作自动沙箱化，无法访问插件目录外的路径
5. **审计追踪**：`ctx.order()` 自动记录 L1（调用链快照）和 L2（事件日志），为零性能开销
