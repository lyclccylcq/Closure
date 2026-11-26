# Closure 工作台主题素材

## 图标

以下通用 UI 像素图标从 fengshiyangrouguan 的 [dsh-closure-skin](https://github.com/fengshiyangrouguan/dsh-closure-skin/tree/e26d58d2042b88efdb2a66b93a8edb30a68a524a/src/icons) 原样引入。版权及 MIT 许可证见 [SKIN-MIT.txt](SKIN-MIT.txt)。

- `plus-pixel.png` 用于新会话。
- `folder-open-glow.png` 用于选择目录。
- `folder-closed-glow.png` 用于目录列表。

## 字体

- `closure-display.ttf` 采用 [dsh-closure-skin 自带的 Orbitron Black](https://github.com/fengshiyangrouguan/dsh-closure-skin/blob/e26d58d2042b88efdb2a66b93a8edb30a68a524a/lib/fonts/orbitron-900.woff2)，重命名为 Closure Display，转换为 TrueType 并仅保留标题需要的拉丁大写字母。版权和 SIL OFL 1.1 全文见 [ORBITRON-OFL.txt](ORBITRON-OFL.txt)。
- `closure-pixel.ttf` 采用 [dsh-closure-pet 自带的 Ark Pixel 12px](https://github.com/fengshiyangrouguan/dsh-closure-pet/blob/e1008aae230eeb65917593dae7de602d4c8e6204/assets/fonts/ark-pixel-12px-zh_hans.woff2)，重命名为 Closure Pixel，转换为 TrueType 并仅保留固定界面标题的简体中文字。版权和 SIL OFL 1.1 全文见 [ARK-PIXEL-OFL.txt](ARK-PIXEL-OFL.txt)。

工作台布局和样式使用 Closure 自己的 JavaFX 实现。

## 本地可露模型

工作台可从本机的 `~/.closure/assets/workbench/` 读取透明待机动画。可用 `scripts/import_workbench_mascot.py` 导入 [dsh-closure-pet 的 idle.webm](https://github.com/fengshiyangrouguan/dsh-closure-pet/blob/e1008aae230eeb65917593dae7de602d4c8e6204/assets/video/idle.webm)，导入保留透明通道并记录来源文件摘要。源仓库将角色动画限定为个人本机使用，详见 [NOTICE.md](https://github.com/fengshiyangrouguan/dsh-closure-pet/blob/e1008aae230eeb65917593dae7de602d4c8e6204/NOTICE.md)。

本地素材缺失时使用 Closure 自带的角色画面。`CLOSURE_WORKBENCH_MASCOT_DIRECTORY` 可以指定素材目录。
