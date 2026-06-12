# 05 — 事件总线

ZCSLEventBus 是 ZCSLIB 的内部事件系统，独立于 NeoForge 事件总线。用于插件间通信。

---

## 设计原则

- **独立总线。** 不与 NeoForge 的 `IEventBus` 混用。ZCSLIB 插件订阅 ZCSLB 事件，NeoForge 模组订阅 NeoForge 事件。
- **@Subscribe 注解。** 与 NeoForge 用法一致，扫描 public 方法上的 `@Subscribe`。
- **S 级保护。** S 级插件不能订阅系统事件，不能发布系统事件。

---

## @Subscribe 注解

```java
import zcslib.event.Subscribe;
import zcslib.event.EventPriority;

public class MyPlugin {

    @Subscribe(priority = EventPriority.NORMAL)
    public void onEnergyChange(EnergyChangeEvent event) {
        if (event.isCancelable() && event.isCancelled()) return;
        // 处理能量变化
    }
}
```

### EventPriority

| 优先级 | 常量 | 执行顺序 |
|:---|:---|:---|
| 最高 | `HIGHEST` | 最先执行 |
| 高 | `HIGH` | |
| 普通 | `NORMAL`（默认）| |
| 低 | `LOW` | |
| 最低 | `LOWEST` | 最后执行 |

### Cancelable 事件

实现 `Cancelable` 接口的事件可被取消：

```java
public class MyEvent implements Cancelable {
    private boolean cancelled = false;

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
```

高优先级监听器取消事件后，低优先级监听器（默认不忽略已取消事件）可以检查 `isCancelled()`。

`@Subscribe(ignoreCancelled = true)` 可跳过已取消的事件。

---

## 注册 / 发布 / 注销

### 注册

```java
ctx.kernel().order("event:register", ctx.getPluginId(), this);
// ZCSLEventBus 自动扫描 this 上的所有 @Subscribe 方法
```

### 发布

```java
ctx.kernel().order("event:post", new EnergyChangeEvent(5000, 3000));
```

### 注销

```java
ctx.kernel().order("event:unregister", ctx.getPluginId(), this);
```

---

## 系统事件黑名单（S 级过滤）

S 级插件调用 `event:register` 时，以下命名空间的方法会被静默跳过：

| 黑名单 | 说明 |
|:---|:---|
| `zcslib.kernel.*` | 内核内部事件 |
| `zcslib.event.ZCSLEventBus.*` | 事件总线元事件 |
| `zcslib.service.*` | 服务生命周期事件 |
| `net.neoforged.fml.*` | NeoForge 框架事件 |
| `net.neoforged.neoforge.event.server.*` | 服务端生命周期事件 |

---

## 线程模型

- `event:register` / `event:unregister` — 可在任意线程调用（内部同步）
- `event:post` — 在调用线程同步执行所有监听器
- 所有监听器在事件发布的线程上运行，**不切线程**

---

## 内部实现

### HandlerEntry

```java
record HandlerEntry(
    String pluginId,        // 归属插件
    Object target,          // 监听器实例
    Method method,          // @Subscribe 方法
    EventPriority priority,
    boolean ignoreCancelled
)
```

### 存储结构

```java
// 事件类 → 处理器列表（按优先级排序）
Map<Class<?>, List<HandlerEntry>> handlers = new ConcurrentHashMap<>();

// 插件 → 已注册的监听器列表（用于注销时批量清理）
Map<String, List<Object>> pluginListeners = new ConcurrentHashMap<>();
```

---

## 异常处理策略

单个事件处理器的异常不会阻止其他处理器执行：

```
处理器顺序: H1 → H2 → H3
H2 抛异常 → 记录日志 → 继续 H3
```

这保证了有问题的插件不会搞死整个事件系统。
