# ZCSLIB 瀹炵幇璺緞 v0.1.0

## 鎬诲師鍒?
1. **鍧氬３杞姱銆?* 姣忎釜 Phase 缁撴潫鏃朵骇鐗╁彲缂栬瘧銆佸彲鍚姩銆佸彲閫氳繃 NeoForge 鍔犺浇銆?2. **鍚戜笅涓嶄緷璧栥€?* 涓ョ鍚庝竴 Phase 瀹屾垚鍚庡洖澶存敼鍓嶄竴 Phase 鐨勫叕寮€鎺ュ彛銆?3. **S 绾у厛琛屻€?* 姣忓疄鐜颁竴涓瓙绯荤粺锛岀珛鍗宠ˉ榻愪俊浠诲垎绾ф嫤鎴€昏緫锛屼笉鎷栧埌鍚庢湡琛ャ€?4. **order() 椹卞姩銆?* 鎵€鏈夊姛鑳藉叆鍙ｉ€氳繃 `kernel.order()` 鏆撮湶锛屽唴閮ㄥ瓙绯荤粺涓嶇洿鎺ュ鎻掍欢鍏紑銆?
---

## 渚濊禆鎷撴墤

```
Phase 1  鍐呮牳楠ㄦ灦
   鈹?Phase 2  PluginContext + Logger
   鈹?   鈹溾攢鈹€ Phase 3  PEC 瑙ｆ瀽 + 鎻掍欢鍔犺浇鍣?   鈹?     鈹?   鈹?     鈹溾攢鈹€ Phase 4  璧勬簮绠＄悊鍣?   鈹?     鈹?     鈹?   鈹?     鈹?     鈹斺攢鈹€ Phase 6  閰嶇疆 + PDC 鎸佷箙鍖?   鈹?     鈹?   鈹?     鈹溾攢鈹€ Phase 7  鏈嶅姟娉ㄥ唽琛?   鈹?     鈹?   鈹?     鈹斺攢鈹€ Phase 8  浜嬩欢鎬荤嚎
   鈹?   鈹溾攢鈹€ Phase 5  寮傛璋冨害鍣?   鈹?   鈹斺攢鈹€ Phase 9  缃戠粶鎶借薄灞?          鈹?          鈹斺攢鈹€ Phase 10  瀹¤ + 宕╂簝鏃ュ織
                 鈹?                 鈹斺攢鈹€ Phase 11  Daemon + 鑷紨鍖栧寘
                        鈹?   (鍙?Main-Class锛屽唴缃?ZCSLIB.jar)
                        鈹?   (鏂囦欢浜や簰锛屾棤 IPC)
                        鈹?                        鈹斺攢鈹€ Phase 12  璁ょ煡涓庤嚜婕斿寲浣撶郴
                             鈹溾攢鈹€ L1-L4 鍥涢樁璁板繂
                             鈹溾攢鈹€ 姊︽搸锛堢敱 Daemon 椹卞姩锛?                             鈹溾攢鈹€ 鍙傛暟浣撶郴 + 濂栨儵
                             鈹斺攢鈹€ 鍘嬬缉浣撶郴锛? 褰㈡€侊級
```

---

## Phase 1 鈥?鍐呮牳楠ㄦ灦锛堥璁?1 涓?BUILD锛?
**鐩爣锛?* 妯＄粍鍙 NeoForge 鍔犺浇锛岃緭鍑轰竴琛屾棩蹇楄瘉鏄庡瓨鍦ㄣ€?
### 浜у嚭鏂囦欢

```
src/main/java/com/dlzstudio/zcslib/
鈹溾攢鈹€ ZCSLIB.java              # @Mod 涓荤被锛孨eoForge 鍏ュ彛
鈹斺攢鈹€ kernel/
    鈹斺攢鈹€ ZCSKernel.java       # 绌哄３鍐呮牳锛屾殏鍙緭鍑哄惎鍔ㄦ棩蹇?
src/main/resources/
鈹斺攢鈹€ META-INF/
    鈹斺攢鈹€ neoforge.mods.toml   # 妯＄粍鍏冩暟鎹?```

### 楠岃瘉鏍囧噯

```
[INFO] [ZCSLIB] [N/zcslib] [Main]: ZCSLIB Kernel v0.1.0 initialized.
```

### 涓嶅仛鐨?
- 涓嶈浠讳綍閰嶇疆
- 涓嶅姞杞戒换浣曟彃浠?- 涓嶆帴鍙椾换浣?order()

---

## Phase 2 鈥?PluginContext + Logger锛堥璁?1-2 涓?BUILD锛?
**鐩爣锛?* 鏃ュ織绯荤粺璺戦€氾紝PluginContext 鎺ュ彛瀹氬瀷銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹溾攢鈹€ api/
鈹?  鈹溾攢鈹€ PluginContext.java       # 7 鏂规硶鎺ュ彛
鈹?  鈹溾攢鈹€ TrustLevel.java          # N / R / A / S 鏋氫妇
鈹?  鈹斺攢鈹€ OrderResult.java         # ok + error + data
鈹溾攢鈹€ log/
鈹?  鈹斺攢鈹€ ZCSLogger.java           # 鍙岃建鍒讹細[ZCSLIB] 鍓嶇紑 + 鐙珛鏂囦欢
鈹斺攢鈹€ kernel/
    鈹斺攢鈹€ ZCSKernel.java           # 鏂板 order() 绌哄３鏂规硶浣?+ dispatch 璺敱楠ㄦ灦
