# Changelog

All notable changes to DingJson are documented here.

## [1.0.0] — 2026-10-22 (企业级基线)

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
