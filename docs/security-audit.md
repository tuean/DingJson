# DingJson 安全审计报告

- **审计对象**：DingJson 1.0.1（分支 `perf/compiler-runtime-optimization`）
- **审计方式**：白盒 + 黑盒。读源码定位防御点，再用恶意载荷实测（226 条攻击用例 + 定向复现）
- **运行环境**：OpenJDK 25.0.2 / G1 / macOS AArch64，默认配置（`Unsafe` 路径）
- **授权**：代码库所有者自行发起，仅在本机隔离环境执行

> **结论**：语法层、编码层、资源上限层的防御扎实；**问题集中在「JSON 值 → Bean 字段」的写入层**。
> 其中 **V1 是 JVM 崩溃级（SIGSEGV / SIGBUS），一个 JSON 字段即可触发**，建议立即修。

## 发现总表

> **修复状态**：V1–V13 已在 **1.0.1** 修复，POC 全部转成回归测试
> `src/test/java/com/dingjson/DingJsonTypeSafetyTest.java`（25 例，Unsafe 与
> `-Ddingjson.unsafe=false` 两条路径各跑一遍）。V14 见文末说明——属于低层流式
> API 的显式契约，不做代码改动，只在 `usage.md` 里写清边界。
> 下方各节保留原始的载荷与实测数据，修复方式见 `CHANGELOG.md` 1.0.1 条目。

| ID | 等级 | 问题 | 影响 | 状态 |
|---|---|---|---|---|
| **V1** | **P0** | `putField` 无类型校验 + 转换失败返回原值 → 任意类型写入任意引用字段 | **JVM SIGSEGV / SIGBUS，进程崩溃** | ✅ 1.0.1 |
| V2 | P1 | 窄整型静默截断（`int/short/byte`） | 数据损坏，可绕过数值校验 | ✅ 1.0.1 |
| V3 | P1 | `getInteger`/`getLong` 饱和/回绕 | 静默错值 | ✅ 1.0.1 |
| V4 | P1 | 泛型擦除的 `T` 字段塞原值 | 调用点 `ClassCastException` | ✅ 1.0.1 |
| V5 | P1 | 数组元素转换失败 → `null` | 静默丢数据 | ✅ 1.0.1 |
| V6 | P2 | `BigDecimal` 接受极端指数 | 下游 `setScale` 炸 / `toPlainString` 撑爆内存 | ✅ 1.0.1 |
| V7 | P2 | `parse(String)` 不捕获 `StackOverflowError` | 小栈线程下 `Error` 逃逸 | ✅ 1.0.1 |
| V8 | P2 | 深度上限 512 对 256 KB 栈不安全 | 同上，且是否触发取决于 JIT 状态 | ✅ 1.0.1（`maxDepth` 可配） |
| V9 | P3 | 大小写变体字段名污染精确匹配 | 字段错位写入 | ✅ 1.0.1 |
| V10 | P3 | `JSONPath.eval(..., Class)` 抛裸 `ClassCastException` | 契约违背，500 而非 400 | ✅ 1.0.1 |
| V11 | P3 | 字段级错误消息不含字段名 | 安全审计不可用 | ✅ 1.0.1 |
| V12 | P3 | `U+2028`/`U+2029` 不转义 | `<script>` 内嵌场景的 XSS 载体 | ✅ 1.0.1 |
| V13 | P3 | `null` Map 键输出 `"null"` | 键语义丢失 | ✅ 1.0.1 |
| V14 | P3 | 流式手动循环不受 `maxArraySize` 约束 | 上限可绕过（需文档说明） | 📄 文档说明 |
| **V1** | **P0** | `putField` 无类型校验 + 转换失败返回原值 → 任意类型写入任意引用字段 | **JVM SIGSEGV / SIGBUS，进程崩溃** |
| V2 | P1 | 窄整型静默截断（`int/short/byte`） | 数据损坏，可绕过数值校验 |
| V3 | P1 | `getInteger`/`getLong` 饱和/回绕 | 静默错值 |
| V4 | P1 | 泛型擦除的 `T` 字段塞原值 | 调用点 `ClassCastException` |
| V5 | P1 | 数组元素转换失败 → `null` | 静默丢数据 |
| V6 | P2 | `BigDecimal` 接受极端指数 | 下游 `setScale` 炸 / `toPlainString` 撑爆内存 |
| V7 | P2 | `parse(String)` 不捕获 `StackOverflowError` | 小栈线程下 `Error` 逃逸 |
| V8 | P2 | 深度上限 512 对 256 KB 栈不安全 | 同上，且是否触发取决于 JIT 状态 |
| V9 | P3 | 大小写变体字段名污染精确匹配 | 字段错位写入 |
| V10 | P3 | `JSONPath.eval(..., Class)` 抛裸 `ClassCastException` | 契约违背，500 而非 400 |
| V11 | P3 | 字段级错误消息不含字段名 | 安全审计不可用 |
| V12 | P3 | `U+2028`/`U+2029` 不转义 | `<script>` 内嵌场景的 XSS 载体 |
| V13 | P3 | `null` Map 键输出 `"null"` | 键语义丢失 |
| V14 | P3 | 流式手动循环不受 `maxArraySize` 约束 | 上限可绕过（需文档说明） |

