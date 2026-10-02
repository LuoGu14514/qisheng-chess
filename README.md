# 启升棋 qisheng-chess

Minecraft 1.20.1 的**中国象棋 PVP 模组**(Fabric)。放下一张棋盘方块,右键坐下,
两个人就在游戏里下一局真正的中国象棋 —— 红先、将死判负、重复局面与自然限着判和、
求和 / 认输 / 红黑互换 / 旁观 / 局内聊天,全部由**服务端权威**裁判。

- 模组 ID:`qisheng_chess`
- 方块:`qisheng_chess:cchess`
- 当前版本:`0.2.1`
- 平台:**仅 Fabric**。NeoForge 模块已于 v0.1.2 移除(它此前处于半删除状态,源码语法都不完整)。

---

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.14.21 |
| Fabric API | 0.92.1+1.20.1 |
| Architectury API | 9.2.14 |
| Java(编译目标) | **17** |
| Java(启动 Gradle 的 `JAVA_HOME`) | **必须是 17**,不能是 25(见下) |
| Java(Gradle daemon) | **25**,由仓库自动 fork |

### 三个 JDK 角色,以及为什么 `JAVA_HOME` 必须是 17

Architectury Loom `1.13.469` 会拉入 `net.fabricmc.unpick:unpick` 3.x,该依赖
**拒绝在 JVM < 21 上启动**;而 Gradle 8.x 又无法在 JDK 25 上运行。本仓库的解决方式是:

- `gradle/gradle-daemon-jvm.properties` → `toolchainVersion=25`
  Gradle 会**自动 fork 一个 JDK 25 的 daemon**(`Daemon JVM discovery is an incubating feature`),
  跑构建脚本和 Loom 的是这个 daemon。
- `build.gradle` 的 `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`
  负责用 JDK 17 编译,测试也跑在 JDK 17 上。
- `gradle.properties` 的 `org.gradle.java.installations.paths` 指向本机的 JDK 17 ——
  **这一行是机器相关的**,换机器要改成你自己的 JDK 17 路径。
  (注意 `.properties` 按 ISO-8859-1 读取,中文路径必须写成 `\uXXXX` 转义。)

启动 `gradlew` 的那个 JVM(即 `JAVA_HOME`)用 **17**,不要用 25。原因与任何源码无关,
是 Windows 中文路径 + 字符集的老问题:

> 类路径太长时 Gradle 会把 worker 的 classpath 写进一个 `@argfile`。
> 这个文件是由 **daemon** 按它自己的默认字符集写的,而 daemon 的字符集是从**启动它的客户端 JVM**
> 继承来的。若 `JAVA_HOME` 是 JDK 25(默认 UTF-8),文件就是 UTF-8;可实际执行测试的 worker 是
> JDK 17,它按 `sun.jnu.encoding`(本机 = cp936/GBK)解析 argfile ——
> 于是本项目路径里的 `代码` 被解成乱码,classpath 条目全部失效,
> **每一个测试类都报 `ClassNotFoundException`**(`Test process encountered an unexpected problem`)。
> 把 `JAVA_HOME` 换成 JDK 17 后客户端与 worker 编码一致(都是 GBK),问题消失。
>
> 这也是为什么 `README` 里给出的构建命令**显式设置 `JAVA_HOME`**。

## 构建

```bat
:: Windows —— JAVA_HOME 必须是 JDK 17,理由见上一节
set JAVA_HOME=D:\工具\jdk-17.0.20.1+1

:: 完整构建(含 51 个单元测试)
.\gradlew.bat clean build

:: 只要可装载的 jar(跳过测试)
.\gradlew.bat :fabric:remapJar

:: 只跑测试
.\gradlew.bat :common:test
```

产物:

```
fabric/build/libs/qisheng_chess-fabric-<version>.jar          ← 装进 mods/ 的那个
fabric/build/libs/qisheng_chess-fabric-<version>-dev-shadow.jar
fabric/build/libs/qisheng_chess-fabric-<version>-sources.jar
```

