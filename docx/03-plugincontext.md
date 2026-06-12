# 03 — PluginContext 接口

`PluginContext` 是 ZCSLIB 插件与内核通信的唯一入口。插件构造时由 ZCSLIB 注入。

---

## 包名约定

每个插件的包名应为 `zcslib.[插件ID]`。因此典型的入口类文件路径为：

```
src/main/java/zcslib/[插件ID小写无连字符]/MyPlugin.java
```

例如插件 ID `energy-grid` → 包名 `zcslib.energygrid` → 文件路径 `src/main/java/zcslib/energygrid/EnergyGridPlugin.java`

---

## 七个方法

### getPluginId()

```java
String id = ctx.getPluginId();
// → "energy-grid"
```

返回 PEC 中声明的插件 ID，全局唯一。插件不能修改此值。

### getDataFolder()

```java
File dataDir = ctx.getDataFolder();
// → config/DLZstudio/ZCSLIB/plugins/energy-grid/data/
```

插件持久化数据的专用目录，已自动创建。

### getConfigFolder()

```java
File configDir = ctx.getConfigFolder();
// → config/DLZstudio/ZCSLIB/plugins/energy-grid/config/
```

JSON 配置文件（ConfigManager）的存放目录。

### getCacheDir()

```java
File cacheDir = ctx.getCacheDir();
// → config/DLZstudio/ZCSLIB/cache/energy-grid/
```

临时缓存文件目录，重启后可被清除。

### getLogger()

```java
ZCSLogger log = ctx.getLogger();
log.info("玩家 %s 加入了世界", playerName);
log.warn("能源不足: 当前 %.1f / 需要 %.1f", current, required);
log.error("电网连接失败: %s", e.getMessage());
```

SLF4J 双轨输出：
- Minecraft 控制台（游戏内可见）
- `logs/zcslib/{pluginId}.log`（文件持久化）

所有 `%d/%f/%x` 格式符统一使用 `%s`（内核 `formatSafe()` 兼容层）。

### getTrustLevel()

```java
TrustLevel level = ctx.getTrustLevel();
if (level == TrustLevel.N) {
    // 官方插件逻辑
} else if (level == TrustLevel.S) {
    // 降级运行
}
```

返回插件信任等级（N/R/A/S），由 ZCSLIB 加载时确定，**运行时不可变**。

### kernel()

```java
ZCSKernel kernel = ctx.kernel();

// 字符串指令模式
OrderResult r = kernel.order("service:register", ctx.getPluginId(),
    "myService", "描述", provider);

kernel.order("scheduler:compute", ctx.getPluginId(), () -> {
    doHeavyWork();
    kernel.order("scheduler:queueSync", ctx.getPluginId(), () -> {
        applyResultToWorld();
    });
});
```

`ZCSKernel` 是插件与 ZCSLIB 六大子系统的唯一交互通道。完整指令集见《04 — kernel.order() 指令全集》。

---

## 典型插件骨架

```java
package zcslib.myplugin;

import zcslib.api.PluginContext;
import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;

public class MyPlugin {

    private final PluginContext ctx;

    public MyPlugin(PluginContext ctx) {
        this.ctx = ctx;

        ctx.getLogger().info("插件启动: id=%s trust=%s version=%s",
            ctx.getPluginId(),
            ctx.getTrustLevel(),
            ctx.kernel().getPluginVersion(ctx.getPluginId()));

        // 初始化数据目录
        File dataDir = ctx.getDataFolder();
        if (!dataDir.exists()) dataDir.mkdirs();

        // 注册服务
        registerServices();

        // 订阅事件
        ctx.kernel().order("event:register", ctx.getPluginId(), this);

        ctx.getLogger().info("启动完成。");
    }

    private void registerServices() {
        ctx.kernel().order("service:register", ctx.getPluginId(),
            "getStatus", "获取插件状态", (ServiceProvider) name -> "OK");
    }

    @Subscribe
    public void onServerTick(ServerTickEvent.Post event) {
        // 事件处理
    }
}
```

---

## 注意事项

1. **不要缓存 PluginContext 衍生值。** `getDataFolder()` 路径理论不可变，但应以每次调用为准。
2. **不要在构造器中调用耗时的 kernel.order()。** 构造器运行在插件加载线程上，会影响其他插件的加载。
3. **S 级插件能力有限。** 检查 `getTrustLevel()` 做降级处理。
4. **不要跨插件使用对方 PluginContext。** 每个插件只应使用被注入到自己的那个实例。