```

### 鍏抽敭鍐崇瓥

- **ZCSLogger** 鏋勯€犲嚱鏁扮鍚嶄负 `ZCSLogger(String pluginId, TrustLevel level, Path logDir)`
- 鍙岃建杈撳嚭鍦ㄦ瀯閫犳椂鍐冲畾锛屼笉鍦ㄦ瘡娆¤皟鐢ㄦ椂鍒ゆ柇
- 鏍煎紡閿佸畾锛歚[{TIME}] [{LEVEL}] [{TRUST}/{PLUGIN_ID}] [{THREAD}]: {MESSAGE}`

### 楠岃瘉鏍囧噯

```java
// 纭紪鐮佹祴璇曪紙Phase 3 涔嬪墠鎵嬪姩鏋勯€?PluginContext锛?ZCSLogger log = new ZCSLogger("test", TrustLevel.N, Path.of("logs/zcslib"));
log.info("Logger online");
// 鈫?// [2026-06-13 16:00:00.000] [INFO] [N/test] [Main]: Logger online
// 鍚屾椂鍐欏叆 logs/zcslib/test.log
```

---

## Phase 3 鈥?PEC 瑙ｆ瀽 + 鎻掍欢鍔犺浇鍣紙棰勮 2-3 涓?BUILD锛?
**鐩爣锛?* 浠?`plugins/` 鐩綍鍙戠幇骞跺姞杞?Native Plugin銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹溾攢鈹€ pec/
鈹?  鈹溾攢鈹€ PECScanner.java          # 4 璺緞浼樺厛绾ф壂鎻忥紙/META-INF/zcslib/PEC.json 鈫?/PEC.json锛?鈹?  鈹溾攢鈹€ PECSchema.java           # PEC JSON 鈫?Java 瀵硅薄鏄犲皠
鈹?  鈹斺攢鈹€ PECValidator.java        # 鐜鏍￠獙 鈫?PASS / SOFT_FAIL / HARD_FAIL
鈹溾攢鈹€ loader/
鈹?  鈹溾攢鈹€ PluginClassLoader.java   # 闅旂 ClassLoader锛堢姝㈣闂唴鏍哥鏈夊寘锛?鈹?  鈹溾攢鈹€ PluginDescriptor.java    # 鎻掍欢鍏冩暟鎹紙id/version/trust/classloader/pec锛?鈹?  鈹斺攢鈹€ PluginLoader.java        # 鎵弿 鈫?鍒嗙被 鈫?鍔犺浇 鈫?娉ㄥ叆 PluginContext
鈹斺攢鈹€ kernel/
    鈹斺攢鈹€ ZCSKernel.java           # 鏁村悎锛氬惎鍔ㄦ椂 PluginLoader.scan() 鈫?鏃ュ織杈撳嚭鍔犺浇缁撴灉
```

### 鍏抽敭鍐崇瓥

- **PECScanner 鍛戒腑鍗虫**锛氭寜鐧界毊涔?4 涓矾寰勯『搴忔壂鎻忥紝鎵惧埌绗竴涓氨鍋?- **PluginClassLoader 榛戝悕鍗?*锛氱姝㈣闂?`com.dlzstudio.zcslib.kernel.internal.*`
- **铏氭嫙 PEC 鐢熸垚**锛歊/A/S 绾ф棤 PEC 鐨勭粍浠讹紝鍐呮牳鑷姩鐢熸垚铏氭嫙 PEC
- **鍔犺浇椤哄簭**锛氭寜 PEC 涓?`priority` 瀛楁鍗囧簭鍔犺浇锛?100 鏈€鍏堬紝100 鏈€鍚庯級

### 楠岃瘉鏍囧噯

```
[INFO] [ZCSLIB] [N/zcslib] [Main]: Scanning plugins/ ...
[INFO] [ZCSLIB] [N/zcslib] [Main]: Found 2 plugin(s)
[INFO] [ZCSLIB] [N/zcslib] [Main]: [N/iems] Loaded (PEC verified)
[INFO] [ZCSLIB] [N/zcslib] [Main]: [R/external-mod] Loaded (Virtual PEC)
```

### 涓嶅仛鐨?
- 涓嶅疄鐜?Standalone Mod 鐨勮櫄鎷?PEC锛堥偅鏄?Phase 7-8 鍚庣殑鎵╁睍锛?- 涓嶅疄鐜?Auto-Adapt 鏍囪鎵弿

---

## Phase 4 鈥?璧勬簮绠＄悊鍣紙棰勮 2-3 涓?BUILD锛?
**鐩爣锛?* 鐩綍缁撴瀯鍒涘缓 + 娌欑璺緞鏄犲皠 + 纾佺洏閰嶉銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹斺攢鈹€ resource/
    鈹溾攢鈹€ ZCSResourceManager.java  # 铏氭嫙璺緞 鈫?鐗╃悊璺緞鏄犲皠
    鈹溾攢鈹€ ResourceSandbox.java     # 璺緞瑙勮寖鍖?+ .. 闃绘柇 + 鏁忔劅鐩綍鎷掓
    鈹斺攢鈹€ DiskQuota.java           # S 绾?500MB / 鍏朵粬 2GB 閰嶉妫€鏌?```

### 鍏抽敭鍐崇瓥

