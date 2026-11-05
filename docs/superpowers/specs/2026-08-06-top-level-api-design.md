# DingJson 顶层 API 增强设计

日期：2026-08-06
状态：✅ 已实现（2026-08-06，457 测试全绿 + 单文件版冒烟 20 项通过）

## 背景与目标

DingJson 现有顶层 API 仅支持 `String` 输入，缺少 IO（byte[]/InputStream/Reader/File）、静态 config 重载、一行式便捷方法、深拷贝。目标：补齐四类 API，保持零依赖、Java 8 兼容、无破坏性变更。

## 不做的事（边界）

- **record 支持**：需要 Java 16+，当前 target 是 Java 8，明确不做，留作未来单独发版方向。
- **编码探测 / BOM 剥离**：保持严格行为——所有字节输入固定 UTF-8 解码；带 BOM 的输入按现有行为被拒绝（与 JSONTestSuite 结果一致）。不做 charset 参数、不做 BOM 剥离。
- **流式增量解析（从 InputStream 边读边解析）**：本次 IO 重载内部仍是「读全部 → 解码 → 现有 String 解析路径」。真正的事件流解析器超出范围。
- **深层泛型的无类型深拷贝**：`deepCopy` 对泛型集合的元素类型需要调用方提供 `TypeReference`，否则元素保留为树节点（JSONObject/JSONArray），这是明确承诺的行为。

## 设计

### 方向 A：IO 重载（DingJson 静态 + DingJsonMapper 对称）

新增私有辅助（DingJson 内）：

```java
private static String readAll(byte[] bytes)            // UTF-8 解码
private static String readAll(InputStream in)          // readAllBytes → UTF-8
private static String readAll(Reader r)                // 读全部字符
private static String readAll(File f)                  // Files.readAllBytes → UTF-8
```

`DingJson` 新增重载（每种输入类型 × parse 家族）：

```java
// byte[] / InputStream / Reader / File 四种输入，各支持：
Object parse(X)
JSONObject parseObject(X)
JSONArray parseArray(X)
<T> T parseObject(X, Class<T>)
<T> T parseObject(X, TypeReference<T>)
<T> List<T> parseArray(X, Class<T>)
<T> List<T> parseArray(X, TypeReference<T>)
// + config 重载（见方向 B）组合

// 写出
void writeTo(File f, Object obj)
void writeTo(File f, Object obj, boolean pretty)
void writeTo(OutputStream out, Object obj)
void writeTo(OutputStream out, Object obj, boolean pretty)
```

`DingJsonMapper` 增加完全对称的方法（带自身 config）。

**错误处理约定**：IO 失败（文件不存在、流关闭、解码失败）一律包装为 `JSONException`（带 cause），保持「无 checked 异常」的既有 API 风格。解码失败（非法 UTF-8）也走 JSONException。

### 方向 B：静态 API 补 config 重载

与现有 mapper 行为完全对称：

```java
Object parse(String text, DingJsonConfig config)
JSONObject parseObject(String text, DingJsonConfig config)
JSONArray parseArray(String text, DingJsonConfig config)
<T> T parseObject(String text, Class<T> clazz, DingJsonConfig config)
<T> T parseObject(String text, TypeReference<T> typeRef, DingJsonConfig config)
<T> List<T> parseArray(String text, Class<T> elementType, DingJsonConfig config)
<T> List<T> parseArray(String text, TypeReference<T> elementType, DingJsonConfig config)
JSONReader createReader(String text, DingJsonConfig config)
```

无重载歧义：`DingJsonConfig` 与 `Class`/`TypeReference` 是不同类型，Java 重载解析安全。静态无 config 版本行为不变（继续用 `DEFAULT_CONFIG`）。

### 方向 C：一行式便捷方法

**C1 — 解析 + JSONPath 一步到位**（`DingJson`）：

```java
Object eval(String json, String path)              // parse(json) + JSONPath.eval
<T> T eval(String json, String path, Class<T> clazz)
```

**C2 — 纯校验（不建树）**：

```java
boolean isValid(String text)
```

实现依赖新增的 **skip 解析器**（见下），只消费 token 不构建树，大 payload 下不触发树 OOM。

**C2.5 — skip 解析器**（`JSONParser` 新增，内部能力）：

```java
void skipValue()          // 校验并消费一个值，不建树；带 MAX_DEPTH 与 size 限制
```

