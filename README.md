# SpeedBox — 谱面变速（Android）

面向 osu!mania / Malody Key 谱面的**离线变速工具**（Android 版）。

> **AI 生成说明**
> 本项目（包括界面、业务逻辑、移植代码与文档）由 **AI 辅助生成**，
> 并在人工指导下迭代完成。发布前请自行审查代码与产物。

## 功能

- **谱面变速**：`.osu` / `.mc` 时间轴整体缩放（bpm × speed，note time ÷ speed，
  beat 元组不动），文本层原位改写，不丢任何非时间字段。
- **音频变速**：解码 → SoundTouch tempo（变速不变调）→ 按原格式编码回写。
  - mp3 → jump3r（纯 Java LAME）
  - ogg → biniu / JOrbis（纯 Java libvorbis）
  - wav → 直接回写；其它格式 → WAV 兜底并同步修改谱面引用
- **曲库与任务队列**：内置 osu!lazer 风格谱面列表、右键/长按菜单、
  批量任务队列与进度、日志。
- **输出与分享**：分开 `.mcz` / 合包 `.mcz` / 散装三种输出方式；
  分享优先 `.osz`（Malody 兼容 osu），谱面含 `.mc` 时才用 `.mcz`。

## 构建

标准 Gradle + Wrapper，无需特殊环境：

```bash
# 需要 JDK 17+ 与 Android SDK（或让 Android Studio 自动配置）
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 首次启动授予存储权限，选择曲库目录（默认 Malody / osu!droid 预设）。
2. 长按谱包或谱面打开菜单，设置变速倍率 / BPM / 半音与输出方式。
3. 提交后到「任务队列」查看进度；完成后可「分享 .osz / .mcz」。

## 许可证 / 致谢

SpeedBox 以 MIT 许可发布，详见 [`LICENSE`](LICENSE)、
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) 与 [`NOTICE`](NOTICE)。
应用内「设置 → 输出 → 开源许可」可查看全部许可证全文。

| 组件 | 许可证 | 来源 / 说明 |
|---|---|---|
| osu!lazer | MIT (© ppy Pty Ltd) | UI / 布局 / 缓动 / 菜单 / 滚动的 Java 移植；**未使用任何 osu! 图片、音频或字体资源** |
| osu!framework | MIT (© ppy Pty Ltd) | Easing、ScrollContainer、Menu 等移植 |
| OSU-Toolbox | MIT (© Difficile) | 变速语义参考 |
| SoundTouch | LGPL-2.1 (© Olli Parviainen) | 原生 `libsoundtouch.so`，动态链接 |
| jump3r | LGPL-2.1+ | de.sciss:jump3r:1.0.5（LAME 纯 Java 移植） |
| JOrbis / biniu | LGPL-2.0+ (© ymnk, JCraft) | 纯 Java Ogg Vorbis；源码包含于本仓库 |
| Xiph.Org libvorbis | BSD-3-Clause | Vorbis 算法 |
| AndroidX Core | Apache-2.0 | Android 基础库 |
| Material Components | Apache-2.0 | Android 组件 |

> 早期 README 把 `biniu` 写成 BSD 是不准确的：`biniu` 是 JOrbis 的
> LGPL-2.0+ 移植，Xiph 原始 libvorbis 才是 BSD。此处已更正。

SoundTouch 以动态链接方式集成，原生库为本工程 `app/src/main/jniLibs/`
下的未修改官方编译产物；LGPL 文本见 `COPYING.SoundTouch.txt` 与
`app/src/main/assets/licenses/LGPL-2.1.txt`。如需替换或重编该库，可从
<https://codeberg.org/soundtouch/soundtouch>（tag `soundtouch-2.1.0`）获取源码。
