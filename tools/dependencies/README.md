# Plugin Verifier

`verifier-cli-1.410-all.jar` 是 JetBrains 官方 IntelliJ Plugin Verifier，用于离线静态兼容性检查。它不是插件运行所需文件，不进入安装 ZIP 或源码 ZIP。

官方来源：https://github.com/JetBrains/intellij-plugin-verifier/releases/tag/1.410

将官方的 `verifier-cli-1.410-all.jar` 下载到本目录后，可运行：

```powershell
.\verify.ps1 -IdeHome 'D:\PyCharm 2025.3'
```

也可通过 `-VerifierJar` 指定其他位置。该工具只在独立 Java 进程中读取安装包和 SDK，不启动 PyCharm 或安装插件。

验证 2026.3 系列时，可将 `-IdeHome` 指向对应的 PyCharm 安装目录。若该目录没有可用的 Java 运行时，可通过 `-RuntimeHome` 指定 JDK 21。插件的安装兼容范围见 [README](../../README.zh-CN.md#兼容范围)。

本工作区所保留文件的 SHA-256：

59a5ef05cbdf0584cbfd6cb6ca802c74ecf340fdeadc5a73eac24af622c22010
