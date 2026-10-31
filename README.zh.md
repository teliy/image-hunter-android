# Image Hunter — Android

[Image Hunter（Windows/Linux 版）](https://github.com/teliy/image-hunter)的移动版：
网页图片搜索、搜索结果图块上的橙色复选框、把勾选的图片批量下载到手机图库。

核心与桌面版一致：内置浏览器（WebView）打开搜索引擎结果页，并注入与桌面版
**完全相同的 JavaScript** 复选框脚本（桌面版 `app/core/extractor.py`）——
包含 Yandex、DuckDuckGo、Google、Bing、Brave、Sogou 的图块识别规则、
代理缩略图还原，以及向包含原图地址的包装节点回溯的逻辑。

## 功能（v0.2.0）

- **搜索** — 11 个搜索引擎，与桌面版相同的结果页地址：Yandex、DuckDuckGo（safe）、
  Google、Bing、Brave、Startpage、Mojeek、Baidu、Sogou、Naver、Mail.ru；
- **图块复选框** — 直接在结果页勾选需要的图片，下载按钮实时显示已勾选数量；
- **下载已勾选** — 每个图块优先取大图（`best`，失败再试 `alts`），
  保存到 `Pictures/ImageHunter`，带结果页 `Referer`、图片校验、并发 3；
- **收集链接** — 确认后下载本页发现的全部图片链接（优质候选优先）；
- **桌面版页面** — 设置中的 User-Agent 开关：部分搜索引擎的移动版结果页
  无法挂载复选框；
- **下载** — 已下载图片网格、预览、分享、删除（无需存储权限 — MediaStore）；
- **日志** — 与桌面版相同的事件：识别出多少图块、哪些下载成功或失败；
- **语言** — 俄语、英语、中文（设置 → 语言）；
- **应用内更新** — 设置 →「检查更新」（另有每日静默检查一次）：APK 从
  GitHub Releases 下载并交给系统安装器，与桌面版一致；
- 系统返回键即为“浏览器后退”。

## 安装

### 现成 APK

1. 打开 [Actions](https://github.com/teliy/image-hunter-android/actions) →
   最近一次成功的 **build** → 下载工件 `ImageHunter-…-debug-apk`；
2. 允许安装未知来源应用后安装；
3. 也可以在 [Releases](https://github.com/teliy/image-hunter-android/releases)
   页面按标签下载 APK。

需要 Android 10（API 29）及以上。应用仅申请联网权限（以及应用内更新所需的
「安装未知应用」权限 —— 首次更新时系统会询问）。

### 升级到新版本

从 v0.2.0 起可以直接在应用内升级：**设置 → 检查更新**。应用会比对
[Releases](https://github.com/teliy/image-hunter-android/releases)，下载 APK
并启动安装；首次更新时 Android 会请求允许 Image Hunter「安装未知应用」。
项目所有 APK 使用同一密钥签名，新版本可直接覆盖安装，无需卸载旧版。
自动检查最多每天一次，且仅在有新版本时提示。

### 从源码构建

```bash
git clone https://github.com/teliy/image-hunter-android
cd image-hunter-android
./gradlew assembleDebug        # APK：app/build/outputs/apk/debug/
```

需要 JDK 17 和 Android SDK（用 Android Studio 打开项目即可）。

## 与桌面版的差异

详见 [README.md](README.md)（俄语）中的对照表：Android v0.2.0 覆盖
“搜索 + 复选框 + 批量下载”核心；“在所有引擎中查找”、格式转换、文件夹树
在计划中。Android WebView 无法在跨域 iframe 中执行 JS，因此 Mail.ru
结果页内嵌的 Yandex 框架暂时没有复选框。

## 隐私

无分析统计、无服务端：请求只发往搜索引擎与图片源站点。日志仅保存在
应用内存中。

## 许可证

MIT — 与桌面版相同。