---

## V1（P0）类型混淆 → JVM 崩溃

### 载荷与实测

**1a. 接口 / 抽象类字段**

```java
interface Iface { }
abstract class Abs implements Iface { abstract int v(); }
class Holder { public Iface i; public Abs a; }

DingJson.parseObject("{\"i\":{\"x\":1},\"a\":{\"v\":2}}", Holder.class);
```

```
field i = com.dingjson.element.JSONObject     ← JVM 认为这里只能是 Iface
field a = com.dingjson.element.JSONObject
调用 h.a.v()  →
#  SIGSEGV (0xb)  at  Method::print_external_name
```

纯反射路径（`-Ddingjson.unsafe=false`）是干净的：

```
JSONException: Cannot deserialize to class Crash$H
Caused by: IllegalArgumentException: Can not set Crash$Iface field Crash$H.i to ...JSONObject
```

**1b. `Class<?>` 字段**

```java
class Cls { public Class<?> c; }
DingJson.parseObject("{\"c\":\"java.lang.Runtime\"}", Cls.class);   // c 里是 String
c.c.getClass().getName();   →
#  SIGSEGV at java.lang.String.valueOf(Object)
```

**1c. 数组字段（最严重：内存任意读）**

```java
class Arr { public String[] s; }
Arr a = DingJson.parseObject("{\"s\":[1,2]}", Arr.class);   // s 里是 JSONArray（一个 ArrayList）
a.s.length;   →   50960989        ← 把 ArrayList 的对象头当数组长度读
#  SIGBUS (0xa) at LinkResolver::resolve_invoke
```

**1d. 集合字段**

```java
class Hs { public HashSet hs; }
DingJson.parseObject("{\"hs\":{}}", Hs.class);   // hs 里是 JSONObject
```

### 根因

两处独立缺陷叠加，缺一不可：

**① 转换层无法转换时返回原值**（`JSONSerializer.convert` / `deserialize` 末尾兜底）：

```java
private static Object convert(Object value, Class<?> targetType) {
    if (targetType.isInstance(value)) return value;
    if (value instanceof JSONObject && !targetType.isAssignableFrom(JSONObject.class)) {
        return deserializeBean((JSONObject) value, targetType);
    }
    ...
    return value;        // ← 无法实例化的接口/抽象类/数组类型，原样返回
}
```

`deserializeBean(JSONObject, Iface.class)` 无法构造接口 → 走兜底 → 把 `JSONObject` 原样返回。

**② Unsafe 写入不做类型检查**（`JSONSerializer.putField:1231`）：

```java
if (UnsafeFields.AVAILABLE) {
    switch (f.kind) { case KIND_INT: UnsafeFields.putInt(...); ... }
}
// 引用类型直接 putObject，JVM 的字段类型不变量被绕过
```

