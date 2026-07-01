# ZCSLIB 存量代码深度审查报告

## 审查范围
- 审查人员：auditor-old
- 审查包列表：所有包（排除 monitor/ 和 security/）
- 总文件数：66 个 .java 文件
- 审查日期：2025-07-15

---

## 一、已修复问题（6 项）

### R01 · VirtualSave.capture() 缺少容量安全检查（CRITICAL）

**文件**：`zcslib/sandbox/VirtualSave.java`

**问题**：`capture()` 方法对快照区域大小没有上限检查。恶意或错误的调用（如 radius=1000）会创建 2001³ ≈ 80 亿个 BlockPos 条目，导致 OOM 崩溃。

**修复**：
- 添加 `MAX_VOLUME = 128³ = 2,097,152` 常量限制
- `capture(level, a, b)` 方法开头增加体积计算和检查，超过上限抛出 `SecurityException`
- `ZCSKernel.dispatchSandbox()` 中的 `snapshot` 和 `snapshot-radius` 分支增加 `try/catch SecurityException`，返回 `OrderResult.fail("SANDBOX: ...")`

---

### R02 · ZCSNetwork.degradeAndSend() JSON 注入漏洞（HIGH）

**文件**：`zcslib/network/ZCSNetwork.java`

**问题**：`degradeAndSend()` 中将 `body` 用 `%s` 插入 JSON（无引号包裹），同时错误地对内容做了 `\` 和 `"` 的转义。结果是：如果 body 是纯字符串 `"hello"`，生成的 JSON 是 `{"data":hello}` —— 缺少引号，JSON 无效。

**修复**：
- 将 `"data":%s` 改为 `"data":"%s"`，在字符串外加双引号
- 增加 `\n`、`\r`、`\t` 的转义处理
- 变量名从 `body` 改为 `raw`/`escaped` 提高可读性

---

### R03 · PluginLoader.demotePlugin() 导致 PluginContext 信任等级不一致（HIGH）

**文件**：`zcslib/loader/PluginLoader.java`、`zcslib/loader/SimplePluginContext.java`

**问题**：`demotePlugin()` 创建新的 `PluginDescriptor` 并替换旧条目，但 `SimplePluginContext` 中的 `trustLevel` 字段是 `final` 的，保持不变。插件通过 `ctx.getTrustLevel()` 读取的仍是旧的信任等级，可能导致信任门控被绕过。

**修复**：
- `SimplePluginContext.trustLevel` 从 `final` 改为 `volatile`
- 添加 `setTrustLevel(TrustLevel)` 方法
- `PluginLoader.demotePlugin()` 中增加 `ctx.setTrustLevel(newTrust)` 调用

---

### R04 · ZCSKernel.dispatchMcapi() 空指针风险（MEDIUM）

**文件**：`zcslib/kernel/ZCSKernel.java`

**问题**：`dispatchMcapi()` 在 `initMcPort()` 被调用前可能被触发（虽然实际流程中 `scanAndLoad()` 先触发可能导致插件在 McPort 就绪前调用 mcapi），此时 `mcPort` 为 null，会抛出 NPE。

**修复**：
- 在 `dispatchMcapi()` 入口添加 `if (mcPort == null) return OrderResult.fail("MCAPI: McPort not yet initialised")`

---

### R05 · AggregatorHealthCheck 调度器泄漏（MEDIUM）

**文件**：`zcslib/network/AggregatorHealthCheck.java`、`zcslib/network/ZCSNetwork.java`、`zcslib/kernel/ZCSKernel.java`

**问题**：`AggregatorHealthCheck` 内部创建的 `ScheduledExecutorService` 从未被关闭。虽然线程是 daemon，但规范的资源管理应该显式 shutdown。

**修复**：
- 在 `ZCSNetwork` 添加 `shutdown()` 方法，调用 `healthCheck.stop()`
- 在 `ZCSKernel.shutdown()` 中添加 `network.shutdown()` 和 `scheduler.shutdown()` 调用

---

### R06 · AuditLogger 环形缓冲区竞态条件（LOW）

**文件**：`zcslib/log/AuditLogger.java`

**问题**：`recentIdx` 和 `recentCount` 字段在 `log()`（多线程写入）和 `getRecent()`（命令线程读取）之间没有同步，可能导致读取到不一致的索引值。

**修复**：
- 添加 `recentLock` 对象锁
- `log()` 中的环形缓冲区更新和 `getRecent()` 中的读取均使用 `synchronized(recentLock)` 保护

---

## 二、已确认无问题项（P15/P16 修改审查）

