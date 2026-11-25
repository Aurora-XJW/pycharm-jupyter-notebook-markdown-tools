# Overview 图文内容

## 截图对应关系

下面七张 PNG 来自 Xu.J.W 2026 年 11 月 19 日在英文版 PyCharm 中截取的界面。工程将完整原图保存在 `assets/full/`，只按功能重命名，文件内容与桌面原图一致。

| 图片编号 | Overview 章节 | 原文件 | 工程文件 |
| --- | --- | --- | --- |
| 1 | Paste and manage images | `2026-11-19_231849.png` | `image-paste.png` |
| 2 | Text color：浮动工具栏 | `2026-11-19_232130.png` | `text-color-toolbar.png` |
| 3 | Text color：调色窗口 | `2026-11-19_232153.png` | `text-color.png` |
| 4 | Text color：生成的 HTML 标记 | `2026-11-19_232203.png` | `text-color-markup.png` |
| 5 | Visible blank lines：双击 Alt 的插入菜单 | `2026-11-19_232237.png` | `insert-menu.png` |
| 6 | Visible blank lines：空行数量 | `2026-11-19_232246.png` | `blank-lines.png` |
| 7 | Visual table editor | `2026-11-19_232334.png` | `markdown-table.png` |

表格下方的 Markdown 示例取自图 7 的代码预览。介绍按英文、中文顺序排版，七张配图放在英文部分对应的功能小节中。

## 生成和预览

正文源稿在 `description.html.in`。使用 Python 和 `tools/requirements-overview.txt` 中的 Pillow 依赖运行：

```powershell
python tools/update_overview.py
python tools/update_overview.py --check
```

生成器按截图中的控件位置裁取显示区域，输出宽度不超过 560 像素的 PNG 到 `assets/`。插件主 JAR 的 `META-INF/plugin.xml` 在 `<description>` 中内嵌这七张图。原始 PNG 完整嵌在离线图文页中，每张图下可点 **Original screenshot** 展开。本版简介为 59,130 个 UTF-16 字符，低于验证器的 65,535 字符上限。

`tools/ValidateOverviewRendering.java` 使用 PyCharm 2025.3 SDK 的 Overview 组件检查图片加载、章节顺序及尺寸，报告位于发行目录 `reports/`。图片加载协议及 2024.3 源码依据见 `offline-support-evidence.md`。