- **鏍圭洰褰曢攣瀹?*锛氭墍鏈夎矾寰勫己鍒惰В鏋愬埌 `./config/DLZstudio/ZCSLIB/plugins/{id}/`
- **S 绾ч€冮€告娴?*锛歚new File("../..")` 鈫?`ResourceSandbox.canonicalize()` 鎶涘畨鍏ㄥ紓甯?- **閰嶉瀹炵幇**锛歚java.nio.file.FileStore.getUsableSpace()` 妫€鏌ュ垎鍖哄墿浣欙紝闈炵簿纭瓧鑺傝鏁帮紙鎬ц兘浼樺厛锛?
### 鐩綍缁撴瀯锛堣嚜鍔ㄥ垱寤猴級

```
config/DLZstudio/ZCSLIB/
鈹溾攢鈹€ global/           # 鍐呮牳鍏ㄥ眬锛圥DC 鍚庣锛?鈹溾攢鈹€ shared_res/       # 鍏变韩璧勬簮锛堝彧璇伙級
鈹溾攢鈹€ cache/            # 鍏ㄥ眬缂撳瓨
鈹斺攢鈹€ plugins/
    鈹斺攢鈹€ {plugin_id}/
        鈹溾攢鈹€ config/
        鈹斺攢鈹€ data/
```

### 楠岃瘉鏍囧噯

```java
ctx.order("resource:file", "/config/server.json");
// 鈫?杩斿洖 File("config/DLZstudio/ZCSLIB/plugins/test/config/server.json")

// S 绾ч€冮€告祴璇?ctx.order("resource:file", "../../../saves/world/player.dat");
// 鈫?OrderResult.error = "SANDBOX: Path escape denied"
```

---

## Phase 5 鈥?寮傛璋冨害鍣紙棰勮 2-3 涓?BUILD锛?
**鐩爣锛?* L0-L3 绾跨▼妯″瀷 + 鑸卞闅旂 + 鎵归噺鍚屾闃熷垪銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹斺攢鈹€ scheduler/
    鈹溾攢鈹€ ZCSScheduler.java        # order() 璺敱鍒扮殑璋冨害瀹炵幇
    鈹溾攢鈹€ ComputePool.java         # L3 璁＄畻绾跨▼姹狅紙FixedThreadPool, per-plugin 闃熷垪锛?    鈹溾攢鈹€ SyncQueue.java           # 鎵归噺鍚屾闃熷垪锛坱ick 鏈悎骞舵墽琛岋級
    鈹斺攢鈹€ Bulkhead.java            # 鑸卞锛氭瘡鎻掍欢鐙珛鏈夌晫闃熷垪 + 瓒呮椂鐔旀柇
```

### 鍏抽敭鍐崇瓥

- **ComputePool 绾跨▼鏁?*锛歚CPU 鏍稿績鏁?脳 2`锛屾瘡鎻掍欢鏈€澶у崰鐢?`min(鏍稿績鏁? 4)` 绾跨▼
- **SyncQueue 鍚堝苟**锛氬悓涓€ tick 鍐呭悓涓€鎻掍欢鐨勫娆?queueSync 鈫?鍚堝苟涓轰竴娆℃墽琛?- **鐔旀柇闃堝€?*锛氭彃浠跺崟娆?compute 瓒?50ms 鈫?WARN锛涜繛缁?3 娆?鈫?鐔旀柇璇ユ彃浠?L3 鏉冮檺 30 绉?- **S 绾ф嫤鎴?*锛歚scheduler:compute` 鈫?鐩存帴杩斿洖 `"FORBIDDEN:S"`

### 楠岃瘉鏍囧噯

```java
// 鎻掍欢 A 姝ｅ父璋冨害
ctx.order("scheduler:compute", () -> heavyCalculation());
// 鈫?鏃ュ織: [ZCSLIB] [N/plugin_a] [L3-Compute-1]: Task started
// 鈫?鏃ュ織: [ZCSLIB] [N/plugin_a] [L3-Compute-1]: Task completed (42ms)

// S 绾ц鎷?suspiciousctx.order("scheduler:compute", task);
// 鈫?OrderResult.error = "FORBIDDEN:S compute"
```

---

## Phase 6 鈥?閰嶇疆 + PDC 鎸佷箙鍖栵紙棰勮 2 涓?BUILD锛?
**鐩爣锛?* 鎻掍欢鍙鍐欑鏈夐厤缃枃浠跺拰鎸佷箙鍖栭敭鍊煎銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹溾攢鈹€ config/
鈹?  鈹斺攢鈹€ ConfigManager.java       # JSON/TOML 閰嶇疆鍔犺浇 + 鐑噸杞?鈹斺攢鈹€ persistence/
    鈹斺攢鈹€ PDCBackend.java          # NBT 搴忓垪鍖?+ 纾佺洏璇诲啓
```

### 鍏抽敭鍐崇瓥

- **閰嶇疆鏍煎紡**锛氫紭鍏?JSON锛屽悗缁敮鎸?TOML锛堜笌鐧界毊涔?`network.toml` 涓€鑷达級
- **PDC 鍚庣**锛歁inecraft `CompoundTag` 鈫?NBT 鏂囦欢锛岃嚜鍔ㄥ鐞?`BlockPos`銆乣ItemStack` 绛?MC 绫诲瀷
- **鍘熷瓙鍐欏叆**锛氬厛鍐?`.tmp` 鈫?`Files.move(tmp, target, ATOMIC_MOVE)`锛岄槻姝㈠穿婧冩崯姣佹暟鎹?
### 楠岃瘉鏍囧噯

```java
// 閰嶇疆
ctx.order("config:save", "server.json", Map.of("port", 8080));
Map cfg = (Map) ctx.order("config:load", "server.json").data;
// 鈫?{port: 8080}

