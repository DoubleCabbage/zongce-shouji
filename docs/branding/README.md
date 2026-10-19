# 综测手记图标

图形为记事本与勾选符号，沿用应用主题主色 `#5956B4`。不在图标内放中文文字，便于小尺寸识别。

- `icon.svg`：可编辑矢量导出。
- `icon-512.png`：512 × 512 PNG，适合项目展示或后续发布素材。
- `icon-preview.png`：方形、圆形遮罩和小尺寸的资产预览；不是设备截图。
- 应用使用 `app/src/main/res/drawable/ic_launcher_foreground.xml` 与自适应图标 XML；Android 13 另提供单色主题图标。

在 Windows PowerShell 执行 `./scripts/export-icon.ps1`，可从应用矢量路径重新导出图片。自适应图标主要图形位于中心安全区域，实际桌面显示由启动器的遮罩决定。真机上的启动器显示仍需验收。
