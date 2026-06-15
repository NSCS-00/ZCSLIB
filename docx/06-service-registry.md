# 06 — 服务注册中心

ServiceRegistry 是插件间通信的基础设施。一个插件注册服务，另一个插件发现并调用。

---

## 概念

服务 = 命名 + 描述 + 函数，本质是一个有名字的 lambda：

```
插件A注册: "getEnergyGrid" → (args) → EnergyGrid对象
插件B查询: "getEnergyGrid" → 拿到函数 → 调用 → 得到EnergyGrid对象
```

---

## 注册服务

```java
ctx.order("service:register",
    ctx.getPluginId(),         // 归属插件
    "getEnergyGrid",          // 服务名
    "获取当前世界的能源网格实例", // 描述（可选）
    (ServiceProvider) name -> energyGridInstance  // 提供者函数
);
```

**重复注册：** 同一 `pluginId` + `name` 会覆盖旧值。

---

## 获取服务

```java
OrderResult r = ctx.order("service:get",
    ctx.getPluginId(), "getEnergyGrid");

if (r.isOk()) {
    @SuppressWarnings("unchecked")
    ServiceEntry entry = (ServiceEntry) r.getData();
    Object result = entry.invoke("getEnergyGrid");
    EnergyGrid grid = (EnergyGrid) result;

    ctx.getLogger().info("电网状态: %.2f / %.2f",
        grid.getCurrentPower(), grid.getMaxPower());
}
```

---

## 获取元数据

不调用服务，只查元信息：

```java
OrderResult r = ctx.order("service:get:meta",
    ctx.getPluginId(), "getEnergyGrid");
// r.getData() → Map { "name":..., "description":..., "registeredBy":... }
```

---

## 列出所有服务

```java
OrderResult r = ctx.order("service:list");
// r.getData() → List<String> 所有已注册服务名
// 格式: "pluginId:serviceName"
// 例如: ["energy-grid:getStatus", "energy-grid:getGrid", "network-manager:getChannels"]
```

---

## S 级限制 — 受保护命名空间

S 级插件不能注册以下前缀的服务名：

| 受保护前缀 | 说明 |
|:---|:---|
| `zcslib.kernel.*` | 内核服务 |
| `zcslib.event.*` | 事件总线服务 |
| `zcslib.service.*` | 服务注册中心自己 |
| `zcslib.scheduler.*` | 调度器内部服务 |
| `zcslib.resource.*` | 资源管理器服务 |
| `zcslib.config.*` | 配置管理器服务 |

S 级可以注册以自己 pluginId 开头的服务名，如 `my-plugin:status`。

---

## 三种跨插件通信模式

| 模式 | 方式 | 场景 |
|:---|:---|:---|
| **服务调用** | `service:get` → 调用函数 | 需要即时结果的请求-响应 |
| **事件发布** | `event:post` → 广播 | 一对多通知、状态变更 |
| **共享数据** | `pdc:load` / `resource:file` | 大量数据、持久化状态 |

服务调用适合"给个结果"，事件适合"通知一下"。不要用高频事件替代服务调用。

---

## 内部存储

```java
// 单例
public class ServiceRegistry {
    private static final ConcurrentHashMap<String, ServiceEntry> services = new ConcurrentHashMap<>();
    // key = "pluginId:serviceName"
}
```

`ConcurrentHashMap` 保证线程安全，支持并发读写。无外部锁。
