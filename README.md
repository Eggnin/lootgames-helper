# LootGames 助手 / LootGames Helper

给 GTNH 的 [LootGames](https://github.com/GTNewHorizons/LootGames) 小游戏用的客户端辅助模组，目前支持：

- **数独**：按 `K` 自动填完（四关连解）；按 `L` 在空格上显示绿色答案。
- **扫雷**：按 `K` 自动解（四关连解，需要你走到棋盘另一侧时会提示方向）；按 `L` 把推断出的**安全格**标绿、**雷**标红。

纯客户端、服务器不用装、不装 LootGames 也不会崩。

## 两种游戏的原理不一样

**数独**：LootGames 本来就把完整答案同步给了每个客户端（`SudokuBoard.writeNBT` 里同时写了
`puzzle` / `player` / `solution`），所以只要按答案逐格点右键。右键是 `cycleValueAdd`（0→1→…→9→0），
所以每格补 `(答案 - 当前 + 10) % 10` 次右键。

**扫雷**：LootGames **不会**把雷的位置发给客户端——`MSBoard.MSField.SYNC_CODEC` 对未翻开的格子直接
不写 `type`，客户端只知道"隐藏"和标记。所以必须真的解题：

1. 读取已翻开格子的数字（`[x][z]` 下标，和数独一样）；
2. 约束求解：单格计数规则 → 剩余雷数 → 两两子集规则 → 对每个连通"边界区块"做带剪枝的穷举，
   并用整盘雷数做全局加权（只把**所有可能布雷方案都一致**的格子判为安全/雷）；
3. 安全格用普通左键翻开（`C07PacketPlayerDigging` 状态 0，和玩家自己左键一模一样）；
4. 实在推不出来才猜一格（LootGames 默认 `board_logic=1`"无需猜测"生成棋盘，所以正常用不到）。

两种点击都是原版包：右键 → `SmartSubordinateBlock.onBlockActivated`，左键 →
`PlayerInteractEvent(LEFT_CLICK_BLOCK)`。没有伪造协议、没有改服务端。

> 雷不需要插旗：LootGames 的胜利条件只是"所有非雷格都翻开、所有雷都没翻开"，而且左键对**有标记**
> 的格子会被忽略，所以本模组会先把挡路的旗帜/问号右键清掉再翻。

## 安装

1. Minecraft **1.7.10 + Forge**（GTNH 自带）。
2. 需要 **LootGames 2.2.2 或更新版本**（数独 2.2.2 加入，2.2.14 已核对字节码）。
3. 把 `LootGamesHelper-1.1.2.jar` 丢进 `mods/`，客户端模组，服务器不用装。

## 用法

1. 走到棋盘旁边（数独 9×9、扫雷 13×13～19×19，棋盘就是脚下那层方块）。
2. 按 `K`：自动解**最近的**那个棋盘（数独或扫雷，按距离自动选）。再按一次停止。
3. 按 `L`：开关棋盘提示。

按键可以在 **选项 → 控制 → LootGames 助手** 里改（默认 `K` / `L`）。

**扫雷一定要走位**：服务端只接受 6 格内的点击，19×19 的盘站在中间也够不到四角。模组会先把
能点到的格子全部翻开，够不到时聊天栏/状态行会提示"往东走 3 格"这类方向，走过去会自动继续。

## 状态与限制

| 提示 | 含义 |
| --- | --- |
| 附近 24 格内没有数独/扫雷棋盘 | 没找到对应方块，走近一点或确认 LootGames 已装 |
| 还有 N 格够不到，请走到棋盘中间 / 往东走 N 格 | 超出服务端 6 格点击范围，需要走位 |
| 潜行中暂停 | 潜行时右键是倒退数字、左键也不会翻格，所以自动暂停 |
| 界面已打开，暂停 | 开着背包/聊天等 GUI 时暂停 |
| 有格子点了没反应 | 那一格的包被服务端丢了（多半是距离贴边）。模组会先冷处理这一格、继续点别的，并提示你走过去 |
| 棋盘结构不匹配 | 目标方块不是 LootGames 的 subordinate 方块，已停止点击 |
| 踩到雷了，等待重开… | 猜错了一格（扫雷每次 3 次机会），爆炸结束后会自动重新生成 |

其它行为：

- 四关都会连续自动通过（数独/扫雷都是 4 个 stage），奖励照常发。
- 数独不会帮你"提交"，但填对最后一格时 LootGames 自己会 `checkWin()` 过关。
- 旧版数独解坏的棋盘不用重开，新版会按当前数字重新计算还差几次右键。

## 配置

`config/lgsudoku.cfg`：

```ini
sudoku {
    I:cellsPerTick=3      # 每 tick 最多填几格数独（每格 1-9 次右键）
}
minesweeper {
    I:revealPerTick=6     # 每 tick 最多翻开几格扫雷
}
client {
    B:showHints=true      # 数独答案 / 扫雷安全格与雷的提示
    B:showHud=true        # 左上角显示进度
    D:scanRadius=24.0     # 搜索棋盘半径
    D:reachBlocks=5.0     # 只点这个距离内的格子（服务端硬上限 6 格）
}
```

## 兼容性

所有 LootGames 交互都走反射，编译期**不依赖** LootGames，同一个 jar 兼容 GTNH 各种 LootGames 构建。
用到的契约（已对 `lootgames-2.2.14.jar` 逐项核对字节码）：

| 用到的东西 | 位置 |
| --- | --- |
| `SudokuTile` / `MSMasterTile` | `...lootgames.common.block.tile.*` |
| `getGame()` | `GameMasterTile` |
| 数独 `getBoard()` / `puzzle` `player` `solution` | `GameSudoku` / `SudokuBoard`（public 字段，下标 `[x][z]`） |
| 扫雷 `getBoard()` / `cIsGenerated` | `GameMineSweeper`（`cIsGenerated` 是 public 字段） |
| 扫雷格子 `isHidden()` `getType()` `getMark()` | `MSBoard.MSField`；`Type.getId()`：-1=雷、0..8=数字 |
| 扫雷棋盘 `size()` `getBombCount()` / `board` | `MSBoard`（`board` 是 `private MSField[][]`） |
| `getBoardOrigin()` | `BoardLootGame`（= master +(1,0,1)，再按 `allocated-current` 居中偏移） |
| `convertToGamePos` = `(p.x-origin.x, p.z-origin.z)`，`convertToBlockPos` = `origin.offset(x,0,z)` | `BoardLootGame` |
| 右键范围 | `getDistanceSq < (blockReachDistance+1)^2`，`blockReachDistance = 5.0` → 6 格 |
| 左键范围 | `processPlayerDigging` 里玩家 Y 再 +1.5 后同样比 6 格 |
| 左键处理 | `PlayerInteractEvent(LEFT_CLICK_BLOCK)` → `ILeftInteractible.onLeftClick`（面朝上、不潜行才生效） |
| `SmartSubordinateBlock` | 棋盘方块（名字里带 `Subordinate`） |

## 构建

```bat
set JAVA_HOME=<JDK 25>
gradlew.bat build
```

> RetroFuturaGradle 2.x 的插件本身用 Java 25 编译，**运行 Gradle 需要 JDK 25**；模组本身仍编译为
> Java 8 字节码，JDK 8 工具链由 Gradle 自动下载。

产物：`build/libs/LootGamesHelper-1.1.2.jar`。

## 已验证 / 未验证

已验证：

- 数独求解器：17 提示经典题、空棋盘、无解题，200 组随机"满盘挖空"全部解回原盘。
- 扫雷求解器：13/15/17/19 四种盘各 60 局随机模拟（`board_logic=1` 之外的更难的随机盘），
  推断**从不出错**（没有任何一次因为推断而踩雷，踩雷全部来自主动猜测）；穷举节点上限在实测中
  从未触发（45 次因区块过大而跳过，0 次因节点上限中止）；单次求解最坏 4.3 ms。
- LootGames 契约：对 GTNH 2.9.0 自带的 `lootgames-2.2.14.jar` 用 `javap` 核对上表全部类/字段/方法，
  并确认 `SYNC_CODEC` 对隐藏格不写 `type`、`onClick` 左键走 `revealField`、右键走 `swapMark`。
- 原版链路：`PlayerControllerMP.onPlayerRightClick` 发 `C08`，左键破坏发 `C07(status 0)`。
- 客户端加载：1.7.10 开发客户端里模组正常 PreInit/Init、按键注册成功。

未验证：

- 没有在真实 GTNH 世界里跑完整对局（数独实机跑过一次，暴露并修掉了 `[x][z]` 下标错误；扫雷还没实机跑过）。
  第一次实机建议先按 `L` 看提示位置对不对，再按 `K`。

## 修订记录

- 1.1.2：数独也像扫雷一样用绿色方块标出"还没点到的格子"（够不到的会一直绿着，直接朝绿色走就行，不用再分东西南北）；提示方块与扫雷共用一套绘制代码。`r`n- 1.1.1：右键距离默认收紧到 5 格、优先点近处（服务端记录的位置比客户端晚 1 tick，贴着 5.9 格的包会被静默丢掉）；"点了没反应"不再直接停止，只冷处理那一格并提示走位；扫雷也按距离排序。`r`n- 1.1.0：加入扫雷（反射读取扫雷盘、约束求解 + 全局雷数加权、左键翻格、走位提示、绿/红提示方块）；
  数独与扫雷共用一个 `K` 键（自动选最近的盘）。jar 改名为 `LootGamesHelper`，modid 仍是 `lgsudoku`。
- 1.0.1：修正数独棋盘下标（`[x][z]` 而不是 `[行][列]`）、右键距离上限、提示 Y 偏移，新增"右键没生效"检测。
