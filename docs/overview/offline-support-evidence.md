# Overview 离线图片验证

## 当前截图

PyCharm 2025.3 原生插件详情组件可以显示 `META-INF/plugin.xml` 的 `<description>` 中内嵌的 PNG data URL。验证使用 `PluginDetailsPageComponent.Companion.createDescriptionComponent(Consumer<View>)` 工厂、`JBHtmlEditorKit` 和 `HiDpiScalingImageView`，文档 base URL 为空。

当前介绍内嵌七张英文实拍截图，描述长 59,130 个 UTF-16 字符。标准及双倍绘制、功能位置和描述文件 SHA-256 分别记在发行目录的 `reports/overview-render-report.json` 和 `reports/overview-render-report-2x.json`。配图保存在 `previews/overview/`，完整截图在其中的 `full/`。前版离屏配图渲染报告保存在 `reports/baseline-b706a9e30070/`。

## 最低兼容版本依据

JetBrains 官方 `intellij-community` 的 `243.21565` 分支已经提供 PNG base64 支持：

1. [PluginDetailsPageComponent.kt](https://github.com/JetBrains/intellij-community/blob/243.21565/platform/platform-impl/src/com/intellij/ide/plugins/newui/PluginDetailsPageComponent.kt) 使用同样的 `HTMLEditorKitBuilder` 说明组件工厂。
2. [ExtendableHTMLViewFactory.kt](https://github.com/JetBrains/intellij-community/blob/243.21565/platform/util/ui/src/com/intellij/util/ui/ExtendableHTMLViewFactory.kt) 的默认扩展包含 `HIDPI_IMAGES`，注释给出了 PNG data URI 用法。
3. [HiDpiScalingImageView.kt](https://github.com/JetBrains/intellij-community/blob/243.21565/platform/util/ui/src/com/intellij/util/ui/html/HiDpiScalingImageView.kt) 继承 `Base64ImageView`。
4. [Base64ImageView.kt](https://github.com/JetBrains/intellij-community/blob/243.21565/platform/util/ui/src/com/intellij/util/ui/html/Base64ImageView.kt) 通过 `JBImageToolkit.tryBuildBase64Url` 解析图片地址。

后两个文件在此分支上的修改日期为 2024-10-03，早于 PyCharm 2024.3 发布。该版本的类名是 `Base64ImageView`，新版本才使用 `DataUrlImageView`；名称变化不影响 PNG base64 的支持结论。最低版本的此项依据是官方源码链，没有启动旧版 IDE 进行图形界面测试。

## 复现方法

验证工具为 `tools/ValidateOverviewRendering.java`，使用相应 SDK 的 `lib/*.jar` 编译与运行。参数依次为 `plugin.xml` 路径、工作目录内的输出目录，以及可选的绘制倍数 `1` 或 `2`。所需 JBR 模块访问参数列在 Java 文件开头；`idea.system.path` 与 `idea.config.path` 应指定到工作目录内的临时路径。

工具直接调用 SDK 的真实 Overview 工厂，仅以最小 `Application` 代理提供工厂所需的 `HelpIdAwareLinkListener` 服务。等待图片加载完成后，核对七张图的数据、绘制尺寸以及各图在四个功能小节中的顺序，再保存 JSON 报告与 PNG 预览。整个过程不启动 IDE，不替换已安装插件。

## 显示限制

Overview 工厂对所有段落强制设置 0.3 的行距。图片作为段落的一行时，也会产生约图片高度 30% 的额外行距。移除 `height`、使用 `display:block` 或表格包装均未消除这部分间距，因此保留普通图片段落及显式宽高。

插件简介采用与逻辑显示宽高一致的配图尺寸，可避免在标准缩放下二次缩放图片。离线页提供七张未重新编码的用户原图。IDE 的主题、字体和缩放比例会影响页面整体观感。
