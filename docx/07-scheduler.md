# 07 — 任务调度器

ZCSScheduler 提供三层调度能力：

| 层 | 组件 | 线程 | 用途 |
|:---|:---|:---|:---|
| L3 计算 | ComputePool | 工作线程池 | CPU 密集型离线计算 |
| Tick 同步 | SyncQueue | 主线程 | Tick 结束批量合并 |
| 保护 | Bulkhead | — | 异常断路保护 |

---

## L3 计算池 (ComputePool)

### 线程池配置

- 线程数：`CPU 核心数 × 2`
- 线程类型：守护线程 (daemon=true)
- 线程名：`ZCSLIB-L3-Compute`
- 每插件最大并发：`min(CPU 核心数, 4)`

### 用法

```java
ctx.kernel().order("scheduler:compute", ctx.getPluginId(), (Runnable) () -> {
    // ① 耗时计算（离线线程）
    PathResult result = doAStarPathfinding(world, start, end);

    // ② 回主线程更新 Minecraft 状态
    ctx.kernel().order("scheduler:queueSync", ctx.getPluginId(), (Runnable) () -> {
        player.sendSystemMessage(Component.literal(
            "路径长度: " + result.getPath().size() + " 步"
        ));
    });
});
```

### 并发限制

插件提交任务超过并发上限时，返回拒绝：

```java
OrderResult r = ctx.kernel().order("scheduler:compute", ctx.getPluginId(), heavyTask);
if (!r.isOk()) {
    ctx.getLogger().warn("计算任务被拒绝: %s", r.getError());
    // 可能错误:
    //   "Plugin X at concurrency limit" — 插件自身并发超限
    //   "BULKHEAD: Plugin X L3 compute is locked" — 断路器跳闸
}
```

### 慢任务告警

执行时间超过 50ms 的任务会触发警告日志：
```
[WARN] [ZCSLIB-L3-Compute] Compute task for my-plugin took 234ms (threshold 50ms)
```

---

## Tick 同步队列 (SyncQueue)

### 工作原理

每个 Tick 内，插件多次调用 `scheduler:queueSync` 追加任务到队列。Tick 结束时 ZCSKernel 调用 `flushSync()`，队列中所有任务按插件分组、在原调用顺序中执行。

```
Tick N 开始
  PluginA.queueSync(taskA1)
  PluginA.queueSync(taskA2)    ← 合并到 PluginA 分组
  PluginB.queueSync(taskB1)
Tick N 结束
  flushSync()
    PluginA 分组: taskA1 → taskA2  (顺序执行)
    PluginB 分组: taskB1
```

### 异常处理

单个 sync 任务抛异常时，记录错误日志，**继续执行该插件分组的后续任务以及后续插件的任务**。

---

## 断路器 (Bulkhead)

### 触发条件

同一插件的 L3 计算任务**连续失败 3 次** → 断路器跳闸，锁定 30 秒。

### 锁定期间行为

```
ctx.kernel().order("scheduler:compute", pluginId, task)
→ OrderResult.fail("BULKHEAD: Plugin 'X' L3 compute is locked")
```

### 自动恢复

锁定 30 秒后自动解除，下一次成功任务重置计数器。

---

## 完整调度流程

```
插件代码:
  ctx.kernel().order("scheduler:compute", pid, task)
    │
    ▼
ZCSKernel.order()
    │
    ▼ (trust 由 resolveTrust 确定)
ZCSScheduler.dispatch("compute", args, trust)
    │
    ├── S 级？ → fail("FORBIDDEN:S compute")
    ├── 断路器锁定？ → fail("BULKHEAD: ...")
    ├── 插件并发超限？ → fail("concurrency limit")
    │
    └── ComputePool.submit(pid, trust, task)
          │
          ├── 记录 active+1
          ├── 执行 task.run()
          ├── 成功 → recordSuccess → 重置断路器
          ├── 异常 → recordFailure → 可能触发断路器跳闸
          └── finally: active-1
```

---

## 线程安全

| 组件 | 数据结构 | 线程安全策略 |
|:---|:---|:---|
| ComputePool | `ConcurrentHashMap<String, AtomicInteger>` | CAS 原子操作 |
| SyncQueue | `LinkedHashMap` + 外部锁 | 仅在主线程调用 flush() |
| Bulkhead | `ConcurrentHashMap<String, BreakerState>` + `synchronized` | 细粒度锁 |

**注意** SyncQueue 不是线程安全的 — `enqueue()` 和 `flush()` 都应在主线程调用。
