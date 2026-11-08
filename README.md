# ScreenTranslate · 屏幕翻译

> 测海外版 App 的时候，界面全英文，来回切出去查词很断节奏。
> 这个工具把屏幕上的英文直接识别、翻成中文，原位盖回去，随时能切回原文对照。

## 功能

- **悬浮球一键翻译**：点一下翻译当前屏幕，再点清除译文
- **原位覆盖**：译文卡片按 OCR 坐标贴合原文位置，不遮别的内容
- **译文 ⇄ 原文**：双击悬浮球全局切换；对照模式下单张卡片可单独切换
- **自动模式**：画面变化才重新翻译（低频比对帧差，静止时不重译）
- **防自捕获**：截屏前先隐藏自己的悬浮窗，不会把自己翻译进去
- **双引擎**：设备端离线（ML Kit，英→中）／百度翻译云端，可切换
- **术语表**：预置约 170 条专业词条，支持导入导出 JSON
- **前台服务**：通知栏可切换原文 / 自动 / 停止

## 技术栈

| 层 | 选型 |
|---|---|
| 截屏 | MediaProjection + VirtualDisplay + ImageReader |
| OCR | ML Kit Text Recognition v2（Latin，离线模型） |
| 翻译 | ML Kit 端侧翻译 / 百度翻译 API |
| 覆盖 | WindowManager TYPE_APPLICATION_OVERLAY 悬浮窗 |
| 语言 | Kotlin，minSdk 29 / targetSdk 34 |

## 构建

- **方式一**：Windows 双击 `build-now.bat`，脚本自动下载 JDK 17 / SDK / Gradle 并编译（首次约 1–1.5GB）
- **方式二**：Android Studio 打开本目录，Gradle Sync 后 Run

## 说明

个人自用工具，装了 APK 直接用，不上架商店。仓库内不含任何公司代码、客户数据或内部接口。
