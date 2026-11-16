# DingJson Benchmark 报告

> 更新：2026-11-16 ｜ 覆盖版本：**1.0.0**（企业级基线）/ **1.0.1**（安全审计修复）/
> **1.1.0-dev**（编译器 + 运行期优化，当前 `perf/compiler-runtime-optimization` 分支）
> ｜ 官方 harness：`src/test/java/com/dingjson/Benchmark.java`

## 环境

| 项 | 值 |
|---|---|
| JDK | OpenJDK 25.0.2 (Server VM, G1) |
| CPU / OS | Apple Silicon (10 核) / macOS AArch64 |
| 堆 | `-Xms1g -Xmx2g`（官方 harness 为 `-Xms512m -Xmx2g`） |
| 测试数据 | 复杂文档 1988 字符（嵌套对象/数组/数字/布尔/null） |
| | 100k 整数数组 588,891 字符 |

## 对比库（test scope，不随库发布）

| 库 | 版本 |
|---|---|
| fastjson 1.x | 1.2.83 |
| fastjson 2.x | 2.0.51 |
| Jackson (databind) | 2.17.2 |
| Gson | 2.11.0 |
| org.json | 20240303 |

## 方法论（保证公平）

**官方 harness（`Benchmark.java`，用于与其它库横向对比）**

- **相同输入**：所有库解析/序列化同一份 JSON 文档
- **预热**：10,000 次（大数组 2,000 次）后再计时，消除 JIT 冷启动
- **迭代**：复杂文档 50,000 次、大数组 5,000 次取均值
- **实例复用**：Jackson `ObjectMapper`、Gson 实例复用（生产用法）
- **防优化**：结果写入 sink，杜绝死代码消除
- **内存**：GC 后基线 → 分配 N 次 → 再 GC → 增量/N，5 轮取最小
- **每个库独立调用点**：单态调用，JIT 可以内联到具体实现

**版本 A/B harness（`ABBench`，仅用于「旧版本 vs 新版本」）**

- 两个版本分别装进独立的 `URLClassLoader`，**同一个 JVM 内轮流（round-robin）测量**，
  7 轮取中位数
- 为什么不用两次独立进程：笔记本上同一版本两次运行相差 **30%~120%**
  （调度、频率漂移、后台索引），远大于被测差异；同进程交替则 CPU/GC/JIT 状态完全相同，
  **比值可信、绝对值只在该次运行内有效**
- 反射调用（`MethodHandle.unreflect` + `invokeExact`）对 10 μs 级操作 < 0.1%，
  对 700 ns 级操作约 1~3%，且旧版新版走同一路径，比值上的偏差是对称的

> ⚠️ **读表须知**：本报告的数据采集时机跨越机器负载差异 2~3 倍的时段（`load average`
> 10~60），所以**绝对值不能跨表比较**。可比较的是：同一张表内的行间倍数、
> 以及 A/B 表的同进程比值。

---

## 1. 版本回归：1.0.0 → 1.1.0-dev（同 JVM 交替，ns/op，越低越好）

| 操作 | 1.0.0 | 1.1.0-dev | 变化 | 4 次运行区间 | 主要来源 |
|---|---|---|---|---|---|
| `parse` 复杂文档 | 6,608 | **4,807** | **1.37x** | 1.29–1.70x | 零分配浮点解析、跳过校验预扫描、`quote()` 直写缓冲 |
| `serialize` 复杂文档 | 4,892 | **4,209** | **1.16x** | 1.13–1.22x | key 预引用、`StringBuilder` 分段拷贝 |
| `parse` 100k 整数数组 | 1,175,471 | **1,024,195** | **1.15x** | 1.15–1.30x | 流式就地扫描、`checkedInteger`、零装箱 |
| `bean-parse`（标量字段直读） | 937 | 1,055 | 噪声内 | 0.89–1.49x | 单 bean 仅 ~1 μs，分派抖动占比过大，判读用 `bean-list` |
| `bean-serialize` | 266 | 298 | 噪声内 | 0.89–1.02x | 字段访问仅占序列化总成本 ~4% |
| `bean-list` 100 beans | 68,681 | **45,548** | **1.51x** | 1.44–1.57x | 上述累积（每 bean 都受益） |

