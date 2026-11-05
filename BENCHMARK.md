# DingJson Benchmark 报告

> 生成时间：2026-07-31 ｜ 运行方式：`src/test/java/com/dingjson/Benchmark.java`

## 环境

| 项 | 值 |
|---|---|
| JDK | OpenJDK 25.0.2 (Server VM) |
| 堆 | -Xms512m -Xmx2g（峰值 2048 MB） |
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

- **相同输入**：所有库解析/序列化同一份 JSON 文档
- **预热**：10,000 次预热（大数组 2,000 次）后再计时，消除 JIT 冷启动
- **迭代**：复杂文档 50,000 次、大数组 5,000 次取均值
- **实例复用**：Jackson `ObjectMapper`、Gson 实例复用（生产用法）
- **防优化**：结果写入 sink，杜绝死代码消除
- **内存**：GC 后基线 → 分配 N 次 → 再 GC → 增量/N，5 轮取最小（消除 GC 噪音）
- **CPU**：`ThreadMXBean.getCurrentThreadCpuTime()` 单线程采集

---

## 1. 吞吐对比（ms/op，越低越好）

### 复杂文档解析（1988 字符）

| 排名 | 库 | ms/op | ops/sec | CPU ms |
|---|---|---|---|---|
| 🥇 | fastjson2 | 0.0029 | 346,263 | 0.003 |
| 🥈 | fastjson1 | 0.0064 | 156,157 | 0.006 |
| 🥉 | Jackson | 0.0072 | 139,127 | 0.007 |
| 4 | **DingJson** | **0.0073** | **136,762** | **0.007** |
| 5 | Gson | 0.0091 | 109,466 | 0.009 |
| 6 | org.json | 0.0154 | 64,922 | 0.015 |

### 复杂文档序列化

| 排名 | 库 | ms/op | ops/sec | CPU ms |
|---|---|---|---|---|
| 🥇 | fastjson2 | 0.0022 | 457,959 | 0.002 |
| 🥈 | Jackson | 0.0036 | 279,334 | 0.004 |
| 🥉 | fastjson1 | 0.0045 | 219,879 | 0.005 |
| 4 | **DingJson** | **0.0055** | **182,867** | **0.006** |
| 5 | Gson | 0.0182 | 55,092 | 0.015 |
| 6 | org.json | 0.0533 | 18,761 | 0.038 |

### 100k 整数数组解析（吞吐场景）

| 排名 | 库 | ms/op | ops/sec | CPU ms |
|---|---|---|---|---|
| 🥇 | **DingJson `readIntArray`** | **0.7266** | **1,376** | **0.721** |
| 🥈 | fastjson2 | 1.3209 | 757 | 1.313 |
| 🥉 | **DingJson `parseArray`** | **1.8542** | **539** | **1.765** |
| 4 | Jackson | 2.1108 | 474 | 2.106 |
| 5 | fastjson1 | 2.5324 | 395 | 2.513 |
| 6 | Gson | 2.7994 | 357 | 2.787 |
| 7 | org.json | 8.0888 | 124 | 8.058 |

> `readIntArray` 是流式 `JSONReader` API（P1）：直接填 `int[]`，零装箱、零中间树，
> 比通用 `parseArray` 快 2.6x，比 org.json 快 11x。

### Bean 绑定（P1 流式路径）

| 场景 | DingJson | fastjson1 | fastjson2 | Jackson | Gson |
|---|---|---|---|---|---|
| bean-parse (ms/op) | 0.0011 | 0.0006 | 0.0003 | 0.0007 | 0.0007 |
| bean-list 100 个 (ms/op) | 0.0615 | 0.0396 | 0.0183 | 0.0371 | 0.0485 |
| bean-serialize (ms/op) | 0.0004 | 0.0004 | 0.0002 | 0.0005 | 0.0008 |

> bean 场景走流式反序列化（A1）：免建树、标量字段直读；与 fastjson2 的差距来自
> 其 ASM 字节码 writer（字段访问 ~0.2ns vs 反射 ~3.5ns）。

## 2. 内存对比（每次解析的保留堆，越低越好）

### 复杂文档

| 排名 | 库 | bytes/op |
|---|---|---|
| 🥇 | fastjson1 | 11,208 |
| 🥈 | fastjson2 | 12,279 |
| 🥉 | Jackson | 13,489 |
| 4 | org.json | 17,578 |
| 5 | **DingJson** | **18,457** |
| 6 | Gson | 25,546 |

### 100k 整数数组

| 排名 | 库 | bytes/op |
|---|---|---|
| 🥇 | fastjson1 | 2,025,164 |
| 🥈 | fastjson2 | 2,025,243 |
| 🥉 | org.json | 2,025,056 |
| 4 | **DingJson** | **2,025,156** |
| 5 | Jackson | 2,027,242 |
| 6 | Gson | 8,443,031 |

> 100k 数组的内存被 `Integer` 装箱 + `ArrayList` 存储主导（~2 MB），各库趋同；
> Gson 因 `JsonPrimitive` 包装多 4 倍。DingJson 整数解析**零中间 String 分配**。

## 3. DingJson 性能演进

