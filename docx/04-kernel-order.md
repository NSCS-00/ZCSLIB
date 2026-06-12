# 04 — kernel.order() 指令全集

`ctx.kernel().order(cmd, args...)` 是插件与 ZCSLIB 内核交互的唯一通用接口。

---

## 调用语法

```java
OrderResult r = ctx.kernel().order("子系统:动作", arg0, arg1, ...);

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

## 六子系统指令全集

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

---

## 错误码表

| 错误 | 含义 |
|:---|:---|
| `"UNKNOWN_COMMAND: ..."` | 未识别的子系统前缀 |
| `"FORBIDDEN:S compute"` | S 级插件禁止 L3 计算 |
| `"FORBIDDEN:S network:send:main"` | S 级禁止主包发送 |
| `"BULKHEAD: ..."` | 断路器锁定（该插件 3 次连续失败） |
| `"Plugin X at concurrency limit"` | 插件并发上限 |
| `"SERVICE_EXISTS: ..."` | 服务名已注册（受保护命名空间） |
| `"INTERNAL: ..."` | 内核内部异常 |

---

## 命令即日志

每个 `order()` 调用自动记录审计日志：

```
[ZCSLIB] kernel.order("service:register", "energy-grid", "getStatus", ...)
→ 用时 0ms ✓
```

这意味着一行代码既是功能调用也是审计记录，无需额外写日志。