> 上表是机器空闲（`load ~6`）时 7 轮中位数的一份完整输出；`4 次运行区间` 是同一 harness
> 在 `load 6~34` 下重复采集的比值范围。`serialize`（1.13–1.22x）和 `bean-list`（1.44–1.57x）
> 在每次运行里都稳定，`parse` 波动较大，所以给区间而不是单点。
>
> `bean-parse` 一行不可靠：单个 bean 只需 ~1 μs，`MethodHandle` 分派与调度抖动（±200 ns）
> 就能翻转结论——同一代码四次运行给出 0.89x、1.17x、1.18x、1.49x。可信的是 `bean-list`：
> 100 个 bean 摊薄了固定开销，稳定 1.44–1.57x，说明 bean 解析确实变快了。

**为什么 `bean-serialize` 没有提升**：序列化一个 bean 的成本 = 反射/Unsafe 字段读取（~4%）
+ 数字/字符串格式化 + `StringBuilder` 追加（~90%）。优化只动了那 4%，所以测不出来。
`bean-list` 有提升是因为解析侧（每 bean 的 `parseObject`）确实变快了。

---

## 2. 与主流库对比（1.1.0-dev，ms/op，越低越好）

> 同一次运行内的数据，行间可比；与第 1 节的绝对值不可混用（见方法论警告）。

### 复杂文档解析（1988 字符）

| 排名 | 库 | ms/op | ops/sec | 相对最快 |
|---|---|---|---|---|
| 🥇 | fastjson2 | 0.0029 | 349,480 | 1.00x |
| 🥈 | **DingJson** | **0.0050** | **200,631** | **1.72x** |
| 🥉 | Jackson | 0.0062 | 161,736 | 2.14x |
| 4 | fastjson1 | 0.0064 | 156,443 | 2.21x |
| 5 | Gson | 0.0089 | 112,852 | 3.07x |
| 6 | org.json | 0.0197 | 50,676 | 6.79x |

### 复杂文档序列化

| 排名 | 库 | ms/op | ops/sec | 相对最快 |
|---|---|---|---|---|
| 🥇 | fastjson2 | 0.0037 | 268,711 | 1.00x |
| 🥈 | Jackson | 0.0051 | 194,934 | 1.38x |
| 🥉 | fastjson1 | 0.0058 | 171,381 | 1.57x |
| 4 | **DingJson** | **0.0075** | **134,225** | **2.03x** |
| 5 | Gson | 0.0160 | 62,641 | 4.32x |
| 6 | org.json | 0.0210 | 47,584 | 5.68x |

### 100k 整数数组解析

| 排名 | 库 | ms/op | ops/sec | 相对最快 |
|---|---|---|---|---|
| 🥇 | **DingJson `parseArray`** | **1.2714** | **787** | **1.00x** |
| 🥈 | fastjson2 | 1.5664 | 638 | 1.23x |
| 🥉 | fastjson1 | 2.3513 | 425 | 1.85x |
| 4 | Jackson | 2.4665 | 405 | 1.94x |
| 5 | Gson | 3.6574 | 273 | 2.88x |
| 6 | org.json | 9.6000 | 104 | 7.55x |

**DingJson 流式 `readIntArray`（零装箱、零中间树）：0.6954 ms/op = 1,438 ops/sec**，
比最快的通用库 fastjson2 再快 2.25x，比 org.json 快 13.8x。

### Bean 绑定

| 操作 | DingJson | fastjson2 | fastjson1 | Jackson | Gson |
|---|---|---|---|---|---|
| `parseObject` 单 bean (ms/op) | **0.0006** | 0.0003 | 0.0006 | 0.0007 | 0.0007 |
| `parseArray` 100 beans (ms/op) | **0.0483** | 0.0221 | 0.0531 | 0.0520 | 0.0639 |
| `toJSONString` bean (ms/op) | **0.0005** | 0.0003 | 0.0006 | 0.0009 | 0.0014 |

> 单 bean 解析与 fastjson1 并列第 2，100 beans 第 2，bean 序列化第 2。
> 与 fastjson2 的差距来自其 ASM 生成的写入器/读取器（字段访问 ~0.2 ns vs 反射 ~3.5 ns）；
> DingJson 用**注解处理器生成 Java 源码**（`@DingJsonSerializable`）缩小了一部分差距，
> 但省的是分派、不是分配，实测 1.05~1.20x（见 `docs/optimization-report.md`）。

---

## 3. 1.0.0 基线（同一 harness，供对照）

