# 优化报告：编译期 / 运行期

分支：`perf/compiler-runtime-optimization`
基线：`main` @ `d0896ec`
验证：`mvn -o test` —— **492 测试全绿**，且分别在 Unsafe 字段访问与纯反射两条实现下各跑一遍（见最后一节）。

## 结论一览

同负载下 `main` 与优化分支交替对跑（`/tmp/Final.java`，5 轮取最小，负载高时取单轮对比，两轮均在同一时段）：

| 场景 | main | 优化后 | 倍数 | 分配 main | 分配后 |
|---|---|---|---|---|---|
| parse tree（~2 KB 文档） | 1.866 μs | 1.139 μs | **1.64x** | 5368 B | 4280 B |
| parse byte[] | 2.501 μs | 1.476 μs | **1.69x** | 7480 B | 6392 B |
| parse bean | 0.497 μs | 0.333 μs | **1.49x** | 672 B | 568 B |
| isValid（~2 KB 文档） | 1.729 μs | 0.591 μs | **2.93x** | 4128 B | **1032 B** |
| serialize tree | 2.382 μs | 1.480 μs | **1.61x** | 8112 B | 4272 B |
| serialize bean | 0.296 μs | 0.181 μs | **1.64x** | 696 B | 200 B |
| toJSONBytes tree | 2.202 μs | 2.139 μs | 1.03x | 9632 B | 5816 B |

单项改动阶段测得的原始数字（改动前后各测一次，JPEG 波动 10~20%）：

- 序列化 1000 个 25 字符字符串：43.6 μs / 220 B/op → 19.5 μs / 76 B/op
- `JSONTokener` 构造（2 KB 文档）：1.82 μs → 0.14 μs
- `isValid`（100k int）：1057 μs / 2.78 MB → 847 μs / 1.18 MB
- `readIntArray`（100k）：764 μs → 586 μs
- `readDoubleArray`（100k）：6883 μs / 17.3 MB → 2986 μs / 4.5 MB

> 环境说明：本机常驻进程（`ecosystemd`、`node`、企业安全客户端）会把 10 核跑到 load 10~15，
> 同一份代码两次测量可差 2 倍。因此表中倍数来自**同一时段内 main / 优化分支交替运行**，
> 而分配量（`getCurrentThreadAllocatedBytes`）与负载无关，可作为更可靠的判据。

## 改了什么

### 1. `quote()` 直写目标缓冲（`JSONSerializer`）
`quote(String)` 每个字符串都要 `new StringBuilder` + `toString()`。新增 `appendQuoted(StringBuilder, String)` 直接写进目标缓冲；热路径（String 值、Map/JSONObject key、bean 字段名、pretty 打印、`JSONWriter`）全部改用它，只有需要独立 `String` 的少数点（char / Character / 1 字符场景）保留 `quote`。

### 2. `skipValue` 纯扫描（`JSONTokener` + `JSONParser`）
`isValid` / `skipValue` 只要"跳过并校验"，原来却要 `readString()` 物化字符串、`readNumber()` 装箱。新增 `skipString()`（无反斜杠时零分配，复用 `decodeEscapes` 做校验后丢弃）与 `skipNumber()`（语法与范围规则与 `readNumber` 一致，不装箱）。**HTML 转义等语义完全未动。**

### 3. 去掉构造期整串控制字符预扫描（`JSONTokener`）
原来 `JSONTokener` 构造时扫一遍全文找控制字符，等于每个文档多一次全量扫描。改为 `checkControlChars(from, to)` 只在真正读到的字符串区间内检查，并并入快扫循环；长串 SIMD 路径在 `indexOf('"')` 之后检查一次。

### 4. 流式数组就地扫描（`JSONReader`）
`readIntArray` / `readLongArray` 直接在 tokener 的 `char[]` 上跑，每元素零方法调用；数字累积改为 18 位内不做范围分支（19 位以上才回退重扫，仍能正确处理 `Long.MIN_VALUE`）。

