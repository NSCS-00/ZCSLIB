# ZCSLIB — 内置自管指令平台

**ZCSLIB** 是 Minecraft NeoForge 模组的内置插件平台。模组主 JAR 加载 ZCSLIB，插件以独立 JAR 形式放在 `plugins/` 目录下，通过**字符串指令** `kernel.order("子系统:动作", args...)` 调用内核能力。

---

## 核心理念

- **字符串指令模式** — `order("service:register", ...) → OrderResult(success/fail)`，简单、可审计、无 magic getter
- **四级信任矩阵** — N / R / A / S，S 级默认全部拒绝
- **独立类加载** — PluginClassLoader 隔离每个插件，只暴露 API 包

---

## 5 分钟写出你的第一个插件

### 1. 创建 PEC.json

```json
{
  "contractSchema": "1.0",
  "pluginId": "hello-world",
  "version": "1.0.0",
  "entrypoint": "zcslib.helloworld.HelloPlugin",
  "environment": { "zcslib": ">=0.2.0" }
}
```

### 2. 包名约定

**推荐包名：`zcslib.[插件ID]`**

例如插件 ID `hello-world` → 包名 `zcslib.helloworld` → 文件路径 `src/main/java/zcslib/helloworld/HelloPlugin.java`

### 3. 写入口类

```java
package zcslib.helloworld;

import zcslib.api.PluginContext;

public class HelloPlugin {
    public HelloPlugin(PluginContext ctx) {
        ctx.getLogger().info("Hello, ZCSLIB! 我是 %s", ctx.getPluginId());
    }
}
```

### 4. 打包部署

```bash
gradle jar
cp build/libs/hello-world.jar config/DLZstudio/ZCSLIB/plugins/
```

---

## 目录结构

```
config/DLZstudio/ZCSLIB/
├── LICENSE.md
├── README.md
├── plugins/
│   └── {pluginId}/
│       ├── config/        # JSON 配置文件（用户可编辑）
│       └── data/          # NBT 持久化数据（程序内部）
├── cache/{pluginId}/      # 临时缓存
├── shared_res/            # 共享只读资源
└── global/                # 内核全局数据
```

---

## 文档索引

| 编号 | 文档 | 内容 |
|:---:|:---|:---|
| 01 | [快速上手](docx/01-quickstart.md) | 10 分钟创建第一个插件 |
| 02 | [PEC 合约](docx/02-pec.md) | 插件身份证 JSON schema |
| 03 | [PluginContext](docx/03-plugincontext.md) | 7 个方法详解 |
| 04 | [kernel.order()](docx/04-kernel-order.md) | 完整指令全集 |
| 05 | [事件总线](docx/05-event-bus.md) | @Subscribe + 跨插件通信 |
| 06 | [服务注册](docx/06-service-registry.md) | service:register/get/list |
| 07 | [调度器](docx/07-scheduler.md) | L3 计算池 + SyncQueue + Bulkhead |
| 08 | [配置与持久化](docx/08-config-pdc.md) | JSON (ConfigManager) vs NBT (PDCBackend) |
| 09 | [资源沙箱](docx/09-resource.md) | 路径穿越防护 + 磁盘配额 |
| 10 | [信任体系](docx/10-trust-system.md) | N/R/A/S 四级 + 完整信任矩阵 |

---

## 信任等级速查

| 能力 | N | R | A | S |
|:---|---|---|---|:---:|
| 事件注册 | ✅ | ✅ | ✅ | ⚠️ |
| 事件发布 | ✅ | ✅ | ✅ | ❌ |
| 服务注册 | ✅ | ✅ | ✅ | ⚠️ |
| L3 计算 | ✅ | ✅ | ✅ | ❌ |
| 配置读写 | ✅ | ✅ | ✅ | ✅ |
| NBT 持久化 | ✅ | ✅ | ✅ | ✅ |
| 文件访问 | ✅ | ✅ | ✅ | ✅ |

---

## 构建

```bash
powershell -File .plasma/buildid/build.ps1
```

产物命名：`ZCSLIB-{version}-BUILD.{buildid}_windows_amd64.jar`

---

## 版本历史

### v0.2.0 — 平台骨架
- 28 个 .java 源文件
- 6 个子系统全部接线
- ZCSLIB-TestPlugin 集成测试通过
- PluginClassLoader 隔离加载
- PEC 扫描/校验/版本比较
- ComputePool + SyncQueue + Bulkhead
- ConfigManager 原子写入
- PDCBackend NBT 持久化
