# 04 — kernel.order() 指令全集

`ctx.order(cmd, args...)` 是插件与 ZCSLIB 内核交互的唯一通用接口。

---

## 调用语法

```java
OrderResult r = ctx.order("子系统:动作", arg0, arg1, ...);

if (r.isOk()) {
    // r.getData() → 返回值（可能为 null）
} else {
    // r.getError() → 错误消息
}
```

---

## OrderResult

```java
public class OrderResult {
    boolean isOk();
    String  getError();
    Object  getData();
    String  getSource();   // 处理的子系统
    int     getTimeMs();   // 处理耗时

    static OrderResult success(Object data);
    static OrderResult fail(String error);
}
```

---

## 九子系统指令全集

### event: — 事件子系统

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `event:register` | pluginId, listener | null | ⚠️ 系统事件被过滤 |
| `event:post` | event | post 结果 | ❌ 仅系统事件 |
| `event:unregister` | pluginId, listener | null | ✅ |

### service: — 服务注册子系统

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `service:register` | pluginId, name, desc, provider | null | ⚠️ 受保护命名空间禁止 |
| `service:get` | pluginId, name | ServiceEntry | ✅ |
| `service:get:meta` | pluginId, name | metadata | ✅ |
| `service:list` | — | `List<String>` | ✅ |

### scheduler: — 调度子系统

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `scheduler:compute` | pluginId, Runnable | null | ❌ `"FORBIDDEN:S compute"` |
| `scheduler:queueSync` | pluginId, Runnable | null | ✅ |
| `scheduler:io` | pluginId, Runnable | null | ✅ |

### config: — 配置子系统

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `config:load` | pluginId, filename | `Map<String,Object>` / null | ✅ |
| `config:save` | pluginId, filename, data | null | ✅ |
| `config:reload` | pluginId, filename | null | ✅ |
| `config:get` | pluginId, key | 值 | ✅ |

### pdc: — 持久化子系统

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `pdc:save` | pluginId, key, CompoundTag | null | ✅ |
| `pdc:load` | pluginId, key | CompoundTag（空则 isEmpty）| ✅ |
| `pdc:delete` | pluginId, key | null | ✅ |
| `pdc:keys` | pluginId | `List<String>` | ✅ |

### resource: — 资源子系统

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `resource:file` | pluginId, virtualPath | File | ✅ |
| `resource:exists` | pluginId, virtualPath | boolean | ✅ |

### network: — 网络子系统（M4 新增）

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `network:send:standard` | pluginId, data | null / 序列号 | ✅ |
| `network:send:main` | pluginId, data | null / 序列号 | ❌ `"FORBIDDEN:S network:send:main"` |
| `network:offline` | pluginId, data | null / 已入队 | ✅ |
| `network:replay` | pluginId | null / 已重放 | ✅ |
| `network:health` | — | AggregatorHealth 状态 | ✅ |

**网络拓扑：** 插件 → MainPacketAssembler (tick 缓冲) → ZCSNetwork → ZCnet Zero-Core-Server。

S 级插件禁止 `send:main`（主包发送），但允许 `send:standard`（标准包）和 `offline`（离线队列）。

### audit: — 审计子系统（M5 新增）

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `audit:log` | pluginId, category, detail, level | null | ✅ |
| `audit:cross` | callerPluginId, calleePluginId, detail | null | ✅ |

审计日志按 N/R/A/S 信任等级分目录写入，文件名格式 `{pluginId}_{yyyy-MM-dd}.log`。跨信任等级调用自动记录。

### memory: — 记忆子系统（M5 新增）

| 指令 | 参数 | 返回 | S级 |
|:---|:---|:---|:---:|
| `memory:l4-check` | pluginId, methodName | Verdict (ALLOW/STUB/BLOCK/MONITOR) | ✅ |
| `memory:l3-query` | pluginId, methodName, pattern | `List<L3Rule>` | ✅ |
| `memory:l3-load` | pluginId | null | ✅ |
| `memory:l1-freeze` | pluginId | L1Snapshot | ✅ |
| `memory:l2-stats` | pluginId | L2 统计 Map | ✅ |
| `memory:decide` | pluginId, methodName | Verdict (Ruling + 压缩形态) | ✅ |

记忆子系统是自演化体系（Phase 12）的运行时接口。L1-L4 四阶记忆的详细说明见《ZCSLIB 自演化包设计》。

---

## 错误码表

| 错误 | 含义 |
|:---|:---|
| `"UNKNOWN_COMMAND: ..."` | 未识别的子系统前缀 |
| `"FORBIDDEN:S compute"` | S 级插件禁止 L3 计算 |
| `"FORBIDDEN:S network:send:main"` | S 级禁止主包发送 |
| `"FORBIDDEN:S event:post"` | S 级禁止事件发布 |
| `"BULKHEAD: ..."` | 断路器锁定（该插件 3 次连续失败） |
| `"Plugin X at concurrency limit"` | 插件并发上限 |
| `"SERVICE_EXISTS: ..."` | 服务名已注册（受保护命名空间） |
| `"INTERNAL: ..."` | 内核内部异常 |
| `"OFFLINE_QUEUE_FULL: ..."` | 离线队列已满（50MB 硬顶） |
| `"AGGREGATOR_UNREACHABLE: ..."` | 聚合器不可达 |

---

## 命令即日志

每个 `order()` 调用自动记录审计日志：

```
[ZCSLIB] kernel.order("service:register", "energy-grid", "getStatus", ...)
→ 用时 0ms ✓
```

这意味着一行代码既是功能调用也是审计记录，无需额外写日志。
