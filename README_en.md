# IsLanTrue

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17+-blue.svg)](https://www.java.com/)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS%20%7C%20Android-lightgrey.svg)]()
[![Build](https://img.shields.io/github/actions/workflow/status/NetheritePickaxe/IsLanTrue/build.yml)](https://github.com/NetheritePickaxe/IsLanTrue/actions)
[![Release](https://img.shields.io/github/v/release/NetheritePickaxe/IsLanTrue)](https://github.com/NetheritePickaxe/IsLanTrue/releases)

Minecraft LAN authentication bypass tool. Injects into running Minecraft client to force `isOnLAN()` to return `true`.

## Quick Start

```bash
# Auto-inject Minecraft process
java -jar islantrue.jar

# List detectable processes
java -jar islantrue.jar --list

# Specify PID to inject
java -jar islantrue.jar --pid 12345

# JVM agent injection
-javaagent:islantrue.jar

# Download JAR and script (same args)
islantrue
```

> **Android**: Launchers based on PojavLauncher (PojavLauncher, ZalithLauncher2, FCL, etc.)
> run a full HotSpot JVM and support `-javaagent` injection.
> If the launcher supports importing a JDK, Attach API injection also works.

## How It Works

1. Attaches to target JVM via Attach API
2. Registers a ClassFileTransformer using ASM bytecode library
3. Dynamically detects `ServerData`/`ServerInfo` class structures
4. Locates fields and methods via bytecode pattern matching, rewrites constructors to set `lan = true`, replaces getter body with `return true`
5. Retransforms loaded classes — no restart needed

## Build

```bash
# Download ASM 9.7.1
mkdir lib; for dep in asm asm-tree asm-commons; do
  curl -L "https://repo1.maven.org/maven2/org/ow2/asm/${dep}/9.7.1/${dep}-9.7.1.jar" -o "lib/${dep}-9.7.1.jar"
done

# Compile
javac -cp "lib/*" -d out src/main/java/cn/pickaxe/islantrue/IsLanTrue.java

# Package fat JAR
mkdir -p deps; for jar in lib/*.jar; do unzip -o -q "$jar" -d deps; done
rm -rf deps/META-INF/MANIFEST.MF deps/META-INF/*.SF deps/META-INF/*.RSA deps/META-INF/*.DSA
jar cfm islantrue.jar src/main/resources/META-INF/MANIFEST.MF -C out . -C deps .
rm -rf out lib deps
```

## Disclaimer
This tool only modifies the client-side `isOnLAN()` return value and does not tamper with server-side data. For integrated server use only.