`Field.set` 有 `IllegalArgumentException` 兜底，`Unsafe.putObject` **没有，也不可能有**。

### 为什么是 P0 而不是"抛个异常"

- 字段里放了一个 **JVM 类型系统认为不可能存在** 的对象 → JIT 的 CHA 优化假设失效
- 后果从 `IncompatibleClassChangeError`（`Error`，业务 `catch (Exception)` 抓不到）到 **SIGSEGV / SIGBUS**
- 触发门槛：目标类只要有一个 `接口` / `抽象类` / `数组` / `Class` / 具体集合类型 的字段，
  一个 JSON 字段就能打崩进程
- 三种崩溃现场都实测复现（`Method::print_external_name`、`String.valueOf`、`LinkResolver::resolve_invoke`）

### 修复

**必做（安全边界，优先级最高）：**

```java
private static void putField(Object bean, FieldInfo f, Object value) {
    // Unsafe writes bypass the JVM's own field-type check; without this a JSON
    // value the converter could not handle lands in the field as-is and the
    // next read crashes the JVM (SIGSEGV). Reflection would throw here.
    if (value != null && !f.type.isPrimitive() && !f.type.isInstance(value)) {
        throw new JSONException("Cannot set field '" + f.name + "' of type "
                + f.type.getName() + " from " + value.getClass().getName());
    }
    ...
}
```

**建议同时收紧源头：** `convert`/`deserialize` 的兜底改成"无法转换就抛"，
尤其对 `targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())
|| targetType.isArray() || Collection/Map 的具体实现类`。

代价实测：一次 `isInstance` 约 1 ns，Bean 解析路径每字段一次，属于噪声级别。

**测试应该加的**：接口字段、抽象类字段、`Class` 字段、`String[]` 字段、`HashSet` 字段
各一条 POC —— 目前 508 个测试里**一条都没有**，所以这个洞一直存在。

---

## V2/V3（P1）数值静默截断

```java
class I { public int i; public short s; public byte b; }

DingJson.parseObject("{\"i\":2147483648}", I.class);       // i = -2147483648
DingJson.parseObject("{\"i\":999999999999999}", I.class);  // i = -1530494977
DingJson.parseObject("{\"s\":70000}", I.class);            // s = 4464
DingJson.parseObject("{\"b\":300}", I.class);              // b = 44
```

注意**不一致**：`{"i":1e10}` 会抛 `"Expected integer but got a decimal number"`，
而 `{"i":999999999999999}` 静默回绕。攻击者用整数字面量就能让数量/余额/权限位变成负数。

树 API 同样：

```java
JSONObject o = DingJson.parseObject("{\"a\":1e40}");
o.getLong("a");       // 9223372036854775807  (饱和)
o.getInteger("a"); 
DingJson.parseObject("{\"n\":2147483648}").getInteger("n");   // -2147483648
```

根因：`convert` 里 `n.intValue()` / `n.shortValue()` / `n.byteValue()` 是 JDK 定义的回绕；
`JSONObject.getInteger` 用 `((Number) v).intValue()`。

修复：`convert` 里改用范围检查（`n.longValue()` 后比对 `Integer.MIN/MAX_VALUE`），
`getInteger` 对非 `Integer` 走同样的检查并抛 `JSONException`。

**安全意义的例子**：`{"quantity": 4294967297}` → `1`；`{"amountCents": 2147483648}` → 负数。
如果下游用 `quantity <= 0` 判断非法，攻击者可以直接绕过。

---

## V4（P1）泛型擦除字段

```java
class G<T> { public T t; public List<T> list; }

G<Integer> g = DingJson.parseObject("{\"t\":\"str\",\"list\":[\"a\",\"b\"]}",
        new TypeReference<G<Integer>>() { });
g.t;            // ★ 字段里是 String
(Integer) g.t;  // ClassCastException —— 在**调用点**炸，不是解析时
```

