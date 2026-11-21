# Jupyter Notebook Markdown Tools

[English](README.md) | **简体中文**

在 PyCharm 的本地 Jupyter Notebook 中粘贴图片、编辑 Markdown 格式。

**版本 0.1.0** · **开发者 Xu.J.W**

## 功能

- **粘贴图片。** 支持剪贴板图片或单个 PNG、JPEG、BMP 文件。插件会在项目内按顺序保存为 PNG，插入相对路径引用。你可以调整展示尺寸，保存的图片保留原始分辨率。

  ![保存图片窗口中的项目目录、文件名和展示尺寸](assets/image-paste.png)

- **修改文字颜色。** 在 Markdown 单元选中文字，从浮动工具栏或右键菜单选择 **Text Color…**。

  ![Markdown 浮动工具栏中的 Text Color 按钮](assets/text-color-toolbar.png)

  ![文字颜色选择窗口与预设色块](assets/text-color.png)

  ![插入 Markdown 单元的 HTML 颜色标记](assets/text-color-markup.png)

- **插入预览可见的空行。** 在空白 Markdown 行快速按下并松开 `Alt` 两次，再选择 **Insert Blank Lines…**。

  ![双击 Alt 打开的 Markdown 插入菜单](assets/insert-menu.png)

  ![设置可见空行数量的窗口](assets/blank-lines.png)

- **制作 Markdown 表格。** 在同一个菜单中选择 **Add Table…**。可以直接编辑网格或粘贴 Excel、TSV 数据，再插入生成的表格。

  ![带有可编辑网格的 Markdown 表格窗口](assets/markdown-table.png)

- **恢复已回收的图片。** 自动清理可在保存后回收由插件创建、未被修改且无引用的图片，需要时可从插件回收区恢复。

## 兼容范围

适用于 Windows 上的本地 Notebook 项目，支持 PyCharm **2024.3**（最低构建 `243.21565.199`）至 **2025.3**。请在 PyCharm 中启用 **Jupyter**、**Notebook Files** 和 **Markdown**。

## 安装

1. 打开本仓库的 **Releases**，在 **Assets** 中下载 `pycharm-jupyter-notebook-markdown-tools-0.1.0.zip`。
2. 在 PyCharm 中进入 **Settings → Plugins**，点击齿轮，选择 **Install Plugin from Disk…**。
3. 直接选择 ZIP 文件，安装后重启 PyCharm。打开本地 `.ipynb` 的 Markdown 单元即可开始使用。

项目设置位于 **Settings → Tools → Jupyter Notebook Markdown Tools**。手动清理、恢复和附件导出位于 **Tools → Jupyter Notebook Markdown Tools**。

## 反馈

欢迎在本仓库的 Issues 提交问题。请附上 PyCharm 构建号、Windows 版本和复现步骤。
