# SpeedBox — 谱面变速（Android）

面向 osu!mania / Malody Key 谱面的**离线变速工具**（Android 版）。
变速逻辑移植自 [OSU-Toolbox](https://github.com/XYboxyboxl/OSU-Toolbox)（MIT）的变速模块，
音频变速使用 [SoundTouch](https://www.surina.net/soundtouch/)（LGPL 2.1）。

## 功能

- **谱面变速**：`.osu` / `.mc` 时间轴整体缩放（bpm × speed，note time ÷ speed，
  beat 元组不动），文本层原位改写，不丢任何非时间字段
- **音频变速**：解码 → SoundTouch tempo（变速不变调）→ 按原格式编码回写
  - mp3 → jump3r（纯 Java LAME，LGPL）
  - ogg → biniu/jVorbisEnc（纯 Java libvorbis，BSD）
  - wav → 直接回写；其它格式 → WAV 兜底并同步修改谱面引用
- **批量处理**：选择谱面文件夹整树处理，输出树保持相对结构；
  同一音频只转码一次

## 构建

标准 Gradle + Wrapper，无需特殊环境：

```bash
# 需要 JDK 17+ 与 Android SDK（或让 Android Studio 自动配置）
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 选择谱面文件夹（SAF）
2. 输入倍率（0.05 ~ 20，不等于 1）
3. 选择输出文件夹，开始

## 许可证 / 致谢

| 组件 | 许可证 | 来源 |
|---|---|---|
| SoundTouch 原生库 | LGPL 2.1 | 官方示例 APK 2.1.0 提取（符号与 codeberg tag soundtouch-2.1.0 一致） |
| jump3r (MP3 编码) | LGPL 2.1 | de.sciss:jump3r:1.0.5 (Maven Central) |
| biniu vorbis (OGG 编码) | BSD (Xiph.Org) | umjammer/jVorbisEnc 整理的 biniu 纯 Java libvorbis 移植 |
| 变速语义参考 | MIT | XYboxl/OSU-Toolbox speed_change.py |

SoundTouch 依据 LGPL 2.1 以动态链接方式集成，原生库来源为本工程
`app/src/main/jniLibs/` 下的未修改官方编译产物；LGPL 许可证文本见
`COPYING.SoundTouch.txt`。如需替换或重编该库，可从
https://codeberg.org/soundtouch/soundtouch (tag soundtouch-2.1.0) 获取源码。

本项目其余代码以 MIT 许可发布。