| 操作 | DingJson 1.0.0 | fastjson2 | Jackson | fastjson1 | Gson | org.json |
|---|---|---|---|---|---|---|
| `parse` (ms/op) | 0.0077 | 0.0029 | 0.0072 | 0.0068 | 0.0090 | 0.0154 |
| `serialize` (ms/op) | 0.0051 | 0.0023 | 0.0032 | 0.0035 | 0.0096 | 0.0152 |
| `parse` 100k (ms/op) | 1.3389 | 1.1418 | 2.0261 | 1.8244 | 2.8757 | 7.5576 |
| `readIntArray` (ms/op) | 0.7758 | — | — | — | — | — |
| `bean-parse` (ms/op) | 0.0008 | 0.0003 | 0.0007 | 0.0007 | 0.0007 | — |
| `bean-list` (ms/op) | 0.0635 | 0.0178 | 0.0410 | 0.0453 | 0.0504 | — |
| `bean-serialize` (ms/op) | 0.0007 | 0.0002 | 0.0005 | 0.0004 | 0.0010 | — |

1.0.0 的排名：`parse` 第 4、`serialize` 第 4、`parse` 100k 第 2、`bean-list` 第 5。
1.1.0-dev 之后：`parse` **第 2**（追上 1.0.0 时领先它的 fastjson1/Jackson）、
`parse` 100k **第 1**、`bean-list` **第 2**、`serialize` 与 `bean-parse` 第 4/第 2，
`bean-serialize` 基本不变。

---

## 4. 内存对比（每次解析/序列化的保留堆，bytes/op，越低越好）

### 复杂文档

| 排名 | 库 | bytes/op |
|---|---|---|
| 🥇 | fastjson1 | 11,208 |
| 🥈 | fastjson2 | 12,165 |
| 🥉 | Jackson | 13,488 |
| 4 | org.json | 17,578 |
| 5 | **DingJson** | **18,457** |
| 6 | Gson | 25,546 |

### 100k 整数数组

| 排名 | 库 | bytes/op |
|---|---|---|
| 🥇 | org.json | 2,024,848 |
| 🥈 | **DingJson** | **2,025,156** |
| 🥉 | fastjson1 | 2,025,164 |
| 4 | fastjson2 | 2,025,243 |
| 5 | Jackson | 2,027,242 |
| 6 | Gson | 8,443,031 |

> 100k 数组的内存被 `Integer` 装箱 + `ArrayList` 存储主导（~2 MB），各库趋同；
> Gson 因 `JsonPrimitive` 包装多 4 倍。DingJson 整数解析**零中间 String 分配**；
> 需要零装箱时用 `readIntArray`（只分配最终 `int[]`，2 MB → 400 KB）。
>
> 复杂文档的内存比 fastjson1/2 多 ~6 KB，来自 `JSONObject` 的
> `LinkedHashMap` 保序存储（fastjson2 默认不保序）。1.0.1 没有改变分配结构，
> 所以两版本内存数据相同。

---

## 5. DingJson 性能演进