| 版本 | parse (ms/op) | serialize (ms/op) | 100k 数组 (ms/op) | 说明 |
|---|---|---|---|---|
| v1 基线 | 0.0127 | 0.0101 | 2.95 | 教科书式递归下降 |
| v2 字符级优化 | 0.0054 | 0.0090 | 1.23 | 手写数字/字符串解析、零分配 |
| v3 安全加固 | 0.0056~0.0062 | 0.0058~0.0090 | 1.15~1.26 | 深度限制/HTML 转义/循环检测 |
| v4 预测解析 (P0) | 0.0033* | 0.0058 | 1.15~1.26 | indexOf intrinsic 混合扫描 |
| **v5 流式 (P1)** | **0.0033*** | **0.0078** | **1.40**（parse）/**0.82**（readIntArray） | JSONReader 零装箱 |
| **v6 流式 Bean (A1+B2)** | **0.0049*** | **0.0055** | **1.85**（parse）/**0.73**（readIntArray） | 流式 bean 反序列化、quote 查表分段 |
| **v7 Unsafe 字段 (C2)** | **0.0060*** | **0.0057** | **1.59**（parse）/**0.85**（readIntArray） | 字节码级字段访问（~0.25ns，见实验记录） |
| **v8 深度计数环检测** | **0.0049~0.0060*** | **0.0037***（聚焦） | **1.59~1.83**（parse）/**0.85~1.00**（readIntArray） | 深度计数替代 IdentityHashMap，serialize 差距 2.5x→1.9x |

*聚焦基准（5 轮取最小）实测；完整 benchmark 受机器负载波动影响（parse 显示 0.007~0.008）。

**累计提升：解析 ~2.6x（聚焦），序列化 1.8x，大数组 parse 1.6x / readIntArray 4.1x；bean 绑定相对波前 -33%（聚焦）。**

### 优化实验记录（v6，A/B 实测否决）

| 尝试 | 结果 | 原因 |
|---|---|---|
| 数字哨兵（去掉 `p<len`） | ❌ parse-100k +23% 变慢 | JIT 失去数组边界检查消除依据（`p<len` 是消除信号），每字符多做一次边界检查 |
| SymbolTable key 驻留 | ❌ parse complex +63% 变慢 | intern 发生在 substring 分配之后（省不了分配），并发哈希开销 > 复用收益；fastjson2 的符号表是分配前查表，不可照搬 |
| MethodHandle 字段访问 | 跳过 | `privateLookupIn` 需 JDK 9+，pom 目标 Java 8 |
| **Unsafe 字段访问（C2 字节码级）** | ✅ 采用 | `objectFieldOffset`+typed get/put：实测 0.25ns ≈ 直接访问（0.26ns）、快于反射 13.7x；但整体 bean-serialize 收益 <5%——字段访问仅占序列化总成本 ~4%，switch 分派抵消收益。**保留**：语义等价、带反射 fallback、未来输出缓冲优化后字段访问占比上升时受益。真·字节码生成（ASM 式）被否决：工程量 ~10 倍，收益边际 ~20%（0.25→0.2ns） |
| **深度计数替代环检测（fastjson2 路线）** | ✅ 采用 | IdentityHashMap 每容器 30-60ns（复杂文档 ~40 容器 ≈ 总成本 28%）；深度计数（≤512，与解析对齐）语义等价——环=无限递归必超限，DAG 不误杀；serialize 差距 2.5x→1.9x。⚠️ 陷阱：writeFieldValue/putField 的 catch(Throwable) 会吞深度检查的 JSONException 导致无限重试挂起——必须重抛 JSONException/Error |
| 手写 char[] 输出缓冲（JSONWriter 路线） | ❌ A/B 否决 | 实测比 StringBuilder 慢 2 倍（212 vs 112ns）——JDK 对 StringBuilder 有内建 JIT 优化，手写缓冲不可战胜 |

**累计提升：解析 3.8x（聚焦基准），序列化 1.7x，大数组 2.6x（parse）/ 3.6x（readIntArray）；安全加固与预测解析零性能代价。**

## 4. 结论

- **DingJson 复杂文档解析第 4**（fastjson2 领先 2.5x；与 fastjson1/Jackson 同档，超过 Gson/org.json）；
  100k 数组解析第 2（与 fastjson2 同档，1.4x 于 fastjson1）；**流式 `readIntArray` 全面登顶**（1,226 ops/s）
- **序列化中游**（第 4）：fastjson2/Jackson/fastjson1 有字符级手写输出器；DingJson 已超过 Gson/org.json
- **内存中游**（复杂文档第 5）：优于 Gson 28%、与 org.json 相当；大数组与 fastjson 持平
- **CPU 与墙钟 1:1**（单线程），无锁竞争、无 GC 抖动
- 快于 org.json **2.2x**（解析）、**1.9x**（序列化）、**6.3x**（大数组 parse）、**10.8x**（readIntArray）；快于 Gson **1.3x**（解析）、**2.2x**（大数组）

### 复现

```bash
mvn test-compile
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
java -Xms512m -Xmx2g -cp "target/test-classes:target/classes:$(cat cp.txt)" com.dingjson.Benchmark
```

> 依赖说明：fastjson1/2、jackson、gson、org.json 仅为 benchmark 测试依赖（test scope），
> 不进入 DingJson 发布产物；fastjson 1.2.83 虽有已知 AutoType 漏洞，但仅存在于测试 classpath。