// PDC
ctx.order("pdc:save", "player_homes", homeData);
HomeData loaded = (HomeData) ctx.order("pdc:load", "player_homes").data;
```

---

## Phase 7 鈥?鏈嶅姟娉ㄥ唽琛紙棰勮 2 涓?BUILD锛?
**鐩爣锛?* 鎻掍欢闂撮€氳繃鎺ュ彛鏉捐€﹀悎浜や簰锛屼俊浠诲垎绾ф嫤鎴?+ 瀹¤銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹斺攢鈹€ service/
    鈹溾攢鈹€ ServiceRegistry.java     # register / get / getWithMeta
    鈹溾攢鈹€ ServiceWrapper.java      # instance + providerId + providerLevel
    鈹斺攢鈹€ ServiceSecurityFilter.java  # S 绾ф敞鍐岄粦鍚嶅崟锛圞ernel/Admin/PlayerData/NetworkMain锛?```

### 鍏抽敭鍐崇瓥

- **娉ㄥ唽琛ㄥ瓨鍌?*锛歚ConcurrentHashMap<Class<?>, ServiceEntry>`锛岀嚎绋嬪畨鍏?- **S 绾ч粦鍚嶅崟鍏抽敭璇?*锛歚Kernel`銆乣Admin`銆乣PlayerData`銆乣NetworkMain` 鈥?鍚换涓€鍗虫嫆
- **璺ㄤ俊浠昏皟鐢ㄦ棩蹇?*锛歂鈫扴 璁?WARN锛孲鈫扤 璁?SECURITY

### 楠岃瘉鏍囧噯

```java
// N 绾ф敞鍐屾湇鍔?iemsctx.order("service:register", IEMSService.class, new IEMSServiceImpl());

// 鍙︿竴涓?N 绾ц幏鍙?ServiceWrapper<IEMSService> w = mi2ctx.order("service:get:meta", IEMSService.class).data;
w.getInstance().getEnergyData();  // N鈫扤, 姝ｅ父浣跨敤

// S 绾у皾璇曟敞鍐屾牳蹇冩湇鍔?suspiciousctx.order("service:register", PlayerDataService.class, impl);
// 鈫?OrderResult.error = "FORBIDDEN:S core service 'PlayerDataService'"
```

---

## Phase 8 鈥?浜嬩欢鎬荤嚎锛堥璁?2-3 涓?BUILD锛?
**鐩爣锛?* @Subscribe 椹卞姩鐨勪簨浠剁郴缁燂紝鑷姩绾跨▼鎶曢€掞紝S 绾у璁°€?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹斺攢鈹€ event/
    鈹溾攢鈹€ Event.java                # 鍩虹被锛坈ancelled + setCancelled锛?    鈹溾攢鈹€ ZCSEventBus.java          # register / post / unregister
    鈹溾攢鈹€ EventDispatcher.java      # 绾跨▼妫€娴?鈫?鑷姩鎶曢€掑埌涓荤嚎绋嬪悓姝ラ槦鍒?    鈹溾攢鈹€ SystemEvent.java          # 绯荤粺浜嬩欢鏍囪鎺ュ彛锛圥luginLoadedEvent 绛夛級
    鈹斺攢鈹€ Subscribe.java            # 娉ㄨВ
```

### 鍏抽敭鍐崇瓥

- **绾跨▼瀹夊叏**锛歀3 璁＄畻姹犱腑 post() 鈫?鑷姩鍏ラ槦 鈫?涓嬩竴 tick 涓荤嚎绋嬫墽琛岀洃鍚櫒
- **S 绾х郴缁熶簨浠舵嫤鎴?*锛氭敞鍐屾椂妫€鏌ョ洃鍚櫒鏂规硶鍙傛暟绫诲瀷锛宍instanceof SystemEvent` 鈫?鎷掔粷娉ㄥ唽
- **S 绾х帺瀹朵簨浠跺璁?*锛歚PlayerBreakBlockEvent` 绛?鈫?鑷姩鍐欏叆 `logs/zcslib/audit/S/{id}_audit.log`

### 楠岃瘉鏍囧噯

```java
// 鎻掍欢娉ㄥ唽鐩戝惉
ctx.order("event:register", new Object() {
    @Subscribe void onEnergyUpdate(EnergyUpdateEvent e) {
        // 姝ゅ姘歌繙鍦ㄤ富绾跨▼锛屽彲瀹夊叏淇敼涓栫晫
    }
});

// L3 绾跨▼涓彂甯?ctx.order("scheduler:compute", () -> {
    ctx.order("event:post", new EnergyUpdateEvent(500));
    // 浜嬩欢鏈珛鍗虫墽琛岋紝宸插叆涓荤嚎绋嬮槦鍒?});

// S 绾ц瘯鍥剧洃鍚郴缁熶簨浠?鈫?鎷掔粷
suspiciousctx.order("event:register", systemEventListener);
// 鈫?OrderResult.error = "FORBIDDEN:S SystemEvent 'PluginLoadedEvent'"
```

---

## Phase 9 鈥?缃戠粶鎶借薄灞傦紙棰勮 3-4 涓?BUILD锛?
**鐩爣锛?* 缁熶竴鍑哄彛绠″埗 + 涓诲寘鑱氬悎 + 绂荤嚎绛栫暐銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹斺攢鈹€ network/
    鈹溾攢鈹€ ZCSNetwork.java           # sendStandard / sendMain / setOfflineStrategy
    鈹溾攢鈹€ MainPacketAssembler.java  # Tick 绾х紦鍐?+ 澶栧３鐢熸垚 + 蹇冭烦娉ㄥ叆
    鈹溾攢鈹€ OfflineQueue.java         # RETRY_LATER 闃熷垪 + 纾佺洏鎸佷箙鍖?+ 50MB 涓婇檺
    鈹斺攢鈹€ AggregatorHealthCheck.java # HTTP Ping / TCP 杩炴帴妫€鏌?```

