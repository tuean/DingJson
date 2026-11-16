# Changelog

All notable changes to DingJson are documented here.

## [1.1.0] — 未发布（编译器 / 运行期优化）

### Performance
相对 1.0.0 的同 JVM A/B 实测（`BENCHMARK.md` 第 1 节，ns/op 越低越好）：

| 操作 | 1.0.0 | 1.1.0 | 变化 |
|---|---|---|---|
| `parse` 复杂文档 | 6,608 | 4,807 | **1.37x**（四次运行 1.29–1.70x） |
| `serialize` 复杂文档 | 4,892 | 4,209 | 1.16x |
| `parse` 100k 整数数组 | 1,175,471 | 1,024,195 | 1.15x |
| `bean-list` 100 beans | 68,681 | 45,548 | **1.51x** |
| `bean-parse` | 937 | 1,055 | 噪声内（单 bean 仅 ~1 μs，判读用 `bean-list`） |
| `bean-serialize` | 266 | 298 | 持平（噪声内） |

横向：`parse` 从第 4 升到**第 2**，100k 数组解析**第 1**，`bean-list` 第 5 → 第 2，
`serialize` 仍第 4，`readIntArray` 绝对领先（1,438 ops/sec）。

### Added
- **编译期 codec 生成**：给类加 `@DingJsonSerializable`，注解处理器生成
  `<类名>$$DingJsonCodec`（直线代码，无反射分派），运行期自动发现、找不到即回退反射。
  生成失败（私有/`final` 字段、泛型、抽象类）只发 NOTE 不阻断构建；
  `-Ddingjson.codec.disabled=true` 可关闭
- **`DingJson.registerCodec(Class, DingJsonCodec)`**：显式注册生成的 codec，
  把"按类名查找 codec"这最后一处 `Class.forName` 也去掉，bean 路径变成零反射
  （GraalVM native-image / 受限 ClassLoader 场景）；必须在首次使用前注册，晚了抛 `JSONException`
- **bean 序列化热路径**：字段 key 预引用（`"name":` 一次构建）、无参构造器缓存、
  `serialize()` 改为 `ClassValue` 分发表

### Fixed
- **树路径不认 `@JSONField(format)`**：`parseObject(text, TypeReference)` 遇到
  `Date` + `@JSONField(format = "yyyy-MM-dd")` 时抛 `NumberFormatException`
  （`Long.parseLong("2025-01-01")`），写得出却读不回；现在与流式路径一致

## [1.0.1] — 2026-11-16 (安全审计修复)

### Performance
无变化：本轮只改错误路径与类型校验，快路径唯一改动是 `readScalar` 的
int/short/byte 分支多一次 `readLong`（差异在噪声内，见 `BENCHMARK.md`）。

### Security / 类型安全
修复 `docs/security-audit.md` 中的 V1–V13。V1 是 **JVM 崩溃级**（未捕获的
`SIGSEGV`/`SIGBUS`），其余是数据静默损坏与报错质量问题。
- **V1（P0）类型混淆致 JVM 崩溃**：`Unsafe` 写字段绕过了 JVM 的字段类型不变量，
  转换失败的原始 JSON 值被写进不相干字段，读取时 `Method::print_external_name`
  / `String.valueOf` 直接 SIGSEGV，`String[]` 字段塞 `JSONArray` 则把对象头当数组
  长度读出 50960989 → SIGBUS。现在三层拦截：
  - `putField` 对非 primitive 字段做 `isInstance` 校验（Unsafe 与反射两条路径都过）
  - `convert` 兜底从 `return value` 改为抛 `JSONException`
  - 数组字段（其 `Modifier` 含 `ABSTRACT`）在抽象检查之前单独路由到数组转换；
    接口/抽象类字段不再是"保留原值"而是显式报错
- **V2 窄整型静默截断**：`2147483648 → -2147483648`、`70000 → 4464`、`300 → 44`；
  现在 `readScalar`/`convert` 走 `readLong` + 范围检查（`toIntExact`/`toShortExact`/
  `toByteExact`，零装箱），与 `1e10` 已有的报错行为一致
- **V3 数值 getter 饱和**：`getLong("1e40")` 返回 `Long.MAX_VALUE`；现委托给
  统一转换器并抛 `JSONException`（`getInteger/getLong/getDouble/getBoolean`）
- **V4 泛型字段丢类型**：`Box<T>.value` 拿到原始 JSON 值；现在解析
  `ParameterizedType` 时建立类型变量绑定（含 `List<T>`），字段按实参转换
- **V5 数组元素转换失败**：随 V1 一并修复，`String[]` ← `[1,2]` 得到 `["1","2"]`，
  `int[]` ← `["a"]` 抛 `JSONException`
- **V6 指数炸弹**：`1e2147483648` 曾构造出 `scale=-2147483648` 的 `BigDecimal`
  （`toPlainString()` 即 OOM、`setScale` 抛 `ArithmeticException`）；
  现在 `|scale| > 10000` 直接拒绝
