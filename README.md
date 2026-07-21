# IsLanTrue

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17+-blue.svg)](https://www.java.com/)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS%20%7C%20Android-lightgrey.svg)]()
[![Build](https://img.shields.io/github/actions/workflow/status/NetheritePickaxe/IsLanTrue/build.yml)](https://github.com/NetheritePickaxe/IsLanTrue/actions)
[![Release](https://img.shields.io/github/v/release/NetheritePickaxe/IsLanTrue)](https://github.com/NetheritePickaxe/IsLanTrue/releases)

Minecraft 局域网认证绕过工具。注入正在运行的 Minecraft 客户端，强制 `isOnLAN()` 返回 `true`。

## 快速开始

```bash
# 自动注入 Minecraft 进程
java -jar islantrue.jar

# 列出可检测进程
java -jar islantrue.jar --list

# 指定 PID 注入
java -jar islantrue.jar --pid 12345

# 启动时附带 agent
java -javaagent:islantrue.jar -jar minecraft_server.jar

# 同时下载jar和脚本（传参同上）
islantrue
```

> **Android**: 基于 PojavLauncher 后端的启动器（如 PojavLauncher、ZalithLauncher2、FCL 等）
> 运行完整 HotSpot JVM，可直接使用 `java -jar islantrue.jar` 或 `-javaagent` 注入。
> 若启动器支持导入 JDK，同样支持 Attach API 注入。

## 工作原理

1. 通过 Attach API 连接到目标 JVM
2. 注册 ClassFileTransformer，基于 ASM 字节码操作
3. 动态检测 `ServerData`/`ServerInfo` 类结构
4. 重写构造函数设置 `lan = true`，替换 getter 为 `return true`
5. Retransform 已加载的类 — 无需重启

## 构建

```bash
# 下载 ASM 9.7.1
mkdir lib; for dep in asm asm-tree asm-commons; do
  curl -L "https://repo1.maven.org/maven2/org/ow2/asm/${dep}/9.7.1/${dep}-9.7.1.jar" -o "lib/${dep}-9.7.1.jar"
done

# 编译
javac -cp "lib/*" -d out src/main/java/cn/pickaxe/islantrue/IsLanTrue.java

# 打包 fat JAR
mkdir -p deps; for jar in lib/*.jar; do unzip -o -q "$jar" -d deps; done
rm -rf deps/META-INF/MANIFEST.MF deps/META-INF/*.SF deps/META-INF/*.RSA deps/META-INF/*.DSA
jar cfm islantrue.jar src/main/resources/META-INF/MANIFEST.MF -C out . -C deps .
rm -rf out lib deps
```