### 鍏抽敭鍐崇瓥

- **涓诲寘缂撳啿**锛氭瘡 tick 缁撴潫鍚堝苟鎵€鏈夋彃浠剁殑 sendMain 鈫?涓€涓富鍖呭彂鍑?- **搴忓垪鍙?*锛氫粠 `global/network_seq.dat` 璇诲彇鑷锛岄槻閲嶆斁
- **绂荤嚎闃熷垪涓婇檺**锛?0MB 纭《锛岃秴鍑?鈫?寮哄埗 DISCARD + ERROR 鏃ュ織
- **S 绾у己鍒?DEGRADE**锛氭棤璁烘彃浠惰缃粈涔堢瓥鐣ワ紝S 绾т竴寰嬮檷绾т负鏍囧噯鍖?
### 楠岃瘉鏍囧噯

```java
// N 绾ф甯镐娇鐢ㄤ富鍖?ctx.order("network:offline", OfflineStrategy.RETRY_LATER);
ctx.order("network:send:main", energyData);
// 鈫?鍐呮牳缁勮涓诲寘锛岃仛鍚堝櫒鍦ㄧ嚎鍒欑珛鍗冲彂閫?
// 鑱氬悎鍣ㄧ绾?+ RETRY_LATER
// 鈫?鏁版嵁瀛樺叆 plugins/iems/data/offline_queue/1718313600.bin
// 鈫?30 绉掑悗閲嶈瘯杩炴帴锛岃繛鎺ユ垚鍔熷垯閲嶆斁闃熷垪

// S 绾?sendMain 鈫?鎷︽埅
suspiciousctx.order("network:send:main", data);
// 鈫?OrderResult.error = "FORBIDDEN:S main packet"

// S 绾?sendStandard 鈫?鍏佽浣嗗璁?// 鈫?鍐欏叆 logs/zcslib/audit/S/suspicious_network.log
```

---

## Phase 10 鈥?瀹¤ + 宕╂簝鏃ュ織锛堥璁?1-2 涓?BUILD锛?
**鐩爣锛?* 鍒嗙骇瀹¤瀛樺偍 + 宕╂簝闅旂 + 鏃ュ織婊氬姩銆?
### 浜у嚭

```
src/main/java/com/dlzstudio/zcslib/
鈹斺攢鈹€ log/
    鈹溾攢鈹€ AuditLogger.java          # 鎸変俊浠荤瓑绾у垎鐩綍鍐欏叆
    鈹溾攢鈹€ CrashHandler.java         # 鍏ㄥ眬寮傚父鎹曡幏 鈫?鍐呮牳/鎻掍欢鍒嗙
    鈹斺攢鈹€ LogRotator.java           # 绛栫暐锛欰udit 7澶?/ Kernel Crash 姘镐箙 / Plugin Crash 5娆?```

### 鐩綍缁撴瀯

```
logs/zcslib/
鈹溾攢鈹€ audit/
鈹?  鈹溾攢鈹€ N/{plugin_id}_{date}.log
鈹?  鈹溾攢鈹€ R/{plugin_id}_{date}.log
鈹?  鈹溾攢鈹€ A/{plugin_id}_{date}.log
鈹?  鈹斺攢鈹€ S/{plugin_id}_{date}.log
鈹斺攢鈹€ crash/
    鈹溾攢鈹€ kernel_{timestamp}.log         # 鍐呮牳宕╂簝锛堟案涔呬繚鐣欙級
    鈹斺攢鈹€ plugins/
        鈹溾攢鈹€ iems/
        鈹?  鈹斺攢鈹€ crash_{timestamp}.log  # 鏈€澶氫繚鐣?5 涓?        鈹斺攢鈹€ mi2/
            鈹斺攢鈹€ crash_{timestamp}.log
```

### 楠岃瘉鏍囧噯

```java
// S 绾ф彃浠惰Е鍙戠帺瀹朵簨浠?鈫?鑷姩瀹¤
// logs/zcslib/audit/S/old_mod_2026-06-13_163000.log:
// [2026-06-13 16:30:00.123] [AUDIT] [S/old_mod] PlayerBreakBlockEvent by Steve (uuid: ...)

// 鎻掍欢宕╂簝 鈫?闅旂瀛樺偍
// crash/plugins/suspicious_mod/crash_2026-06-13_163500.log 鍖呭惈:
// - 鎻掍欢 ID + 淇′换绛夌骇
// - 寮傚父鍫嗘爤
// - 鏈€杩?50 鏉¤鎻掍欢鐨勫璁℃棩蹇?```

---

## Phase 11 鈥?Daemon 瀹堟姢杩涚▼ + 鑷紨鍖栧寘锛堥璁?5-7 涓?BUILD锛?
**鐩爣锛?* 鍐呯疆鍦?ZCSLIB.jar 涓殑绾?Java SE 瀹堟姢杩涚▼锛屽弻 Main-Class 鍒囨崲锛屾枃浠朵氦浜掞紝鏃?IPC銆?
### 鏋舵瀯

