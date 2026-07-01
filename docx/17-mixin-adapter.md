# Mixin 适配器 — 详细设计 v0.1

> ZCSLIB v0.2.0, Phase 17 — mixin/

---

## 需求回顾

插件声明 Mixin 目标 → ZCSLIB 适配器解析 → 生成合并 Mixin config → 注册给 Mixin 框架
适配器维护版本映射表，Agent 辅助更新。

---

## 核心架构

```
@Mod 构造期 (类加载前)
│
├── 1. scanPluginMixins()
│     遍历 config/DLZstudio/ZCSLIB/plugins/*.jar
│     → 读每个 JAR 的 zcslib.mixin.json
│
├── 2. resolveRefmaps()
│     对每个插件的 refmap JSON: Mojang 名 → 查版本映射表 → 当前 obf 名
│     → 生成正确 refmap
│
├── 3. mergeConfigs()
│     合并所有插件的 Mixin 类列表 → 单一 mixins.zcslib.json
│
└── 4. register()
      MixinBootstrap.addConfig("mixins.zcslib.json")
      → 类加载时织入
```

---

## 插件声明格式

插件 JAR 根目录放 `zcslib.mixin.json`:

```json
{
  "pluginId": "my-plugin",
  "mixins": [
    "zcslib.myplugin.mixin.MixinItemStack",
    "zcslib.myplugin.mixin.MixinServerLevel"
  ]
}
```

插件同时放标准 refmap（由 Mixin 注解处理器生成，编译于 21.1）:
```
META-INF/my-plugin.refmap.json    ← 21.1 Mojang→obf 映射
```

---

## 适配器数据

### 版本映射表
```
config/DLZstudio/ZCSLIB/mappings/
├── 21.1.json    ← Mojang → 21.1 obf
├── 22.4.json    ← Mojang → 22.4 obf
├── 23.3.json    ← Mojang → 23.3 obf
├── 24.2.json    ← Mojang → 24.2 obf
├── 25.7.json    ← Mojang → 25.7 obf
└── 26.1.json    ← Mojang → 26.1 obf
```

格式:
```json
{
  "net/minecraft/world/item/ItemStack": {
    "obf": "net/minecraft/world/item/ItemStack",
    "methods": {
      "split (I)Lnet/minecraft/world/item/ItemStack;": "m_abc123"
    }
  }
}
```

### 合并后 config
```
config/DLZstudio/ZCSLIB/mixins.zcslib.json
```
插件不可直接编辑。

---

## refmap 重写流程

```
插件 refmap (21.1):
  "net.minecraft.world.item.ItemStack" → "net.minecraft.world.item.ItemStack"
  "ItemStack.split(I)LItemStack;"      → "m_41620"     ← 21.1 obf

运行时版本 (26.1):
  "net.minecraft.world.item.ItemStack" → "net.minecraft.world.item.ItemStack"
  "ItemStack.split(I)LItemStack;"      → "m_41621"     ← 26.1 obf (可能不同)

适配器输出:
  "net.minecraft.world.item.ItemStack" → "net.minecraft.world.item.ItemStack"
  "ItemStack.split(I)LItemStack;"      → "m_41621"     ← 已修正
```

大多数类名和简单方法随 Mojang 名稳定，**只有 obf 名跨版本变化的条目需重写**。实际映射表比全量小很多。

---

## 文件清单

```
zcslib/mixin/
├── MixinAdapter.java        // 入口: scan+resolve+merge+register
├── RefmapResolver.java      // refmap 重写引擎
├── MappingTable.java         // 版本映射表加载/查询
└── MixinConfigMerger.java   // 多插件 config 合并
```

---

## MixinAdapter 伪代码

```java
public final class MixinAdapter {
    public static void bootstrap(Path pluginsDir) {
        // 1. 加载运行时版本映射
        MappingTable runtimeMappings = MappingTable.loadCurrent();

        // 2. 扫描 plugins/ → 收集 Mixin 声明
        List<PluginMixinDecl> decls = scanPlugins(pluginsDir);

        // 3. 对每个插件: 读 refmap → 重写 → 收集 Mixin 类
        List<String> allMixinClasses = new ArrayList<>();
        for (PluginMixinDecl d : decls) {
            Refmap remapped = RefmapResolver.resolve(
                d.refmap(), runtimeMappings);
            // 写入临时文件，Mixin 框架会读它
            remapped.writeTo(pluginsDir.resolve(d.pluginId() + ".refmap.json"));
            allMixinClasses.addAll(d.mixinClasses());
        }

        // 4. 合并 config
        Path configPath = pluginsDir.resolve("../mixins.zcslib.json");
        MixinConfigMerger.merge(allMixinClasses, configPath);

        // 5. 注册
        MixinBootstrap.addConfig(configPath.toAbsolutePath().toString());
    }
}
```

---

## 兼容矩阵

| 场景 | 结果 |
|---|---|
| 类名跨版本不变 | ✅ 无需映射，refmap 原样复用 |
| 方法 obf 名变 | ✅ 映射表查 Mojang desc → 当前 obf |
| 类被删除/重命名 | ❌ 该 Mixin 跳过，打 warn 日志，不影响其他 |
| 插件无 Mixin | ✅ zcslib.mixin.json 不存在，跳过 |
| 多插件目标同一方法 | ✅ 合并 config 中两个 Mixin 类都注册 |

---

## Agent 可维护范围

映射表只需包含**跨版本 obf 名有变化**的条目:

1. Agent 下载 NeoForge 各版本 Maven 工件
2. 提取 mappings (Mojang→obf)
3. 对比相邻版本 → 仅存差异条目
4. 写入 `mappings/{version}.json`

维护频率：每个 NeoForge 大版本一次，Agent 独立完成。

---

## 已知限制

1. **Class only 限制**: 插件 Mixin 只能 target 类（`@Mixin(SomeClass.class)`），不能用字符串指定目标
2. **accessWidener**: 如果插件需要访问 private 成员，需额外机制（暂不在此阶段）
3. **类结构重构**: 如果目标类在版本间被拆分/合并，对应 Mixin 直接跳过

---

## Phase 17 子任务

| # | 任务 | 文件 |
|---|------|------|
| 1 | MappingTable 实现 | MappingTable.java |
| 2 | RefmapResolver 实现 | RefmapResolver.java |
| 3 | MixinConfigMerger 实现 | MixinConfigMerger.java |
| 4 | MixinAdapter 入口 + 集成 ZCSLIB | MixinAdapter.java |
| 5 | 生成 21.1 映射表 (基线) | mappings/21.1.json |
| 6 | 生成 26.1 映射表 + 差异 | mappings/26.1.json |
| 7 | 构建验证 | — |
