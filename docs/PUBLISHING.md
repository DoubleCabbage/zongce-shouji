# 综测手记：GitHub 发布准备

## 上传内容

建议使用本次另外整理的干净源码目录作为新仓库起点，不直接推送旧开发仓库的历史。开发仓库已跟踪大量审核截图、日志、测试工作簿；不能仅靠追加 `.gitignore` 将其从已有提交和历史中排除。

干净源码仅包含 Android 源码、构建脚本、Gradle Wrapper、测试源码、README、图标及公开说明，不携带 `.git`、本机配置、构建缓存、旧审核材料或个人数据。不要把同级的安装包和检查报告复制到源码仓库。

## 在本机检查

使用 Android Studio 打开源码目录，选择 JDK 17 或兼容的 Android Studio JBR，并安装 Android SDK 35。`local.properties` 由本机生成，不要提交。

```powershell
.\gradlew.bat :app:assembleDebug
```

首次安装使用 `app/build/outputs/apk/debug/app-debug.apk`，这是供试用的调试包。

正式分发前至少在一台真实手机上检查：安装、活动与照片保存、重启后数据保留、Excel 导入导出、活动包收发、备份恢复，以及从已有版本覆盖安装后的数据保留。Excel 图片兼容性仍需在目标 Excel/WPS 版本上验收。

## 签名与版本

发给同学长期使用前，在 Android Studio 中创建专用的 release 签名并生成签名 APK，妥善备份 keystore 与密码。它们留在本机或专用凭据管理工具中，不进入 Git、聊天、README 或源码压缩包。

后续更新保持 `applicationId` 为 `cn.activityscore.app`、使用同一签名，并提高 `versionCode`。若已安装的旧包使用调试签名，正式签名包不能直接覆盖它；先在旧包中导出备份，再测试迁移流程，避免为了安装而直接卸载丢失记录。源码当前为 0.3.0（versionCode 3），本次名称与图标整理没有擅自提升版本。

## 上传 GitHub

1. 自己在 GitHub 新建空的 private 仓库，建议仓库名 `zongce-shouji`。不要在远端预建 README 或许可证。
2. 在干净源码目录内初始化新 Git 历史；用 GitHub Desktop 查看每个待提交文件，再提交并发布到刚建的仓库。也可自行使用 Git 命令推送。
3. 上传完成后，检查远端文件列表；决定公开前选择许可证，并确认样例和截图已脱敏。许可证尚未选定，本次未擅自添加。
4. 通过 GitHub Releases 添加版本说明及签名 APK，源码仓库不存安装包。

本地整理不要求给助手 GitHub 权限。需要后续协作时，另外给这个仓库配置限定范围的授权即可。