`TypeReference<G<Integer>>` 的 `T` 绑定没有传递到字段解析，`T` 擦除成 `Object`，
`convert` 对 `Object` 走兜底返回原值。`List<T>` 同样。

实际影响：解析"成功"，坏数据留在对象里，等业务代码强转时在**不可控的位置**抛 CCE。
修复需要把 `TypeReference` 的 type argument 绑定成 `TypeVariable → Type` 的 map 传进字段转换，
成本中等。至少应在文档里明确"`T` 字段不做类型校验"。

---

## V5（P1）数组元素转换失败 → null

```java
class Arr { public String[] s; }
// 见 V1c：整个数组引用被替换；而元素级转换失败时元素保持 null
```

元素级失败（如 `int[]` ← `["a"]`）会在 `Array.set` 处抛 `ArrayStoreException`，
该异常被外层包装成 `JSONException`（可接受），但 `String[]` ← `[1,2]` 会先触发 V1c 的
整字段覆盖。修完 V1 后需重新验证元素级行为。

---

## V6（P2）BigDecimal 极端指数

```java
Object v = DingJson.parse("1e2147483648");     // 接受！
BigDecimal bd = (BigDecimal) v;
bd.scale();        // -2147483648
bd.setScale(2);    // ArithmeticException: Underflow   ← 下游炸
bd.toPlainString() // 1e99999999 就分配 1 亿字符；1e2147483648 直接 OOM
```

库把 `1e400`、`1e999999999`、`1e2147483648` 都保留成 `BigDecimal`（为了不丢精度，合理），
但**没有给出安全阀**。一个 24 字节的载荷就能让任何调用 `toPlainString()`/`stripTrailingZeros()`/
算术运算的下游程序 OOM 或抛 `ArithmeticException`。

建议：`|scale|` 超过阈值（如 10000）时直接抛 `JSONException`，或者只允许到
`Double` 可表示范围的邻近值，并把这些极端值留在 `Double`（`0`/`Infinity` 会被现有
`failOnNonFinite` 拦住）。

---

## V7/V8（P2）深度上限与小栈线程

```
主线程          depth 512 → ok
256KB 栈线程    数组 depth 400/511 → ok（Attack2 实测）/ StackOverflowError（Attack1 实测）
256KB 栈线程    对象 depth 511 → StackOverflowError
```

结论：**`MAX_DEPTH = 512` 对 256 KB 栈（容器、虚拟线程、部分 Servlet 容器）不安全**，
而且是否抛 `SOE` 取决于 JIT 是否已编译该递归（帧大小不同）—— **不确定性本身就是风险**：
同一份载荷在预热前后行为不同。

更关键的是 `parse(String)` 入口**不捕获** `StackOverflowError`：

```java
// DingJson.java:97
public static <T> T parseObject(String text, Class<T> clazz) {
    return JSONSerializer.deserializeFromReader(new JSONReader(text), clazz);  // 无 try/catch
}
```

而 `deserializeBean` / `deepCopy` / `JSONPath` 都有 `catch (StackOverflowError)`。
`Error` 逃逸到 Servlet 容器通常直接 500 并可能污染线程池。

修复（二选一或都做）：
1. `DingJson.parse*` 入口包 `catch (StackOverflowError)` → `JSONException`
2. 深度上限做成配置项（默认 128 更安全），并把 512 作为上限

---

## V9（P3）大小写变体字段名污染精确匹配

```java
class Coll { public String name; public String NAME; }

DingJson.parseObject("{\"name\":\"x\"}", Coll.class);   // name=null, NAME="x"  ← 写错字段！
DingJson.parseObject("{\"NAME\":\"x\"}", Coll.class);   // name=null, NAME="x"  ← 正确
```

根因（`JSONSerializer:998`）：

```java
for (FieldInfo f : fieldsOf(type)) {
    map.put(f.jsonName, f);
    map.put(f.jsonName.toLowerCase(Locale.ROOT), f);   // ← 后声明字段覆盖前者的精确键
}
```