```
java -jar ZCSLIB.jar                     鈫?NeoForge mod 鍏ュ彛锛圥hase 1-10 鍏ㄩ儴锛?java -jar ZCSLIB.jar --daemon dream      鈫?绾?Java SE Daemon 鍏ュ彛锛堟ⅵ鎿庯級
```

- 閫氳繃 Manifest 鍙屽叆鍙ｆ垨 Spring Boot PropertiesLauncher 鍒囨崲
- Daemon 鍏ュ彛浠ｇ爜涓?import 浠讳綍 Minecraft/NeoForge 绫?- 鍒嗗彂鏃跺彧涓€涓?jar

### 浜у嚭

```
src/main/java/zcslib/daemon/          鈫?绾?Java SE锛岄浂 MC 渚濊禆
鈹溾攢鈹€ ZCSDaemon.java                    # main() 鍏ュ彛锛孋LI 璺敱
鈹溾攢鈹€ DreamWorker.java                  # 姊︽搸锛歀2鈫扡3 + MC 楠岃瘉 + 濂栨儵闂幆
鈹溾攢鈹€ L3Merger.java                     # 涓囪兘 L3 鍚堟垚锛堝 .zcsmem 鍘婚噸鍚堝苟锛?鈹溾攢鈹€ TrainingSetPacker.java            # 鎵撳寘璁粌闆?鈫?.zctsp
鈹溾攢鈹€ TrainingSetImporter.java          # 瀵煎叆鑱旈偊璁粌闆?鈫?鎷嗚В涓烘湰鍦?L2/L3
鈹溾攢鈹€ ParamFreezer.java                 # 鍙傛暟鍥哄寲锛氶攣瀹氬叏灞€ + 璁惧畾灞€閮ㄥ熀鍑?鈹溾攢鈹€ RestartEngine.java                # 宕╂簝鈫掑垎鏋?MEM 鈫?閲嶅惎 MC 杩涚▼
鈹斺攢鈹€ ui/
    鈹斺攢鈹€ TrainingUI.java               # Swing 榛戝簳缁垮瓧缁堢椋庢牸锛堝弬鏁伴浄杈惧浘绛夛級
```

### 鍏抽敭绾︽潫

