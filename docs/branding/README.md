# 插件图标

图标以橙色轨道表达 Jupyter，以 M↓ 表达 Markdown，适用于浅色和深色主题。

正式资源在 `src/main/resources/META-INF/`：

- `pluginIcon.svg`：默认及浅色主题。
- `pluginIcon_dark.svg`：深色主题。

两份资源使用40×40画布，均为纯SVG，无字体或外部资源。当前目录的 `plugin-icon-preview.png` 为 JetBrains SVGLoader 渲染的40/80像素对照。

预览工具为 `tools/RenderPluginIcon.java`。更新图标后，需要重新打包、安装并完整重启 IDE；只关闭设置窗口不会清除图标缓存。

中央 Markdown 标记参考 [Dustin Curtis 的 Markdown Mark](https://github.com/dcurtis/markdown-mark)，采用 [CC0](https://github.com/dcurtis/markdown-mark/blob/master/LICENSE) 许可，保留标记比例。橙色轨道为本插件绘制。

打包位置与尺寸遵循 [JetBrains 插件图标规范](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html)。
