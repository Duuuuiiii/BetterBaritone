# BetterBaritone

面向地狱长途飞行的 Baritone 扩展。输入终点 X、Z，即可自动规划路线并控制鞘翅飞行。支持 Minecraft **1.21.11**、**Fabric** 和 **Java 21**。

## 安装

从 [GitHub Releases](https://github.com/Duuuuiiii/BetterBaritone/releases) 下载 **0.1.45 完整替换版**。需要独立搭配官方 Baritone 的玩家可下载 [0.1.16 附加版](https://github.com/Duuuuiiii/BetterBaritone/releases/tag/v0.1.16)。

| 版本 | 放进 `mods` 的文件 |
| --- | --- |
| 附加版 | `BetterBaritone-Addon-*.jar` 和官方 [`baritone-unoptimized-fabric-1.17.0.jar`](https://github.com/cabaletta/baritone/releases/download/v1.17.0/baritone-unoptimized-fabric-1.17.0.jar)。需要 Fabric Loader 0.18.4 或更新版本。 |
| 完整替换版 | `BetterBaritone-*.jar`。先把原来的 Baritone 或旧版 BetterBaritone 移出 `mods`。 |

附加版使用官方 Baritone 寻路。使用 `standalone`、`api` 或客户端内置版的玩家，请先切换到上表指定的 `unoptimized` 版本。完整替换版提供额外的地狱脱困和飞行诊断。

0.1.45 保留 0.1.12 的飞行代码，并合入 0.1.16 的挖矿兼容代码。原版 Tanghulu 1.0.7 可以直接搭配使用。

## 快速上手

1. 进入地狱，装备鞘翅并带上烟花。
2. 按 **F6** 打开面板，输入终点 **X、Z**，查看预计耗时、烟花和鞘翅用量，再点「开始飞行」。
3. 按 **U** 暂停并接管，再按一次恢复。默认键位可在「设置」中修改。

HUD 可调整位置和偏移，也可关闭。界面默认中文，可在 HUD 页切换英文。规划路线和飞行预测线可分别开关和改色，背包补给可把烟花和食物移到指定的快捷栏槽位。

## 消耗怎么估算

模组在本地记录实际飞行距离、烟花用量和鞘翅耐久损耗。用历史平均每格消耗乘以当前的 **XZ 直线距离**，得到这趟的预估。比如飞 1 万格平均用 64 发烟花，剩 5 万格就预计用 320 发，也就是 5 组。鞘翅损耗折算为满耐久鞘翅数量。飞行中会逐步改用本趟接近终点的速度和烟花用量，并显示本趟已用烟花。历史数据不足时会显示「飞行数据不足」。

## 构建与反馈

- **0.1.45** 由已归档的 0.1.12 和 0.1.16 完整 JAR 组装。运行 `python scripts/build-0.1.45.py <0.1.12.jar> <0.1.16.jar> BetterBaritone-0.1.45.jar` 可复现。脚本校验两个输入的 SHA-256，只替换挖矿相关的三个类和版本号。
- 运行 `./gradlew test :fabric:remapJar -Pavailable_loaders=fabric,tweaker` 从当前 Gradle 源码构建 **0.1.44** 完整替换版，产物在 `fabric/build/libs`。
- 反馈问题时附上模组版本、目标坐标和复现步骤。完整替换版的飞行日志在实例目录下的 `baritone/logs/flight.log`。[提交反馈](https://github.com/Duuuuiiii/BetterBaritone/issues)

基于 [Baritone](https://github.com/cabaletta/baritone) 开发，感谢上游贡献者。[许可证](LICENSE)