1. **闆朵緷璧?*锛氫粎鐢?`java.base` 妯″潡
2. **绾?Java SE**锛氫笉寮曠敤 Minecraft銆丯eoForge銆佷换浣曠涓夋柟搴?3. **鍗曞懡浠ゅ惎鍔?*锛歚java -jar ZCSLIB.jar --daemon dream`
4. **甯搁┗鍐呭瓨 < 50MB**

### 涓?ZCSKernel 浜や簰锛堟枃浠朵氦浜掞紝鏃?IPC锛?
```
Daemon 娴佺▼锛?1. 澶囦唤涓婁竴涓増鏈?L3 鏂囦欢锛堟墦鍖呬负 zip锛?2. 璇诲彇 plugins/{id}/memory/l2/*.zcslog 鈫?鍋氭ⅵ 鈫?鐢熸垚鏂?L3
3. 鍐欏叆 plugins/{id}/memory/l3/{env_hash}.zcsmem
4. ZCSKernel 涓嬫鍚姩/鐑姞杞芥椂璇诲彇鏂版枃浠?```

### 楠岃瘉鏍囧噯

```
> java -jar ZCSLIB.jar --daemon dream
[DAEMON] ZCSLIB Daemon v0.2.0
[DAEMON] DreamWorker: scanning L2 journals...
[DAEMON] Found 3 plugins with L2 data
[DAEMON] Plugin 'mi2': extracting features... 6 patterns matched
[DAEMON] Generating candidate L3 rules... 2 rules generated
[DAEMON] Starting MC verification...
[DAEMON] MC stable for 120s 鈫?rules validated
[DAEMON] Writing L3 memory 鈫?plugins/mi2/memory/l3/a1b2c3d4.zcsmem
[DAEMON] Dream cycle complete.
```

---

## Phase 12 鈥?璁ょ煡涓庤嚜婕斿寲浣撶郴锛堥璁?5-7 涓?BUILD锛?
**鐩爣锛?* L1-L4 鍥涢樁璁板繂绯荤粺 + 姊︽搸 + 鍘嬬缉浣撶郴 + 鍙傛暟鍥哄寲锛屾浛浠ｇ櫧鐨功鏃х殑涓夐樁 Kitten/Adult/Elder 妯″瀷銆?
> **娉ㄦ剰**锛氱櫧鐨功 搂9 鐨勮蹇嗛儴鍒嗗凡杩囨棫锛孭hase 12 浠ヨ璁℃枃浠?`zcslib-evolution-design.md` 涓哄噯銆?
### 12.1 璁ょ煡浣撶郴锛圠1-L4 鍥涢樁璁板繂锛?
```
src/main/java/zcslib/evolution/
鈹溾攢鈹€ memory/
鈹?  鈹溾攢鈹€ L1Buffer.java          # 500 tick Ring Buffer锛岃褰曞師濮嬭皟鐢ㄩ摼甯?鈹?  鈹溾攢鈹€ L1Snapshot.java        # 宕╂簝鏃跺喕缁?L1Buffer 鈫?纾佺洏 .zcsl1
鈹?  鈹溾攢鈹€ L2Journal.java         # 鍗曟杩愯 append-only 浜嬩欢鏃ュ織 鈫?.zcslog
鈹?  鈹溾攢鈹€ L3Memory.java          # 闀挎湡浣滄垬鎵嬪唽锛堝甫鐜/浜烘牸/绛栫暐锛夆啋 .zcsmem
鈹?  鈹斺攢鈹€ L4Instinct.java        # 鏈兘妯″紡搴擄紙纭紪鐮?+ 鍙拷鍔狅級鈫?.zcsinst
```

| 灞傜骇 | 鐢熷懡鍛ㄦ湡 | 瀛樺偍璺緞 | 鑱岃矗 |
|:---|:---|:---|:---|
| L1 | 500 tick 婊戠獥 | `memory/l1/{ts}.zcsl1` | 鐬椂蹇収 + 宕╂簝鍙栬瘉 |
| L2 | 鍗曟杩愯鍛ㄦ湡 | `plugins/{id}/memory/l2/{date}.zcslog` | 鏉傛暎浜嬩欢鏃ュ織锛屽仛姊﹀師鏂?|
| L3 | 姘镐箙 | `plugins/{id}/memory/l3/{env_hash}.zcsmem` | 鐜鐗瑰寲浣滄垬鎵嬪唽 |
| L4 | 姘镐箙 | `memory/l4/instinct.zcsinst` | 鏋佺畝鐗瑰緛鐮佸厹搴?|

### 12.2 姊︽搸锛圖reamWorker锛夆€?鐢?Phase 11 Daemon 椹卞姩

瑙佽璁℃枃浠舵ā鍧楀洓锛屾牳蹇冩祦姘寸嚎锛?
```
L2 鏃ュ織 鈫?婊戝姩绐楀彛缁熻 鈫?澶氱淮鐗瑰緛鍚戦噺 鈫?妯″紡鍖归厤(L4 渚? 鈫?鍗遍櫓绛夌骇鍒ゅ畾
    鈫?鐢熸垚鍊欓€?L3 瑙勫垯 鈫?MC 楠岃瘉 鈫?濂栨儵璋冩暣鍙傛暟 鈫?鍐欏叆 L3
```

- 妯″紡搴撶敱 L4 缁存姢锛屾ⅵ鎿庡彧绠℃秷璐?- 楠岃瘉锛氬惎鍔ㄥ悓涓€ MC 鐗堟湰锛岃繘涓荤晫闈㈠悗閰嶇疆鏃堕檺鍐呮湭宕╂簝 鈫?閫氳繃
- v0.2.0-M4 浠呮墜鍔ㄨЕ鍙戯紙`--daemon dream`锛夛紝涓嶅仛鑷姩璋冨害

### 12.3 鍙傛暟浣撶郴

```
src/main/java/zcslib/evolution/
鈹溾攢鈹€ params/
鈹?  鈹溾攢鈹€ GlobalParams.java      # 鐔靛蹇嶅害/鑷剤绱ц揩搴?璧勬簮璐┆搴?鎵弿鏁忔劅搴?鈹?  鈹溾攢鈹€ LocalParams.java       # 鍘嬪埗鍊惧悜/鎻愭潈鍊惧悜/璧勬簮鏉冮噸锛埪?0% 娉㈠姩锛?鈹?  鈹溾攢鈹€ BilateralParams.java   # 鍐茬獊瑁佸喅鍊惧悜/杩炲甫闄嶇骇鍊惧悜/鏇夸唬鍋忓ソ
鈹?  鈹斺攢鈹€ AttentionParams.java   # 鍏虫敞搴?閬楀繕閫熺巼锛堝姩鎬侊紝涓嶅浐鍖栵級
```

| 绫诲瀷 | 鍥哄寲琛屼负 | 娉㈠姩闄愬埗 |
|:---|:---|:---|
| 鍏ㄥ眬鍙傛暟 | 鍙浐鍖栵紝鍥哄寲鍚庣粷瀵归攣瀹?| 鏃犳尝鍔?|
| 灞€閮ㄥ弬鏁?| 鍙浐鍖栧熀鍑嗗€?| 卤10% 杞害鏉?|
| 娉ㄦ剰鍔?| 涓嶅浐鍖?| 鍔ㄦ€?|

### 12.4 鍘嬬缉浣撶郴

```
src/main/java/zcslib/evolution/
鈹溾攢鈹€ quarantine/
鈹?  鈹溾攢鈹€ StubReplacer.java      # ASM/Proxy 灏嗗嵄闄╂柟娉曡繑鍥?0/null
鈹?  鈹溾攢鈹€ KernelCache.java       # Daemon 娌欑 ClassLoader 闅旂杩愯
鈹?  鈹溾攢鈹€ CollateralDegrader.java # 鐗虹壊娆¤ B锛屼繚鍏ㄦ牳蹇?A
鈹?  鈹斺攢鈹€ TimelineRollback.java  # L1 蹇収 鈫?鍥為€€涓栫晫 CompoundTag
```

| 褰㈡€?| 閫傜敤鍦烘櫙 | 鍓綔鐢?| 鍙€嗘€?|
|:---|:---|:---|:---|
| Stub 鏇挎崲 | 浣庨闄┿€侀潪鏍稿績 | 鍔熻兘澶辨晥锛岀郴缁熶笉宕?| 鉁?|
| KernelCache 闅旂 | 楂橀闄┿€佸叧閿矾寰?| 寤惰繜澧炲姞锛屽姛鑳藉畬鏁?| 鉁?|
| 杩炲甫闄嶇骇 | 涓ゆ彃浠跺啿绐?| B 鍔熻兘鍙楁崯 | 鈿狅笍 |
| 鏃剁┖鍥為€€ | 鐮村潖鎬ф瀬寮?| 鐜╁鎰熺煡鏃堕棿鍊掓祦 | 鉂?|

### 12.5 濂栨儵闂幆

```
鍋氭ⅵ 鈫?鏂?L3 鈫?MC 楠岃瘉
   鈹溾攢鈹€ 宕╂簝 鈫?鎯╃綒 鈫?entropy鈫?urgency鈫?鈫?鏇翠繚瀹?   鈹斺攢鈹€ 澶氭绋冲畾鏃犳姤閿?鈫?濂栧姳 鈫?entropy鈫?urgency鈫?鈫?鏇存縺杩?```

- 鏃犵洃绠¤缁冩椂宕╂簝绱Н 鈫?鑷劧閫€鍖栧洖淇濆畧娲?- 绠＄悊鍛樻墜鍔ㄨ皟婵€杩涗絾涓嶅浐鍖?鈫?鍑犺疆鍚庡張閫€鍖?- 鍥哄寲鏄敮涓€闃绘閫€鍖栨墜娈?
### 楠岃瘉鏍囧噯

```
# L1 宕╂簝蹇収
[CRASH] Plugin-B tick timeout 鈫?L1 snapshot saved to memory/l1/1718313600.zcsl1

# L3 瑙勫垯鍐欏叆
[DAEMON] L3 rule written: auto_PluginB_tick_perf_degrade_001 鈫?SOFT_THROTTLE, confidence 0.75

# 鍘嬬缉鎵ц
[QUARANTINE] Plugin-B render callback 鈫?Stub replaced (return null), reason: 3 consecutive timeouts
```

---

## 閲岀▼纰戜笌棰勮 BUILD 鏁?
| 閲岀▼纰?| 鍖呭惈 Phase | 棰勮 BUILD | 鐘舵€?|
|:---|:---|:---|:---|
| **M1: 楠ㄦ灦** | 1-2 | 3 | 鉁?鍐呮牳鍙惎鍔?+ 鏃ュ織鍙啓 |
| **M2: 鍗曟満鍐呮牳** | 3-6 | 9 | 鉁?鎻掍欢鍙姞杞?璋冨害/璇诲啓鏂囦欢 |
| **M3: 澶氭彃浠跺崗鍚?* | 7-8 | 5 | 鉁?鏈嶅姟娉ㄥ唽 + 浜嬩欢鎬荤嚎 |
| **M4: 鑱旂綉搴曞骇** | 9-10 | 7 | 鉁?缃戠粶灞?+ 瀹¤/宕╂簝鏃ュ織 (BUILD.00000019) |
| **M5: 涓嶆楦?* | 11-12 | 5 | 鉁?鑷紨鍖栧寘 + 璁ょ煡浣撶郴 (BUILD.00000024) |

**鎬昏锛氬疄闄?24 BUILD锛圡1-M5 鍏ㄩ儴瀹屾垚锛?*

> **M5 瀹屾垚鐘舵€?*锛欱UILD.00000024 宸插畬鎴?Phase 10锛堝璁?+ LogRotator锛? Phase 11锛圖aemon 9 鏂囦欢 + 鍙屽叆鍙ｏ級+ Phase 12锛堣鐭ヤ綋绯?18 鏂囦欢 + 涔濆瓙绯荤粺鍏ㄦ帴绾匡級銆倊55+ Java 婧愭枃浠躲€?
## 璁捐鍙傝€?
- **鑷紨鍖栧寘瀹屾暣璁捐**锛歚C:\Users\ASUS\.qclaw\workspace-agent-5e453a01\zcslib-evolution-design.md`
  - 妯″潡涓€锛氳鐭ヤ綋绯伙紙L1-L4 + 鍋氭ⅵ + 鑱旈偊锛?  - 妯″潡浜岋細鍙傛暟浣撶郴锛堝叏灞€/灞€閮?鍙岃竟 + 鍥哄寲 + 濂栨儵锛?  - 妯″潡涓夛細鍘嬬缉浣撶郴锛圫tub/KernelCache/杩炲甫闄嶇骇/鏃剁┖鍥為€€锛?  - 妯″潡鍥涳細姊︽搸锛堟棩蹇楃悊瑙?+ MC 楠岃瘉 + 濂栨儵闂幆锛?  - 妯″潡浜旓細璺緞浣撶郴锛堝榻愮櫧鐨功鏍圭洰褰?+ 鏂板 MEM 璺緞锛?
---

## 涓嶅湪姝よ矾寰勪腑鐨勫唴瀹癸紙鏄庣‘鎺ㄨ繜锛?
| 鍐呭 | 鍘熷洜 |
|:---|:---|
| Standalone Mod 鑷姩閫傞厤锛圓uto-Adapt锛?| 铏氭嫙 PEC 鐢熸垚閫昏緫闇€ M2 绋冲畾鍚庡啀璁捐 |
| 閰嶇疆绠＄悊鍣ㄦ彃浠讹紙ZCSConfigAdmin锛?| 闇€鏈嶅姟娉ㄥ唽琛ㄦ垚鐔熷悗浣滀负"绗竴涓?N 绾х壒娈婃彃浠?瀹炵幇 |
| Gradle 鎻掍欢 / SDK | 鍐呮牳 API 绋冲畾鍚庯紙M3 涔嬪悗锛夊啀鎻愪緵缁欏閮ㄥ紑鍙戣€?|
| 鍙鍖?Daemon UI 瀹屾暣鐗?| Phase 11 鍏堝嚭 CLI 鏂囨湰鐣岄潰锛孲wing 绐楀彛鐣欏埌 M5 鍚庢湡 |