`NAME` 字段的 `toLowerCase` 就是 `"name"`，把 `name` 字段的精确映射覆盖掉了。
**即使不开 `caseInsensitiveFields` 也会触发**，因为 map 是同一份。

影响：字段错位写入。如果 `name` 是审计字段、`NAME` 是展示字段，攻击者可以用 `"name"` 写 `NAME`。
修复：精确 map 与 lower map 分开存，或 `map.putIfAbsent(lower, f)`。

---

## V10–V14（P3）

**V10** `DingJson.eval(json, "$.a", Integer.class)` 在 `{"a":"str"}` 上抛
`ClassCastException`（不是 `JSONException`），违背"所有错误都是 JSONException"的契约，
Web 层会变成 500 MSI 而非 400。

**V11** 所有字段级错误都归并成 `Cannot deserialize to class X`，**没有字段名、没有值、没有位置**：

```
{"outer":{"inner":{"num":"not-a-number"}}}  →  Cannot deserialize to class Inner
```

安全审计/告警系统无法从日志看出攻击打的是哪个字段，也无法区分"攻击"和"用户输入错误"。
建议消息带 `field path + jsonName`（值不要带，避免日志注入）。

**V12** `U+2028` / `U+2029` 原样输出。JSON 合法，但在 `<script>var x = {...}</script>` 场景
是 JS 行终止符（ES2019 前直接语法错误，之后仍需防范 `JSONP`/`eval` 链）。
加进 `ESCAPE` 表成本为零（这两个码点不在 0x00–0x7F，需要单独判断）。

**V13** `Map` 的 `null` 键输出 `{"null":1}` —— 键语义丢失，且与字符串 `"null"` 键无法区分。

**V14** `maxArraySize` 只在便捷方法（`readIntArray`/`parseArray` 等）里生效；
用 `while (r.hasNext()) r.readValue()` 手动循环则**完全不受限**。

```
readIntArray 10 vs max=5   →  JSONException  ✓
manual readValue loop      →  返回 10        ✗
```

不是漏洞（上限是"防呆"不是"安全边界"），但必须在文档里写明，否则使用者会误以为
配了 `maxArraySize` 就安全。

---

## 已确认防住（无需处理）

实测 226 条载荷中 **105 条干净 REJECT（全部是 JSONException，零内部异常泄漏）**：

| 类别 | 覆盖 |
|---|---|
| 语法畸变 55 条 | 未终止串/数组/对象、尾逗号、单引号、裸键、`01`/`1.`/`.5`/`+1`/`--1`/`0x10`、`NaN`/`Infinity` 字面量、注释、`\u12`/`\uZZZZ`/`\x41` 非法转义、尾随垃圾、BOM(`\uFEFF` + UTF-8 BOM)、`null` 输入、空输入 |
| 数字 | 超 `long` 拒绝、`checkedInteger` 边界正确（`-9223372036854775808` ✓）、`1e309` → BigDecimal 不丢精度、著名 `2.2250738585072011e-308` 正确 |
| 编码 | 非法 UTF-8、overlong UTF-8、BOM 全部拒绝（`CodingErrorAction.REPORT`）✓ |
| 资源 | 10 万层嵌套、5 MB 未终止字符串、12 MB 超长串、10 万元素超限 —— 全部干净 JSONException |
| 上限 | 树/流式/批量 API 的 `maxStringLength`、`maxArraySize`、`maxObjectSize`、`maxInputBytes` 全部生效（键名也检查）✓ |
| 注入 | `< > & = '` 全部转义，**键名也转义**，round-trip 一致；`</script>`、`]]>`、`${7*7}` 全被转义 ✓ |
| 代理项 | 孤立代理项输出转义为 `\uD800` ✓（输入侧接受，见下） |
| 日期 | `2025-13-45`、`2025-02-30`、`Instant` 垃圾串全部拒绝（`DateTimeFormatter` 严格）✓ |
| 一致性 | `isValid` 与 `parse` 判定 **21/21 完全一致**（无校验绕过）✓ |
| AutoType | **不存在**：`{"@type":"java.lang.Runtime"}` 只是普通键 ✓ |
| JSONPath | 递归下降无组合爆炸（200×200 树 `$..a..a..a` 0 ms；100k 数组 `$..*` 5 ms）；路径长度 1 MB 线性 ✓；`$[2147483648]` 拒绝 ✓ |
| Codec | 无反射注入面；`Class.forName` 只拼接 `type.getName() + "$$DingJsonCodec"`（类名来自已加载类，不可控）✓ |