同时把 `JSONReader.skipValue()` 的实现改为调用它（顺带修复：当前 skipValue 建树后丢弃，纯浪费）。

**C3 — 快速构造工厂**（`JSONObject` / `JSONArray`）：

```java
static JSONObject of(Object... kvPairs)   // 偶数个参数，交替 key/value；key 必须 String，否则 JSONException
static JSONArray of(Object... values)
```

**C4 — facade 统一入口**（`DingJson`）：

```java
JSONWriter createWriter()                  // 等价 JSONWriter.create()
JSONWriter createWriter(int initialCapacity)
```

### 方向 D：深拷贝

```java
// DingJson
static JSONObject deepCopy(JSONObject obj)    // 递归复制树（快路径，不经过序列化）
static JSONArray deepCopy(JSONArray arr)
static <T> T deepCopy(T value)                // 序列化往返 + convert（best effort）
static <T> T deepCopy(T value, TypeReference<T> typeRef)  // 精确泛型元素类型
```

- 树类型走递归复制；普通 bean 走 `toJSONString → parse → deserialize`。
- 循环引用：沿用序列化深度限制，超限抛 `JSONException`。
- 泛型集合无 `TypeReference` 时元素保留为树节点（见「不做的事」）。

## 实现偏差记录（对照设计）

1. **IO parse 家族不做 config 变体**：四种输入 × 7 方法 × config 组合会新增 28+ 个
   近重复方法；配置化 IO 需求由 `DingJsonMapper` 的对称 IO 家族（自带 config）覆盖。
2. **`isValid` 实现**：`JSONTokener.hasMore()` 是包私有，改用
   `JSONReader.skipValue() + ensureEnded()`（语义等价）。
3. **存量 bug 修复（超范围但必要）**：`parseArray(text, Integer.class)` 等流式
   标量数组遇到元素分隔符 `,` 时，`readScalar` 的字符串回退路径抛
   `Expected string but got ','` —— 原有测试从未覆盖标量元素类型。
   新增 `JSONReader.skipComma()`，两个 `parseArray(String, Class)` 循环（静态 + mapper）
   在元素间消费分隔符。
4. **重载歧义**：新增 `parse(Reader)`/`parse(File)` 后 `parse(null)` 二义；
   两个存量测试调用点改为 `parse((String) null)`。
5. **deepCopy 标量短路**：`String`/`Number`/`Boolean` 直接返回原引用（不可变，
   免去无谓往返）。
6. `JSONReader` 新增公开 `skipComma()`（上述修复 3 的一部分）。

## 兼容性

- 全部为**新增方法**，零破坏。
- 静态 API 默认行为不变（仍用 `DEFAULT_CONFIG`，含新加的 size 限制）。
- 单文件版 `release/DingJson.java` 通过 `python3 tools/generate_single_file.py` 重新生成；该脚本顶部 import 段需补充 `java.io.*`（File/InputStream/OutputStream/Reader）。

## 测试计划

- 新测试文件 `src/test/java/com/dingjson/DingJsonIoTest.java`：
  - byte[]/InputStream/Reader/File 各解析入口（含 UTF-8 中文、非法 UTF-8 → JSONException）
  - writeTo 写出 + 读回 round-trip
  - config 重载（含 size 限制生效验证：小 maxObjectSize 拒绝大对象）
  - eval 一步到位（成功/路径不匹配）
  - isValid（合法/非法/超大嵌套不建树）
  - skipValue 与现有 parse 结果一致（对照测试）
  - of / createWriter
  - deepCopy（树、bean、泛型集合、独立性验证：改副本不影响原对象）
- 现有 433 测试零回归。
- JSONTestSuite 行为不变。

## 文档计划

- README.md：新增「便捷 API」章节（IO、config 重载、eval/isValid/of/deepCopy 示例）。
- CHANGELOG.md：追加条目。
- 重新生成 release/DingJson.java 单文件版。

## 实现顺序（逐步推进）

1. 方向 B（config 重载）→ 2. 方向 C（eval / of / createWriter）→ 3. C2.5 skip 解析器 + isValid → 4. 方向 A（IO 重载 + mapper 对称）→ 5. 方向 D（deepCopy）→ 6. 测试补齐 → 7. README/CHANGELOG/单文件版重新生成 → 8. 全量验证（clean test）。
