# hello-java

发布验证用的最小 Gradle 工程（v0.1.0 冒烟 S2 / S5 / S7 使用）。

```bash
# 在工作区选择本目录后：
#   编译 → F7
#   运行 → F5
```

首次构建会下载 Gradle 依赖；如离线，先在本目录执行一次 `./gradlew run` 预热 `~/.gradle`。

> 本目录暂未提交 Gradle Wrapper。生成方式：`gradle wrapper --gradle-version 8.12`，
> 或直接使用 `docs/03-发布验证.md` 中的准备步骤。