> 首次运行会下载 Gradle 9.2.1 发行包。如果 `services.gradle.org` 被网络挡住,
> 需要手动把 `gradle-9.2.1-bin.zip` 预置到
> `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.2.1-bin\<url-hash>\`。

### 运行开发客户端

```bat
.\gradlew.bat :fabric:runClient
```

## 玩法

1. 合成/取出 `qisheng_chess:cchess` 方块放下。
2. 走到 **5 格以内**右键 —— 第一位玩家入座红方,GUI 自动打开。
3. 第二位玩家右键入座黑方,对局开始(**PVP**);或留在单人上 ——
   全局模式为 **PVC** 时入座红方即开始,**电脑执黑**(`/qisheng mode pvc`)。
4. 第三位及以后右键 = 旁观;等有空位时用 `/qisheng takeover red|black` 接手
   (PVC 棋盘拒绝第二个真人接手,电脑才是固定的对手)。

GUI 内的按钮:求和、认输、红黑互换、离开。棋盘下方是旁观名单,右侧是局内聊天。
最近一次走子的起点和终点会被高亮成淡黄色色块,便于看清刚刚发生了什么;同时
会有一次轻微的 Note Block Pling 音效（v0.2.1 起）。

### 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/qisheng leave` | 玩家 | 离场(对局中离场 = 判负);旁观时停止旁观 |
| `/qisheng takeover red\|black` | 玩家 | 接手空位(需先右键进入旁观) |
| `/qisheng board` | 玩家 | 以 ASCII 打印当前棋盘(**唯一会走聊天的盘面输出**) |
| `/qisheng status` | 玩家 | 打印紧凑对局状态 |
| `/qisheng select <sq>` / `/qisheng move <src> <dst>` | 玩家 | 用命令行走子(测试/后备) |
| `/qisheng reset` | 玩家 | 重置自己所在的棋盘 |
| `/qisheng mode pvp\|pvc` | **OP(等级 2)** | 切换全局模式 |
| `/qisheng purge` | **OP(等级 2)** | 清空所有棋盘会话 |

`<sq>` 的坐标与 `/qisheng board` 打印出来的数字一致:**行 0 是最下面一行(红方底线),
行 9 是最上面一行(黑方底线)**,`sq % 9` 是 a..i 列,所以 `sq = 行 * 9 + 列`。

### 许可

本项目整体以 **GPL-2.0-or-later** 分发 —— 内嵌的规则引擎
(`com.qisheng.chess.engine.xqwlight`,源自 XiangQi Wizard Light,
Copyright 2004-2008 elephantbase.net / 2004-2013 xqbase.com)自带 GPLv2 文件头,
与 MIT 不兼容。完整文本见 [`LICENSE`](LICENSE),第三方组件清单见 [`NOTICE`](NOTICE)。

方块/物品材质来自 TLM 系资源(作者 tartaric_acid),**CC BY-NC-SA 4.0,仅限非商业使用**。

---

## 项目结构

```
qisheng-chess/
├─ common/            # 平台无关的全部逻辑(约 45 个类)
│  └─ src/main/java/com/qisheng/chess/
│     ├─ block/       # CChessBoardBlock(右键入口)
│     ├─ tileentity/  # CChessTileEntity(对局快照的 NBT 落盘)
│     ├─ network/     # 15 条包的注册与处理(CHESS_INTERACT / SYNC / POPUP / …)
│     ├─ pvp/         # SessionManager(会话)、GameLogic(裁判)、GameBroadcaster(广播)
│     ├─ command/     # /qisheng
│     ├─ client/      # CChessBoardScreen 与 8 个 widget
│     ├─ engine/xqwlight/  # 内嵌规则引擎(Position/Search/Util)
│     └─ event/       # 掉线处理
└─ fabric/            # Fabric 入口点与事件注册
```

`REVIEW-AND-PLAN.md` 是 2026-10-02 那次全量代码审查的记录(v0.1.2 → v0.3 的路线图),
留作开发参考。

## 已知限制

- **对局快照有丢失窗口**:棋盘状态在每次状态变化时标脏,随区块存档落盘;
  若在区块被保存前进程被杀,最近若干步可能丢失。
- **PVC 的电脑执黑,不可换**:`BoardMode.PVC` 下人类永远执红;`/qisheng takeover`
  与 `swapRoles` 都会拒绝(PVC 棋盘就是单人 vs 引擎)。
- **界面只做了 `zh_cn` 与 `en_us` 两种语言**。
- **GUI 仍缺走子动画**（最近一步高亮与走子音效已于 v0.2.1 落地）。

## 放置与朝向

`qisheng_chess:cchess` 接受 `facing` 状态属性,可设为 `north` / `south` /
`east` / `west`(默认 `north`)。`south` 会让 GUI 渲染时把棋盘整体翻转 180°,
适合把棋盘靠着北墙放、让南边走过来的玩家红方仍在下方;其余朝向只影响方块本身
的 3-D 朝向,GUI 不变。可用 vanilla 命令测试:

```
/setblock ~ ~ ~ qisheng_chess:cchess[facing=south]
```
