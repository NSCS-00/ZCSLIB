# 01 — 快速上手

读完这份文档，你应该能在 10 分钟内写出你的第一个 ZCSLIB 插件。

---

## 包名约定

**所有 ZCSLIB 插件推荐使用 `zcslib.[插件ID]` 作为 Java 包名。**

例如：
- 插件 ID 为 `energy-grid` → 包名 `zcslib.energygrid`
- 插件 ID 为 `my-addon` → 包名 `zcslib.myaddon`

原因：
1. **单段从属** — 不会出现 `com.example.mod.submod.deepsub` 这种无限嵌套
2. **归属明确** — `zcslib.` 前缀表明这是 ZCSLIB 生态插件
3. **命名简洁** — 两段就够，不与传统 Java 包名格式冲突

---

## 前置知识

- 会用 Gradle 打包 JAR
- 了解 Minecraft NeoForge 模组的基本概念
- 了解 JSON

不需要了解 ZCSLIB 内部实现。

## 支持的版本

| NeoForge | Minecraft | 状态 |
|----------|-----------|------|
| 21.1.x | 1.21.1 | ✅ 编译 & 运行 |
| 26.1.x | 1.26.1 | ✅ 零修改运行 |

> 插件只需编译一份（21.1），即可同时运行于 21.1 和 26.1。

---

## 第一步：创建项目

```
my-plugin/
├── build.gradle
├── src/main/java/zcslib/myplugin/
│   └── MyPlugin.java
└── src/main/resources/
    └── PEC.json
```

**build.gradle 最小配置：**

```groovy
plugins {
    id 'java'
}

group = 'zcslib.myplugin'
version = '1.0.0'

repositories {
    mavenCentral()
}

dependencies {
    compileOnly files('path/to/ZCSLIB-0.2.0.jar')
}

jar {
    archiveFileName = 'my-plugin.jar'
}
```

---

## 第二步：写 PEC.json

PEC = Plugin Execution Contract，插件的身份证。

```json
{
  "contractSchema": "1.0",
  "pluginId": "my-first-plugin",
  "version": "1.0.0",
  "displayName": "我的第一个插件",
  "authors": ["YourName"],
  "entrypoint": "zcslib.myplugin.MyPlugin",
  "priority": 100,
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

---

## 第三步：写入口类

```java
package zcslib.myplugin;

import zcslib.api.PluginContext;
import zcslib.api.OrderResult;
import java.util.Map;

public class MyPlugin {

    private final PluginContext ctx;

    public MyPlugin(PluginContext ctx) {
        this.ctx = ctx;

        ctx.getLogger().info("插件已启动: ID=%s  信任等级=%s",
            ctx.getPluginId(), ctx.getTrustLevel());

        // 获取数据目录
        ctx.getLogger().info("数据目录: %s", ctx.getDataFolder().getAbsolutePath());

        // 读配置
        OrderResult r = ctx.order("config:load",
            ctx.getPluginId(), "settings.json");
        if (r.isOk() && r.getData() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) r.getData();
            ctx.getLogger().info("配置内容: %s", cfg);
        }

        // 注册一个服务
        ctx.order("service:register", ctx.getPluginId(),
            "hello", "返回问候语", (ServiceProvider) name ->
                "Hello from " + ctx.getPluginId() + "!");

        ctx.getLogger().info("启动完成。");
    }
}
```

---

## 第四步：编译部署

```bash
gradle jar
# 产物: build/libs/my-plugin.jar

# 部署到 ZCSLIB 插件目录
cp build/libs/my-plugin.jar config/DLZstudio/ZCSLIB/plugins/
```

---

## 第五步：启动验证

启动 Minecraft 后查看日志：
```
[ZCSLIB] [my-first-plugin] 插件已启动: ID=my-first-plugin  信任等级=N
[ZCSLIB] [my-first-plugin] 数据目录: .../plugins/my-first-plugin/data/
[ZCSLIB] [my-first-plugin] 配置内容: {...}
[ZCSLIB] [my-first-plugin] 启动完成。
```

---

## 常见问题

**Q: 插件没被加载？**
检查 PEC.json 是否在 JAR 根目录或 `META-INF/zcslib/` 目录下。

**Q: 信任等级是 S？**
说明 PEC.json 未被识别，或格式错误。检查 `pluginId` 字段。

**Q: 如何调试？**
所有日志输出到 `logs/zcslib/my-first-plugin.log`，同时输出到 Minecraft 控制台。
