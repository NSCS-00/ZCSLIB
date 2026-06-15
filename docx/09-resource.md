# 09 — 资源沙箱

ZCSResourceManager 管理插件文件访问，通过路径沙箱隔离保证一个插件不能读写另一个插件的文件。

---

## 目录结构

```
config/DLZstudio/ZCSLIB/
├── global/                      # 内核全局数据
│   └── PDC/                     # PDCBackend 存放处
├── shared_res/                  # 共享只读资源
├── cache/                       # 全局缓存
│   └── {pluginId}/              # 每插件子目录
├── memory/                      # 🆕 全局记忆（L1/L4/训练集）
│   ├── l1/                      # L1 崩溃快照
│   ├── l4/                      # L4 本能模式库
│   └── training/                # 联邦训练集
├── plugins/
│   └── {pluginId}/
│       ├── config/              # 配置目录
│       ├── data/                # 数据目录
│       └── memory/              # 🆕 插件私有记忆
│           ├── l2/              # L2 短期日志
│           └── l3/              # L3 长期作战手册
```

---

## PluginContext 暴露的目录

```java
ctx.getDataFolder()    // → plugins/{id}/data/
ctx.getConfigFolder()  // → plugins/{id}/config/
ctx.getCacheDir()      // → cache/{id}/
```

---

## 路径沙箱 (ResourceSandbox)

每条 `resource:file` 调用都经过沙箱验证：

```java
OrderResult r = ctx.order("resource:file", ctx.getPluginId(), "data/saves/world.dat");
// OK: → plugins/my-plugin/data/saves/world.dat
```

### 防护机制

1. **路径穿越拦截**
   ```
   "../../saves/world" → SANDBOX: Path escape denied
   ```

2. **敏感文件黑名单**
   ```
   "server.properties" → SANDBOX: Sensitive path denied
   "ops.json"          → SANDBOX: Sensitive path denied
   "eula.txt"          → SANDBOX: Sensitive path denied
   ```

被拦截的路径：
```
saves, world, server.properties, eula.txt,
ops.json, banned-ips.json, banned-players.json,
whitelist.json, usercache.json
```

3. **路径规范化**
   - 反斜杠统一转正斜杠：`data\\saves` → `data/saves`
   - 去除前导 `/`：`/config/settings.json` → `config/settings.json`
   - 绝对路径归一化：`.resolve().normalize()`

### 内部实现

```java
public File resolve(String virtualPath) {
    String clean = virtualPath.replace('\\', '/');
    while (clean.startsWith("/")) clean = clean.substring(1);

    Path resolved = root.resolve(clean).toAbsolutePath().normalize();

    // 检查是否在根目录下
    if (!resolved.startsWith(root)) throw new SecurityException("Path escape");

    // 检查是否命中敏感路径
    for (String sensitive : SENSITIVE_DIRS) {
        if (resolved.toString().contains(sensitive)) throw new SecurityException("Sensitive path");
    }

    return resolved.toFile();
}
```

---

## 磁盘配额 (DiskQuota)

| 信任等级 | 配额 | 检查方式 |
|:---|:---|:---|
| N / R / A | 2048 MB (2 GB) | 目录实际大小 |
| S | 500 MB | 目录实际大小 |

---

## 两种获取文件的方式

### 方式一：PluginContext 直接读（推荐）

```java
File dataDir = ctx.getDataFolder();
File myFile = new File(dataDir, "cache.bin");
// 不经过沙箱验证 — 已在沙箱内
```

### 方式二：kernel.order("resource:file")

```java
OrderResult r = ctx.order("resource:file", ctx.getPluginId(), "data/cache.bin");
File myFile = (File) r.getData();
// 经过沙箱验证 — 双重确认
```

**推荐方式一。** 方式二主要用于未来跨插件文件访问。

---

## 当前与未来

| 功能 | v0.2.0-M5 状态 |
|:---|:---|
| 插件自身数据目录 | ✅ 自动创建 |
| 插件私有记忆 (L2/L3) | ✅ `plugins/{id}/memory/` |
| 路径穿越拦截 | ✅ SecurityException |
| 敏感文件黑名单 | ✅ 9 个系统路径 |
| 磁盘配额检查 | ✅ 可查询 |
| 全局记忆目录 (L1/L4) | ✅ `memory/l1/` `memory/l4/` |
| 联邦训练集目录 | ✅ `memory/training/` |
| shared_res 共享资源 | ✅ 目录已创建 |
