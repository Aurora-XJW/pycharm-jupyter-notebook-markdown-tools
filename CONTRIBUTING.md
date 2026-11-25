# Contributing

[简体中文](#简体中文)

## Source layout

| Path | Contents |
| --- | --- |
| `src/main/java/` | Plugin actions, IDE integration, dialogs, and Markdown and image handling |
| `src/main/resources/` | Plugin descriptor, icons, and localized messages |
| `src/test/java/` | Regression tests for Markdown editing, image management, and IDE compatibility |
| `docs/` | Demo notebook, Overview templates and screenshots, and branding credits |
| `tools/` | Overview generation, developer previews, and verifier setup |
| `assets/` | Screenshots used by the two READMEs |

## Build and test on Windows

Install PowerShell 7 and use a local PyCharm 2025.3 distribution with its `product-info.json` and bundled Jupyter, Notebook Files, and Markdown components. The build uses Java 21 bytecode. If the IDE's `jbr` directory lacks `javac.exe`, add `-RuntimeHome` with the path to a JDK 21 installation.

The compile script accepts PyCharm SDK branches `243` through `253`. The packaged plugin's declared compatibility is `243.21565.199 — 263.*`, set by `idea-version` in `src/main/resources/META-INF/plugin.xml`. Run the verifier separately against the PyCharm build you want to check.

```powershell
$ide = 'D:\PyCharm 2025.3'
.\build.ps1 -IdeHome $ide
```

The build compiles the plugin, runs regression tests, and writes the installation ZIP to `dist/<version>/`. To run just the regression tests, use `-TestOnly`. For JetBrains Plugin Verifier, follow the [verifier setup](tools/dependencies/README.md). For a manual check inside PyCharm, open the [demo notebook](docs/overview/demo/功能演示.ipynb) in a local project.

If you change the plugin Overview, follow the [Overview maintenance notes](docs/overview/README.md). After building and verifying the ZIP, run `.\package-release.ps1` to package the source and verification results.

## Submit a change

1. Fork this repository and make your change on a branch.
2. Run the regression tests. For changes involving PyCharm's UI or clipboard, include the build number and any manual checks you ran.
3. Open a pull request that explains the change and how you verified it. Link an existing issue when relevant.

## 简体中文

主源码位于 `src/main/java/`，插件描述与图标在 `src/main/resources/`，回归测试在 `src/test/java/`。`docs/` 保存演示笔记本、截图和简介模板，`tools/` 提供开发工具。

构建需要 Windows、PowerShell 7、本地 PyCharm 2025.3 和 JDK 21。`-IdeHome` 指向含 `product-info.json` 的 PyCharm 目录。如果自带的 `jbr` 没有 `javac.exe`，通过 `-RuntimeHome` 指向 JDK 21。

编译脚本接受 `243` 至 `253` 分支的 PyCharm SDK。安装包声明的兼容范围是 `243.21565.199 — 263.*`，由 `src/main/resources/META-INF/plugin.xml` 中的 `idea-version` 设置。校验特定 PyCharm 构建时，可单独运行验证器。

```powershell
$ide = 'D:\PyCharm 2025.3'
.\build.ps1 -IdeHome $ide
```

ZIP 生成在 `dist/<版本>/`。只跑回归可加 `-TestOnly`。如需运行官方兼容性检查，请先按[验证器说明](tools/dependencies/README.md)准备工具。涉及 PyCharm 界面或剪贴板的改动，可以用[演示笔记本](docs/overview/demo/功能演示.ipynb)实测。

欢迎 Fork 后新建分支提交 PR。请说明改动、测试方式和使用的 PyCharm 构建号，有关联 Issue 时附上链接。