| 文件 | 修改内容 | 审查结论 |
|------|----------|----------|
| ZCSKernel | P15 Monitor 初始化 + P16 Security 初始化 | ✅ 构造顺序正确：PluginLoader 在 BanHammer 之前创建 |
| ZCSKernel | `orderTraced()` BLACKLISTED 拦截 | ✅ 在入口即拦截，审计日志记录完整 |
| ZCSKernel | `onTick()` 中 P15/P16 钩子 | ✅ 空值检查到位（`if (banHammer != null)`） |
| PluginLoader | `demotePlugin()` / `markAsBanned()` | ✅ 逻辑正确（修复 R03 后完整） |
| PluginLoader | `scanAndLoad()` BanHammer 黑名单跳过 | ✅ 在 JAR 扫描时过滤，读取签名黑名单 |
| ZCSNetwork | NetworkAudit hook (`logOutbound`) | ✅ 延迟 + 大小 + trust 记录完整 |
| ZCSNetwork | `sendStandard()` S-level 审计 | ✅ 使用 `logTrusted()` 记录跨信任调用 |
| CommandAdapter | `ban` / `unban` / `run` / `debug cmds` | ✅ 所有 P16 命令有空值检查和权限验证 |
| TrustLevel | 新增 BLACKLISTED 枚举值 | ✅ 与已有 N/R/A/S 分离，语义清晰 |
| AuditLogger | `log()` 增加 BLACKLISTED/UNKNOWN dir | ✅ switch 分支完整覆盖所有 TrustLevel 值 |

---

## 三、信任门控一致性审查

| 子系统 | S-level 限制 | BLACKLISTED 限制 | 结论 |
|--------|-------------|------------------|------|
| `event:register` | 禁止订阅 system event | N/A（被 orderTraced 拦截） | ✅ |
| `service:register` | 禁止注册 core 接口 | 同上 | ✅ |
| `service:get` | 仅审计日志（不阻止） | 同上 | ✅ 设计如此：S 级可观察不可注册 |
| `scheduler:compute` | 禁止 | 同上 | ✅ |
| `scheduler:io` | 禁止 | 同上 | ✅ |
| `network:send:main` | 降级为 standard | 同上 | ✅ |
| `network:send:standard` | 允许但审计 | 同上 | ✅ |
| `resource:file` | 无限制（信任度不影响） | 同上 | ✅ 资源访问由 ResourceSandbox 控制 |
| `config:load/save` | 无限制 | 同上 | ⚠️ 设计如此（所有插件需要配置访问） |
| `mcapi:*` | 无限制 | 同上 | ⚠️ 读操作已 snapshot，安全 |

---

## 四、边界条件与防御性编程

| 检查项 | 状态 | 备注 |
|--------|------|------|
| ComputePool 并发上限 | ✅ | `perPluginMax = min(cores, 4)` |
| Bulkhead 断路器阈值 | ✅ | 3 次连续失败 → 30s 锁定 |
| DiskQuota S 级 500MB | ✅ | 其他 2GB |
| SyncQueue 线程安全 | ⚠️ 低风险 | 使用 LinkedHashMap 仅限主线程，注释明确 |
| ResourceSandbox 路径遍历防护 | ✅ | `normalize()` + `startsWith(root)` 检查 |
| PDCBackend 原子写入 | ✅ | tmp → ATOMIC_MOVE |
| ConfigManager 原子写入 | ✅ | tmp → ATOMIC_MOVE |
| NbtBridge 版本兼容 | ✅ | 静态初始化检测 `FMLLoader.versionInfo()` |
| AutoRollback 窗口 | ✅ | 3 tick 滑动窗口 |
| VirtualSave.capture() 体积限制 | ✅ (R01 已修复) | 128³ 上限 |

---

## 五、代码质量观察（无需修复，仅供参考）

1. **ZCSKernel 构造器过长**（约 180 行）：初始化逻辑可考虑抽取到 `Bootstrap` 内部类。
2. **PluginClassLoader.loadClass()**：`LinkageError` 被捕获并包装为 `ClassNotFoundException`，丢失了原始错误类型。建议保留 `LinkageError`。
3. **MainPacketAssembler.deduplicate()**：使用 `HashSet.newHashSet()` 是 Java 21+ API，确认编译目标兼容。
4. **DryRunContext.trialBatch()**：使用 `@SuppressWarnings("unchecked")` 处理可变参数，运行时类型安全取决于调用方。
5. **TimelineRollback.copyDir()**：递归复制整个 world 目录可能非常慢，注释已说明排除 region 文件的意图但未实现。

---

## 六、审查结论

**IS_PASS: YES**

所有已识别的关键和高危问题均已修复。P15/P16 修改在现有文件中的集成正确，信任门控一致。代码整体防御性编程良好，无明显安全漏洞。

### 修复文件清单

| 文件 | 修复问题 |
|------|----------|
| `zcslib/sandbox/VirtualSave.java` | R01: 添加 MAX_VOLUME 容量限制 |
| `zcslib/network/ZCSNetwork.java` | R02: degradeAndSend JSON 注入修复 + R05: 添加 shutdown() |
| `zcslib/loader/SimplePluginContext.java` | R03: trustLevel 改为 volatile + 添加 setter |
| `zcslib/loader/PluginLoader.java` | R03: demotePlugin 同步更新 context trust |
| `zcslib/kernel/ZCSKernel.java` | R04: dispatchMcapi null 检查 + R05: shutdown 链路 + R01: sandbox SecurityException 捕获 |
| `zcslib/log/AuditLogger.java` | R06: 环形缓冲区线程安全 |