### 5. 字段元数据预计算 + `ClassValue`（`JSONSerializer`）
`FieldInfo` 带上构造时算好的 `kind`，不再每个实例每个字段重跑 8 路 `kindOf()` 分派；`FIELD_CACHE` / `FIELD_MAP_CACHE` 从 `ConcurrentHashMap` 换成 `ClassValue`。实测在噪声内（±2%），作为结构性简化保留，不作为提速项计入。

### 6. 零分配浮点解析（`JSONTokener.fastDouble`）
原来每个小数都要 `new String(src, start, len)` 再 `Double.parseDouble`。现在：尾数 ≤15 位（long 精确表示）且十进制指数 ∈ [-22, 22]（10^n 精确）时，用一次乘/除完成——这是对精确十进制值的**一次**舍入，与 JDK 正确舍入解析器**逐位相同**；超出范围才回退 `parseDouble`。20k 随机字面量与 34 个边界样例（`1e22`/`1e-22`/`5e-324`/`-0.0`/17 位尾数/…）与 `Double.parseDouble` 比对**位相等**，已固化为测试 `DingJsonNumberParseTest`。
`readDoubleArray` / `readStringArray` 顺带改为融合扫描（不再逐元素 `Number` 装箱 / 两趟分派）。

### 7. 反射回退路径纳入构建验证
`Unsafe::objectFieldOffset` 在 JDK 25 已 termina­lly deprecated，未来会移除。`-Ddingjson.unsafe=false` 可强制走纯反射路径，`pom.xml` 增加第二个 surefire execution 用它跑完整测试套件（492 全绿），保证两条实现不会漂移。

## 评估后**不做**的项

| 候选 | 决定与理由 |
|---|---|
| 数组定容预扫描 | 已实现后**回退**。精确容量只省两次 memcpy（100k 元素约 80 μs），却要多扫一遍输入（1.1 M 字符，数百 μs），净亏。增长倍增保留。 |
| `toJSONBytes` 直编 UTF-8 | 实测无收益（2.202 → 2.139 μs）。JDK 9+ 的 `StringBuilder` 是 Latin1 紧凑存储，`toString()` 后 `getBytes(UTF_8)` 走 SIMD 快路径，String→bytes 这一步几乎免费；只有 2 KB 的中间 String 拷贝，改为字节流式序列化需要重写整条序列化链，收益与工程量不匹配。 |
| 多版本 jar + `VarHandle`（`META-INF/versions/9`） | 不做。`VarHandle` 只能从 jar 的多版本路径生效（classpath 测试走不到），需要改构建并维护第二份字段访问实现，**零性能收益**（Unsafe 直访已与手写 `GETFIELD` 持平），只换来去掉警告。改为验证反射回退（上一节），Unsafe 真被移除时库仍可用，只是慢一点。 |
| 运行期字节码生成（codegen，ASM 风格） | 不做。`BENCHMARK.md` 已评估过一次：工程量约 10 倍、边际收益约 20%。且本库**零依赖**，要生成字节码得自带一套 classfile 写入器（fastjson2 是自带 ASM 分支的），与项目定位冲突。A~F 完成后如需再议，起点应是"手写 classfile 常量池 + 单字段 getter"的极小实现，而非通用 ASM。 |
| byte[] 原生 tokener（彻底去掉解码拷贝） | 暂不做。当前 `bytes → String → char[]` 有 3 次拷贝，但 `parse(byte[])` 只比 `parse(String)` 慢 0.1~0.3 μs（SIMD 解码已很快）。收益集中在超大文档，工程量大（要重写全部扫描器为 byte 版或做双实现）。 |
| `readBooleanArray` 融合扫描 | 不做。布尔数组体积小、非热路径，融合收益低于维护成本。 |

## 已知残留开销

- 每次解析都会把输入 `String` 复制成 `char[]`（`String.toCharArray`，约 2N 字节）。这是所有扫描器零分支访问的前提，暂时接受。
- `parse(InputStream)` 路径是 `BAOS → byte[] → String → char[]`，小文档无所谓，超大流可以后续优化。

## 验证

```
mvn -o test
  injection           492 tests, 0 failures   (Unsafe 字段访问)
  reflection-fallback 492 tests, 0 failures   (纯反射)
```