两点观察（非缺陷）：

- **孤立代理项输入被接受**（`"\uD800"` 产生含孤立代理项的 `String`，`getBytes(UTF_8)` 时变 `?`）。
  RFC 8259 §8.2 允许但不建议，Gson/Jackson 同样接受。若要求严格，可在 `decodeEscapes` 里校验配对。
- `0.000…0001`（2 万个 0）→ `0.0`、`0.999…9` → `1.0`：double 语义下的正确舍入，非缺陷。

---

## 建议的修复顺序

| 顺序 | 项 | 成本 | 理由 |
|---|---|---|---|
| 1 | **V1** `putField` 类型校验 + 转换兜底抛错 | 小（~10 行） | JVM 崩溃，一个字段即可触发 |
| 2 | **V7/V8** `parse` 入口 catch SOE + 深度可配 | 小 | `Error` 逃逸，容器场景必现 |
| 3 | **V2/V3** 窄整型范围检查 | 小 | 业务校验可被绕过 |
| 4 | **V6** BigDecimal 指数阈值 | 小 | 24 字节载荷 → 下游 OOM |
| 5 | **V9** 精确 map 与 lower map 分离 | 小 | 字段错位写入 |
| 6 | **V10/V11** 异常类型统一 + 消息带字段名 | 小 | 契约与可运维性 |
| 7 | **V4** 泛型 `TypeVariable` 绑定 | 中 | 需要改 `TypeReference` 传递链 |
| 8 | **V12/V13/V14** 加固 | 极小 | 一行/文档 |

修复后应把本报告的 POC 全部转成回归测试（`DingJsonSecurityTest` 或新增
`DingJsonTypeSafetyTest`），并在两套路径（Unsafe / `-Ddingjson.unsafe=false`）下都跑。

## V14 的处理方式（不修代码）

`maxArraySize` 在**解析器自己产出数组**的所有路径上生效：`parse`/`parseArray`、
`JSONReader.readValue()`、批量方法（`readIntArray`/`readLongArray`/`readDoubleArray`/
`readStringArray`/`readObjectArray`）。

唯一不生效的是**调用方手动逐元素循环**：

```java
r.readBeginArray();
while (r.peek() != ']') { r.readString(); r.skipComma(); }   // 不计数
r.readEndArray();
```

这种模式下"元素"只存在于调用方的 `while` 里，读取器看不到边界。要强制计数就得让
`skipComma()` 维护数组/对象的嵌套状态栈，而 `skipComma()` 同时用于对象成员分隔，
计数会把对象成员数混进数组元素数（`[{a,b},{c,d}]` 直接误报）。

结论：这是**低层 API 的显式契约**——用 `readValue()`/批量方法即自动受上限保护，
手动循环由调用方负责。`docs/usage.md` 的流式章节已写明。这不算漏洞：要触发它，
攻击者必须能让服务端代码改写查询循环，与"已能执行任意循环"等价。

## 复现方式

攻击代码在 `/tmp/attack/{Attack,Attack2,Crash,Verify,Verify2,V3}.java`：

```bash
cd /Users/zhongxiaotian/IdeaProjects/DingJson
javac -cp target/classes -d /tmp/attack/out /tmp/attack/Attack2.java
java -cp "/tmp/attack/out:target/classes" Attack2        # 226 条攻击用例
java -cp "/tmp/attack/out:target/classes" V3             # V1c：SIGBUS
java -Ddingjson.unsafe=false -cp "/tmp/attack/out:target/classes" V3   # 干净失败
```