- **V7/V8 深度**：`parse`/`skipValue` 捕获 `StackOverflowError` 转 `JSONException`；
  新增 `DingJsonConfig.maxDepth(int)`（默认 512）供小栈环境调低
- **V9 字段名遮蔽**：同时声明 `name` 与 `NAME` 的类，`{"name":"x"}` 会写进 `NAME`
  （小写键覆盖精确键）；精确/小写两张表分离，精确匹配优先
- **V10 `JSONPath.eval(..., Class)` 裸 `ClassCastException`**：改为走统一转换器，
  抛 `JSONException`
- **V11 报错无字段名**：转换失败消息现在带 `Field 'age': ...` 前缀（嵌套 bean 形成路径）
- **V12 `U+2028/U+2029` 不转义**：JavaScript 里是行终止符，可截断 `<script>`；
  现在转义为 `\u2028`/`\u2029`，round-trip 不变
- **V13 null Map 键**：输出 `"null"` 与字符串 `"null"` 无法区分，改为抛 `JSONException`

### Fixed
- **`clean` 后首个构建失败**：`-proc:full` 原本作用于全部 compile，导致
  `META-INF/services` 在处理器类存在之前被读取；现在只加在 `test-compile`
- **测试脆弱性**：`ExplicitCodecRegistrationTest.UpperCaseCodec` 由 `private` 改为
  包私有，去掉随编译单元状态变化的 synthetic 访问构造器

### Tests
- 新增 `DingJsonTypeSafetyTest`（25 例，V1–V13 的 POC 全部转为回归测试），
  测试总数 509 → **534**，Unsafe 与 `-Ddingjson.unsafe=false` 两条路径各跑一遍

## [1.0.0] — 2026-10-22 (企业级基线)

### Performance
相对 0.1.0：解析 **1.65x**（0.0127 → 0.0077 ms/op）、序列化 **1.98x**
（0.0101 → 0.0051）、100k 整数数组 **2.2x**（2.95 → 1.34，另加流式
`readIntArray` 0.78 ms，零装箱）。数据见 `BENCHMARK.md`。

### Security (安全加固 — 审计修复 P0–P3)
- **输入大小上限（OOM DoS 防护）**：新增 `DingJsonConfig.maxInputBytes()`（默认 10 MB，
  0 = 不限），`byte[]`/`InputStream`/`Reader`/`File` 入口在读入过程中即时拒绝超大输入；
  此前流入口无上限，攻击者可传入无限流直到内存耗尽
- **深嵌套栈溢出防护**：深度超过小栈线程（容器/虚拟线程）容量时抛出可捕获的
  `JSONException` 而非 `StackOverflowError`（序列化/反序列化/deepCopy/JSONPath 入口）
- **孤立代理项（lone surrogate）转义**：输出时对未配对代理项输出 `\uXXXX`，
  修复经 UTF-8 编码后被静默替换为 `?` 的 round-trip 数据损坏；合法代理对保持原样输出
- **double 溢出数字**：`1e999` 不再变成 `Infinity`（随后序列化为 `null` 丢数据），
  树路径改为保留为 `BigDecimal`（Gson 策略，可 round-trip）；`readDouble()`/double 字段
  严格拒绝；指数位数超限抛 `JSONException` 而非裸 `NumberFormatException`
- **Map 非字符串键**：序列化/`toJSON` 不再 `ClassCastException`，键按 Jackson 风格
  字符串化（`null` 键 → `"null"`）
- **严格日期解析**：`DateTimeFormatter` 取代 ThreadLocal<SimpleDateFormat>
  （消除线程泄漏风险 + 宽松解析问题，如 `2023-02-30` 不再自动滚月）

### Fixed (P0 正确性 bug)
- **JSONWriter 静默输出非法 JSON**：`beginObject().key("a").endObject()` 曾产出
  `{"a":}`，现在抛 `JSONException`
- **pretty 序列化全 null 对象输出损坏**：`writeNullFields(false)` 且所有值均为 null 时
  曾产出 `\n}`（`{` 被误覆盖），现在正确输出 `{}`
- **char 字段内存损坏**：char 字段此前走 `Unsafe.getObject`/`putObject` 路径，
  序列化产出垃圾（如 `{"c":{"value":null}}`）、反序列化污染相邻字段
  （`'A'` 变成随机汉字），现在走类型化 `getChar`/`putChar`，序列化为 1 字符字符串
- **int=0 字段被丢弃**：`writeNullFields(false)` 时 `getObject` 读取值为 0 的 int 字段
  误判为 null 导致字段丢失，现在只对引用类型做 null 判断（基本类型永不为 null）
- **Character 值序列化**：`Character` 实例此前按 bean 反射输出 `{"value":null}`，
  现在输出 1 字符字符串（`"Z"`）

