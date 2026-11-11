# 日语悬浮翻译器 (Android)

初期原型：点击或选中其他应用中无障碍服务可读取的日语文本，使用 Google ML Kit 离线日中翻译，在悬浮窗显示中文。首次需要联网下载翻译模型。

**限制：** 目前没有视频字幕 OCR、视频音频识别、Sakura 模型或任意屏幕位置的点按 OCR；部分应用无法提供可访问文字。ML Kit 的日中翻译质量不等同于高精度日语小说专用模型。

安装：GitHub Actions → Build Android APK → 最新成功运行 → Artifacts 下载 debug APK；打开 App 并按提示授予悬浮窗、无障碍权限。