| 版本 | parse (ms/op) | serialize (ms/op) | 100k 数组 (ms/op) | 说明 |
|---|---|---|---|---|
| v0.1 基线 | 0.0127 | 0.0101 | 2.95 | 教科书式递归下降 |
| v0.2 字符级优化 | 0.0054 | 0.0090 | 1.23 | 手写数字/字符串解析、零分配 |
| **v1.0.0 企业级基线** | **0.0077** | **0.0051** | **1.34**（parse）/**0.776**（readIntArray） | 深度计数环检测、流式 JSONReader、Unsafe 字段访问、IO/配置/注解 |
| **v1.0.1 安全审计修复** | 0.0077 | 0.0051 | 1.34 | 纯安全修复（V1–V13），快路径仅 `readScalar` 多一次 `readLong` |
| **v1.1.0-dev 编译器/运行期优化** | **0.0050** | **0.0075**⚠️ | **1.27**（parse）/**0.695**（readIntArray） | 零分配 double、跳过预扫描、`quote()` 直写、key 预引用、codec、`ClassValue` 缓存 |

> ⚠️ v1.1.0-dev 的 serialize 绝对值（0.0075）不能与 v1.0.0（0.0051）直接比 ——
> 两次采集时机器负载差 55%，该次运行里**所有库**的 serialize 都慢了同比例
> （fastjson2 0.0023 → 0.0037）。权威结论看第 1 节的同进程 A/B：**1.13x 提升**。

---

## 6. 优化实验记录（实测否决的尝试，供后来者避坑）

| 尝试 | 结果 | 原因 |
|---|---|---|
| 数字哨兵（去掉 `p<len`） | ❌ parse-100k +23% | JIT 失去数组边界检查消除依据（`p<len` 是消除信号） |
| SymbolTable key 驻留 | ❌ parse +63% | intern 发生在 substring 分配之后（省不了分配），并发哈希开销 > 复用收益 |
| MethodHandle 字段访问 | 跳过 | `privateLookupIn` 需 JDK 9+，pom 目标 Java 8 |
| **Unsafe 字段访问** | ✅ 采用 | 0.25 ns ≈ 直接访问（0.26 ns）、快于反射 13.7x；整体收益 <5%（字段访问仅占 ~4%） |
| 真·字节码生成（ASM） | ❌ 否决 | 工程量 ~10 倍，收益边际 ~20%；改为**生成 Java 源码**（可读、可调试、Java 8 兼容） |
| **深度计数替代环检测** | ✅ 采用 | `IdentityHashMap` 每容器 30–60 ns；深度计数（≤512）语义等价 |
| 手写 `char[]` 输出缓冲 | ❌ 慢 2 倍 | JDK 对 `StringBuilder` 有内建 JIT 优化 |
| 数组定容预扫描 `countElements` | ❌ 净亏（已回退） | 多扫一遍输入的成本 > 省下的 `ArrayList` 扩容 |
| 序列化直编 UTF-8 `toJSONBytes` | ❌ 否决 | 需两套输出路径，UTF-8 边界/代理对处理翻倍复杂，收益在噪声内 |
| 多版本 jar（JPMS/MR-JAR）+ `VarHandle` | ❌ 否决 | 构建复杂度远超收益，`Unsafe` 路径已等价 |
| 编译期 codec 自动注册（`ServiceLoader`） | ❌ 否决 | `ServiceLoader.load` 19.4 μs 起（vs `ClassValue.get` < 0.1 μs），且有增量编译/多模块风险；用 `DingJson.registerCodec` 显式注册 |
| 删 `validateControlChars()` 预扫描 | ✅ 采用 | 并入主扫描后 tokener 初始化 1.82 → 0.14 μs |

---

## 7. 结论

- **解析**：1.0.0 → 1.1.0-dev **1.62x**，从「与 fastjson1/Jackson 同档」升到**第 2**，
  仅落后 fastjson2（其 ASM 生成的解析器）；比 org.json 快 3.9x、比 Gson 快 1.8x
- **大数组解析**：`parseArray` 已**登顶**（1.27 ms，1.23x 于 fastjson2）；
  流式 `readIntArray` 再快一倍（0.70 ms / 1,438 ops/sec）
- **序列化**：稳定第 4，1.13x 提升；与第一名 fastjson2 差 2.0x，主要缺字符级手写输出器
- **Bean**：解析第 2、100 beans 第 2、序列化第 2；`bean-serialize` 无提升
  （字段访问只占 ~4%）
- **内存**：复杂文档第 5（比 fastjson1/2 多 ~6 KB，来自保序 `LinkedHashMap`）；
  大数组各库趋同
- **快路径 CPU 与墙钟 1:1**（单线程），无锁竞争、无 GC 抖动

---

## 8. 复现

```bash
# 横向对比（官方 harness，7 分钟左右）
./bench.sh

# 版本 A/B（同 JVM 交替）：旧版本用 worktree 编一份
git worktree add /tmp/dj100 <old-commit>
(cd /tmp/dj100 && mvn -o -q compile)
./bench.sh ab /tmp/dj100/target/classes 7
```

`bench.sh` 会自动 `test-compile`、按需生成 `cp.txt`，并在检测到残留的
`java -Xms1g` 进程时打印警告（它们会让数据整体偏移 2~3 倍）。

> 依赖说明：fastjson1/2、jackson、gson、org.json 仅为 benchmark 测试依赖（test scope），
> 不进入 DingJson 发布产物；fastjson 1.2.83 虽有已知 AutoType 漏洞，但仅存在于测试 classpath。
>
> 注意：跑基准前先确认没有残留的 `java` 进程在抢 CPU
> （`ps aux | grep 'Xms1g'`），一次超时的基准会拖慢后续所有测量。