### Changed
- **序列化性能**：`Map`/`Collection` 直接迭代输出（不再整体拷贝为 JSONObject/JSONArray），
  大容器 O(n) 内存/时间开销移除；`writeNullFields(false)` 模式下引用字段只读一次
- **输入读取**：`readAll` 流读取带初始容量（64 KB），超大输入提前拒绝
- **代码去重**：`DingJson` 与 `DingJsonMapper` 共享 `asObject`/`asArray`/`parseTypedArray`/`readAll`
  实现（行为不变）
- **缓存与增长**：`FIELD_CACHE` 用 `computeIfAbsent`（并发下不再重复构造 FieldInfo）；
  数组增长/容量预估带溢出检查
- **新增回归测试**：`DingJsonHardeningTest`（31 用例覆盖全部上述修复）

### Added
- **IO 输入输出**：`byte[]` / `InputStream` / `Reader` / `File` 四种输入的完整
  parse 家族 + `writeTo` 写出（`DingJson` 静态 + `DingJsonMapper` 对称）；
  固定 UTF-8 严格解码（非法字节抛 `JSONException`，不静默替换）
- **静态 config 重载**：parse 家族 8 个带 `DingJsonConfig` 的重载 + `createReader(text, config)`，
  与 mapper 行为完全对称；`null` config 等同默认
- **便捷方法**：`eval(json, path[, Class])` 一步到位、`isValid(text)` 纯校验不建树、
  `JSONObject.of(...)` / `JSONArray.of(...)` 快速构造、`createWriter()` 门面
- **skip 解析器**：`JSONParser.skipValue()` / `JSONReader.skipValue()` 校验并消费
  一个值而不建树（常量内存），带深度与大小限制；修复了原 skip 建树后丢弃的浪费
- **deepCopy**：树快路径（递归复制）+ bean 序列化往返 + `TypeReference` 精确泛型
- **流式类型化数组修复**：`parseArray(text, Integer.class)` 等标量元素类型此前
  遇到元素分隔符会误入字符串回退路径（`Expected string but got ','`），
  新增 `JSONReader.skipComma()` 修复（原有测试未覆盖的存量 bug）

### Fixed
- **配置化引擎**：`DingJsonConfig` + `DingJsonMapper`（Jackson ObjectMapper 同款）：
  `writeNullFields`（省略 null 字段）、`dateFormat`（全局日期格式）、
  `caseInsensitiveFields`、`failOnNonFinite`
- **注解**：`@JSONField(name / serialize / deserialize / format)`
- **JSONWriter**：流式构建 API（与 JSONReader 对称）
- **JSONPath**：`$.a.b[0]`、`[*]` 通配、负索引、`..` 递归下降、类型化取值
- **toJSONBytes**：UTF-8 字节输出（`DingJson` + `DingJsonMapper`）
- **JDK 类型支持**：`Date`/`Calendar`（epoch millis 或格式化）、`UUID`、
  `BigDecimal`/`BigInteger`（全精度）、`LocalDate`/`LocalDateTime`/`Instant`
- **数组泛型**：`TypeReference<Order[]>` 支持
- **并发测试**：缓存竞争、多线程解析/序列化（16 线程 × 5000 轮）
- **模糊测试**：随机输入 2 万用例不崩溃、随机数字 1 万用例

### Fixed
- **NaN/Infinity 输出非法 JSON**（`{"nan":NaN}`）→ 默认输出 `null`（Gson 策略），
  可配置为抛异常（Jackson 策略）
- **JDK 类字段序列化崩溃**（`InaccessibleObjectException`，JPMS 强封装下
  `UUID`/`Date` 字段直接炸）→ 类型特判 + `setAccessible` 优雅降级
- **BigDecimal 精度丢失**：反序列化经 double 中间态（`0.1000000000000000000001` → `0.1`）
  → 新增 `readNumberRaw()` 原始数字文本读取
- **匿名类合成字段泄漏**（`this$0`）→ 序列化跳过 synthetic 字段
- **Date 字段输出空对象**（`{}`）→ epoch millis / 格式化字符串

## [0.1.0] — 2026-07-30 (初始版本)

### Added
- 零依赖 JSON 解析/序列化核心：`DingJson` 静态门面、`JSONObject`/`JSONArray`/`JsonElement`
- `TypeReference` 泛型反序列化（嵌套 List/Map/Bean）
- `JSONReader` 流式读取 + 类型化数组（`readIntArray` 等，零装箱）
- 手写字符级解析器：混合字符串扫描（`String.indexOf` SIMD intrinsic）、
  整数零分配解析、`Unsafe` 字段访问（带反射降级）
- 安全基线：深度限制 512、HTML 转义、循环引用深度检测、无 AutoType、数字溢出拒绝
- JSONTestSuite 318 用例 100% 通过
- 基准框架 `Benchmark.java` + `BENCHMARK.md`
- 362 个单元/集成测试
