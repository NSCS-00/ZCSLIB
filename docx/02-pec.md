# 02 — PEC 插件执行合约

PEC = Plugin Execution Contract。一个 JSON 文件，告诉 ZCSLIB "我是谁、我需要什么环境、从哪里启动我"。

---

## 包名约定

PEC 的 `entrypoint` 字段指向的类，其 Java **包名推荐**为 `zcslib.[插件ID]`。

例如插件 ID 为 `energy-grid`：
- 包名：`zcslib.energygrid`
- 入口类全限定名：`zcslib.energygrid.EnergyGridPlugin`
- 对应文件路径：`src/main/java/zcslib/energygrid/EnergyGridPlugin.java`

这保证：
- 不会出现多层嵌套（如 `com.example.energy.grid.core.plugin`）
- 一眼能看出插件归属（`zcslib.` 前缀即 ZCSLIB 生态）
- 简洁且不与传统 Java 包名冲突

---

## PEC 扫描优先级

插件 JAR 加载时按以下顺序查找 PEC（第一个匹配返回）：

| 优先级 | 路径 |
|:---:|:---|
| 1 | `META-INF/zcslib/PEC.json` |
| 2 | `zcslib.plugin.json`（JAR 根目录）|
| 3 | `zcslib.PEC.json`（JAR 根目录）|
| 4 | `PEC.json`（JAR 根目录）|

推荐使用 `META-INF/zcslib/PEC.json`。

---

## JSON Schema

### 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|:---|:---|:---:|:---|
| `contractSchema` | string | ✅ | 固定 `"1.0"` |
| `pluginId` | string | ✅ | 全局唯一标识，小写+连字符，如 `"my-plugin"` |
| `version` | string | ✅ | 语义版本，如 `"1.0.0"` |
| `displayName` | string | 否 | 人类可读名称 |
| `authors` | string[] | 否 | 作者列表 |
| `entrypoint` | string | ✅ | 插件入口类全限定名 |
| `priority` | int | 否 | 加载优先级，越大越先加载，默认 100 |
| `environment` | object | ✅ | 环境要求 |
| `onEnvironmentNotMet` | string | 否 | 环境不满足时的行为：`"skip"` / `"warn"` / `"refuse"`，默认 `"warn"` |
| `fallbackLabel` | string | 否 | 降级标签 |
| `features` | string[] | 否 | 声明的特性列表，用于兼容性检查 |

### entrypoint 规范

- 类的全限定名，如 `"zcslib.myplugin.MyPlugin"`
- 必须有一个接收 `PluginContext` 参数的构造器
- 若无此构造器，回退到无参构造器
- 类必须可被反射实例化（public、非抽象）

### environment 字段

```json
{
  "environment": {
    "zcslib": ">=0.2.0",
    "platform": {
      "minecraft": ">=1.21.1",
      "loader": "neoforge",
      "loaderVersionRange": ">=21.1",
      "javaVersionRange": ">=21"
    }
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|:---|:---|:---:|:---|
| `zcslib` | string | ✅ | ZCSLIB 版本范围，如 `">=0.2.0 <1.0.0"` |
| `platform.minecraft` | string | 否 | MC 版本范围 |
| `platform.loader` | string | 否 | 模组加载器，如 `"neoforge"` |
| `platform.loaderVersionRange` | string | 否 | 加载器版本范围 |
| `platform.javaVersionRange` | string | 否 | Java 版本范围 |

### 版本范围语法

| 表达式 | 含义 |
|:---|:---|
| `">=1.21"` | 1.21 及以上所有版本 |
| `">=1.21.1 <1.22"` | 1.21.1 ~ 1.22（不含）|
| `"1.21.1"` | 精确匹配 |
| `">=1.21.1"` | 1.21.1+ |

版本比较采用**语义版本**算法：`1.21` 与 `1.21.0` 视为相同。

---

## 信任判定流程

```
PEC 存在 AND pluginId 已设置
  → 包含 zcslib 包名 → N (Trusted)
  → 不包含 zcslib 包名 → R (Recognized)

PEC 不存在
  → 有 neoforge.mods.toml → A (Auto-Adapt)
  → 有 META-INF/zcslib/auto-adapt → A
  → 否则 → S (Suspicious，拒绝加载)
```

---

## 完整示例

```json
{
  "contractSchema": "1.0",
  "pluginId": "energy-grid",
  "version": "0.1.0",
  "displayName": "能源网格",
  "authors": ["PlasmaStudio"],
  "entrypoint": "zcslib.energygrid.EnergyGridPlugin",
  "priority": 200,
  "environment": {
    "zcslib": ">=0.2.0",
    "platform": {
      "minecraft": ">=1.21.1",
      "loader": "neoforge",
      "loaderVersionRange": ">=21.1",
      "javaVersionRange": ">=21"
    }
  },
  "features": ["energy", "grid", "network"]
}
```
