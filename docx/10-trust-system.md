# 10 — 信任体系

信任体系是 ZCSLIB 安全模型的核心。每个插件被分配一个信任等级，决定它能使用哪些内核功能。

---

## 四级信任 (N/R/A/S)

| 等级 | 缩写 | 判定条件 | 示例 |
|:---|:---|:---|:---|
| **Native** | N | pluginId 已注册，PEC 中的类在 `dlzstudio` 包内 | 官方能源网格、官方网络管理器 |
| **Recognized** | R | pluginId 已注册，PEC 中的类在非 dlzstudio 包内 | 白名单第三方插件 |
| **Auto-Adapt** | A | pluginId 有 PEC 的类但不在白名单，或未注册但有自动适配信息 | 未知但格式标准的插件 |
| **Suspicious** | S | 无法验证身份 | 无 PEC 或异常插件 |

---

## 信任矩阵

### 信任矩阵总览

| 能力 | N | R | A | S |
|:---|---|---|---|:---:|
| event:register (过滤) | ✅ | ✅ | ✅ | ⚠️ 系统事件被过滤 |
| event:post | ✅ | ✅ | ✅ | ❌ |
| event:unregister | ✅ | ✅ | ✅ | ✅ |
| service:register | ✅ | ✅ | ✅ | ⚠️ 受保护命名空间禁止 |
| service:get | ✅ | ✅ | ✅ | ✅ |
| service:get:meta | ✅ | ✅ | ✅ | ✅ |
| service:list | ✅ | ✅ | ✅ | ✅ |
| scheduler:compute | ✅ | ✅ | ✅ | ❌ |
| scheduler:queueSync | ✅ | ✅ | ✅ | ✅ |
| scheduler:io | ✅ | ✅ | ✅ | ✅ |
| config:load / save / reload | ✅ | ✅ | ✅ | ✅ |
| pdc:save / load / delete | ✅ | ✅ | ✅ | ✅ |
| resource:file | ✅ | ✅ | ✅ | ✅ |
| network:send:main | ✅ | ✅ | ✅ | ❌ |

### 设计哲学

**"S 级默认全部拒绝，只放行明确无危害的能力"**

S 级可以：
- 读写自己的配置和数据（config/pdc/resource）
- 订阅非系统事件（event:register）
- 在主线程执行低风险操作（scheduler:queueSync）

S 级不可以：
- 在非主线程执行代码（scheduler:compute）— 防 CPU 挖矿
- 发布事件（event:post）— 防污染其他插件
- 注册受保护服务名（service:register）— 防伪装
- 联网发包（network:send:main）— 防数据外泄

---

## 信任判定流程

```
启动时 ZCSKernel 加载插件 JAR
  ↓
PECScanner 扫描 JAR 中的 PEC.json
  ↓ 找到
PECValidator.parse(pecContent)
  ↓
检查 pluginId:
  ├─ 包含 "dlzstudio" → N
  └─ 不包含 → R
  ↓
PECValidator.validate(schema, environment)
  ↓
  ├─ 版本要求满足 → 使用 PEC 中的 entrypoint
  ├─ 版本不满足，onEnvironmentNotMet="refuse" → 拒绝加载，无信任等级
  └─ 版本不满足，onEnvironmentNotMet="warn" → 加载但降级为 A

PEC.json 未找到:
  ├─ 有 neoforge.mods.toml → A (Auto-Adapt)
  └─ 无任何元信息 → S (拒绝加载)
```

---

## ZCSKernel dispatch 中的 Trust 传递

每次 `kernel.order()` 调用，内核按以下流程分发：

```java
public OrderResult order(String cmd, Object... args) {
    TrustLevel trust = resolveTrust(cmd, args);  // ← 从 args[0] (pluginId) 查找
    String prefix = extractPrefix(cmd);          // ← "event:" / "service:" ...

    return switch (prefix) {
        case "event:"     -> eventDispatch(cmd, args, trust);
        case "service:"   -> serviceDispatch(cmd, args, trust);
        case "scheduler:" -> schedulerDispatch(cmd, args, trust);
        case "config:"    -> configDispatch(cmd, args, trust);
        case "pdc:"       -> pdcDispatch(cmd, args, trust);
        case "resource:"  -> resourceDispatch(cmd, args, trust);
        default           -> OrderResult.fail("UNKNOWN_COMMAND: " + prefix);
    };
}
```

`resolveTrust()` 从已加载的 `PluginDescriptor` 映射中查找插件 ID → TrustLevel。

**注意事项：**
1. 信任等级在加载时确定，**运行时不可提升**
2. 如果一个 N 级插件帮助 S 级插件调用 forbidden 指令，**由 N 级插件承担责任**（审计日志记录的是 caller PID）
3. 未加载的 pluginId 调用 order() → 返回未知命令错误

---

## 审计日志

每次 `kernel.order()` 自动记录：

```
[ZCSLIB-AUDIT] [N] energy-grid → scheduler:compute(pathfinding) 用时:234ms ✓
[ZCSLIB-AUDIT] [S] unknown-plugin → scheduler:compute → ❌ FORBIDDEN:S compute
```

可通过 `config/DLZstudio/ZCSLIB/logs/audit.log` 查看完整审计记录。
