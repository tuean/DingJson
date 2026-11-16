# DingJson 技术架构

这份文档讲三件事：**为什么这样设计**、**核心处理逻辑怎么走**、**关键代码在哪**。
使用方式见 [usage.md](usage.md)，性能数据见 [../BENCHMARK.md](../BENCHMARK.md) 与
[optimization-report.md](optimization-report.md)。

- [1. 设计目标与取舍](#1-设计目标与取舍)
- [2. 总体结构](#2-总体结构)
- [3. 输入层：JSONTokener](#3-输入层jsontokener)
- [4. 建树解析：JSONParser](#4-建树解析jsonparser)
- [5. 流式解析：JSONReader](#5-流式解析jsonreader)
- [6. 序列化：JSONSerializer](#6-序列化jsonserializer)
- [7. Bean 反射层](#7-bean-反射层)
- [8. Bean 反序列化](#8-bean-反序列化)
- [9. 编译期 codec](#9-编译期-codec)
- [10. 安全设计](#10-安全设计)
- [11. 性能工程](#11-性能工程)
- [12. 被否决的方案](#12-被否决的方案)
- [13. 代码地图](#13-代码地图)
- [14. 测试策略](#14-测试策略)
- [15. 扩展指南](#15-扩展指南)

---

## 1. 设计目标与取舍

| 目标 | 具体含义 | 代价 |
|---|---|---|
| 零依赖 | 运行时只用 JDK，无第三方 jar | 不能复用 Jackson 的成熟实现，全部自研 |
| Java 8 兼容 | `source/target = 8` | 不能用 `var`、`List.of`、records、`StringBuilder.isEmpty()` |
| 企业级安全 | 有上限、无 AutoType、转义正确、错误可 catch | 一部分性能（深度/长度检查） |
| 性能 | 与 fastjson2/Jackson 同量级 | 需要 Unsafe、手写扫描器、codegen |

**明确的取舍**：宁可性能比 fastjson2 低 20%，也不引入 CVE 生态和依赖地狱；
宁可多几十行手写扫描器，也不在热路径上装箱。

三个数字化的目标（全部有测试/基准支撑）：

1. 解析/序列化小对象在**微秒**级（1.1 μs / 0.18 μs）
2. 解析过程**零中间装箱**（`JSONReader` 直接读原生类型）
3. 所有失败都是可 catch 的 `JSONException`，绝不是 `StackOverflowError` 或 `OOM`

---

## 2. 总体结构

```
                        ┌──────────────────────────────────────┐
   输入 String/bytes ──▶ │ JSONTokener   字符数组 + 位置游标     │
                        └──────────────┬───────────────────────┘
                                       │
                    ┌──────────────────┴───────────────────┐
                    ▼                                      ▼
        ┌───────────────────────┐              ┌──────────────────────┐
        │ JSONParser   建树      │              │ JSONReader  流式     │
        │ → JSONObject/JSONArray │              │ → 直接读标量/Bean    │
        └───────────┬───────────┘              └──────────┬───────────┘
                    │                                     │
                    ▼                                     ▼
        ┌───────────────────────┐              ┌──────────────────────┐
        │ JSONSerializer        │              │ JSONSerializer       │
        │ deserialize(树→Bean)  │              │ deserializeFromReader│
        └───────────────────────┘              └──────────────────────┘
                    │                                     │
                    └──────────────┬──────────────────────┘
                                   ▼
                        ┌────────────────────────┐
                        │ FieldInfo + UnsafeFields│  ← 反射回退
                        │ 或生成的 $$DingJsonCodec│  ← 编译期 codec
                        └────────────────────────┘

  序列化只有一条链：
        Object ──▶ JSONSerializer.serialize(分发表) ──▶ StringBuilder ──▶ String/bytes
                  └─ Bean → serializeBean → codec 或 FieldInfo/Unsafe
```

**为什么解析有两条链、序列化只有一条？**

- 解析的目标是"谁来承载结果"：树（`JSONObject`）和 Bean 的读取模式完全不同，
  共用一套代码会引入大量 `if (targetIsTree)` 分支，反而更慢更乱。
- 序列化的目标是"把值变成字符"，无论源头是树还是 Bean，写字符的逻辑是同一套，
  只有"取一个字段的值"这一小段不同（`FieldInfo` vs `Map.Entry`），没必要拆两份。

---

## 3. 输入层：JSONTokener

`src/main/java/com/dingjson/parser/JSONTokener.java`（773 行，最底层）

**核心决策：把输入转成 `char[]` 后直接按下标访问，不用 `Reader`。**

```java
JSONTokener(String source, DingJsonConfig config) {
    this.sourceString = source;
    this.source = source.toCharArray();      // 一次拷贝，换来全程零边界检查
    ...
}
```

代价是 `String → char[]` 的一次拷贝（约 2N 字节），换来的是扫描循环里
`src[i]` 直接读，没有 `charAt` 的边界检查、没有 `Reader.read()` 的方法调用。
这是所有高性能 JSON 库的共同选择。

### 3.1 字符串读取：用 SIMD 索引找边界

读字符串时不逐字符判断"是不是引号"，而是用 `String.indexOf`（JDK 里是 SIMD intrinsic）：

```java
String readString() {
    int p = position + 1;                       // 跳过开头的 "
    int q = this.sourceString.indexOf('"', p);  // 找结束引号
    int b = this.sourceString.indexOf('\\', p); // 找第一个转义
    if (b < 0 || b > q) {
        // 无转义：直接 slice，一次拷贝
        String s = this.sourceString.substring(p, q);
        checkStringLength(s.length());
        position = q + 1;
        return s;
    }
    return decodeEscapes(source, source.length, p, b);   // 有转义才走慢路径
}
```

绝大多数 JSON 字符串没有转义，所以主流路径是**两次 SIMD 搜索 + 一次 substring**，
没有逐字符循环。`skipString()` 是同一套逻辑但不产生 `String`，供 `skipValue()` /
`isValid()` 使用。

### 3.2 转义解码：只拷贝一次

`decodeEscapes` 用 `char[]` 原地拼装，遇到 `\uXXXX` 走 `hexValue`（手写位运算，
不查表不 `parseInt`），未转义区间整段 `StringBuilder.append(src, from, to)`。
**不做"逐字符 append"**，避免 N 次小拷贝。

### 3.3 控制字符校验：并入扫描，不单独预扫

RFC 8259 禁止字符串里出现裸控制字符（`< 0x20`）。早期实现是构造 `JSONTokener` 时
对整个输入预扫一遍，实测初始化 1.82 μs → 0.14 μs（去掉预扫后）：
现在用 `checkControlChars(from, to)` 并入字符串读取的分支里，只在**无转义快路径**上
对已确定的区间做一次紧循环（`for` + `c < 0x20` 判断，可向量化），有转义时在
`decodeEscapes` 里顺带检查。

### 3.4 数字解析：整数与浮点分开

**整数**走 `checkedInteger(from, to)`：

```java
private long checkedInteger(int from, int to) {
    int digits = to - from;
    if (digits > 18) {                    // 19 位才有溢出风险
        return Long.parseLong(sourceString, from, to, 10);   // 回退 JDK
    }
    long v = 0;
    for (int i = from; i < to; i++) {     // 18 位以内，long 装得下，零检查
        v = v * 10 + (source[i] - '0');
    }
    return v;
}
```

18 位以内不可能溢出，所以循环里**没有任何边界判断**；19 位才回退 `Long.parseLong`。

**浮点**走 `fastDouble(char[], from, to)`，这是"零分配浮点解析"：

```java
static double fastDouble(char[] src, int from, int to) {
    // 手写扫描：符号、整数部分、小数部分、指数
    // 条件：尾数 ≤ 15 位有效数字 且 |exp| ≤ 22
    if (mantissaDigits <= 15 && Math.abs(exp) <= 22) {
        // 一次乘/除，结果与 Double.parseDouble 位相等（正确舍入）
        return negative ? -(double) mantissa * POW10[exp] : ...
    }
    return Double.parseDouble(new String(src, from, to - from));   // 超范围回退
}
```

为什么是 15 位 / 22 次：`double` 有 53 位尾数 ≈ 15.95 位十进制，15 位以内的整数
在 double 里**精确可表示**；10^22 是 double 里精确可表示的最大 10 的幂。
在这两个条件下，一次乘除得到的就是正确舍入的结果，不需要 `BigDecimal`/`Double.parseDouble`
的慢路径（后者要构造 `String` + 走 `FloatingDecimal` 解析器）。

实测：读 10 万个 double，6883 μs / 17.3 MB → **2986 μs / 4.5 MB**（分配量降到 1/4）。
正确性由 `DingJsonNumberParseTest` 保证：2 万条随机字面量 + 34 个边界样例，
逐个比对 `doubleToLongBits` 与 `Double.parseDouble` 完全一致。

---

## 4. 建树解析：JSONParser

`src/main/java/com/dingjson/parser/JSONParser.java`（253 行）

主循环就是一个递归下降 + 一层深度检查：

```java
private Object readValue(int depth) {
    if (depth > MAX_DEPTH) {                       // 512，防栈溢出 DoS
        throw new JSONException("Exceeded maximum nesting depth of " + MAX_DEPTH);
    }
    char c = tokener.nextNonWhitespace();
    switch (c) {
        case '"': return tokener.readString();
        case '{': return readObject(depth + 1);
        case '[': return readArray(depth + 1);
        case 't': tokener.backup(1); return tokener.readKeyword("true", Boolean.TRUE);
        ...
        default:
            if (c == '-' || (c >= '0' && c <= '9')) {
                tokener.backup(1);
                return tokener.readNumber();
            }
            throw new JSONException("Unexpected character '" + c + "' ...");
    }
}
```

两个细节：

1. **`backup(1)` 而不是预读**：`readKeyword("true")` / `readNumber()` 需要看到首字符，
   所以把游标退一格交给它们。比传参 `readNumber(c)` 少一层判断。
2. **深度检查在入口**，不是"先递归再检查"，所以栈上最多 512 帧。

### 4.1 `skipValue()`：纯扫描，不物化

`isValid()` 和 `JSONReader.skipValue()` 都不需要值本身，只需要知道"值到哪结束"。
为此有独立的扫描链：

```
skipValue(depth) → skipObject(depth) / skipArray(depth) → skipString() / skipNumber()
```

- `skipString()`：复用 `indexOf` 找边界，但**不 substring**、不解码转义（只检查有没有非法转义）
- `skipNumber()`：只校验数字语法（符号、整数、小数、指数），**不解析成数值**
- 全程零对象分配

实测 `isValid(complex)` 1.729 μs / 4128 B → **0.591 μs / 1032 B**（2.93x，分配降 4 倍）。

---

## 5. 流式解析：JSONReader

`src/main/java/com/dingjson/parser/JSONReader.java`（675 行）

对外是 token 游标（`readBeginObject` / `readKey` / `readValue` …），对内是一组
**手写融合扫描器**。设计要点：

### 5.1 结构断言，而不是信任调用方

```java
public JSONReader readBeginObject() {
    skipWs();
    if (position >= length || chars[position] != '{') {
        throw new JSONException("Expected '{' at position " + position);
    }
    position++;
    return this;
}
```

每个 `readX` 都先断言当前字符，不匹配立刻抛带位置的 `JSONException`。
这让"调用方状态机写错"变成明确错误，而不是解析出乱七八糟的数据。

### 5.2 数组批量读取：一次扫描，不逐个方法调用

`readIntArray()` 这类方法把"读 `[`、循环读数字、读 `,`、读 `]`"压进一个循环：

```java
public int[] readIntArray() {
    skipWs();
    expect('[');
    int[] buf = new int[16];          // 小起步，倍增扩容
    int n = 0;
    while (true) {
        skipWs();
        char c = chars[position];
        if (c == ']') { position++; break; }
        if (n == buf.length) buf = Arrays.copyOf(buf, n << 1);
        buf[n++] = checkedInt(...);   // 直接算，不经过 String/Long
        skipWs();
        if (chars[position] == ',') position++;
    }
    return Arrays.copyOf(buf, n);     // 收缩到精确长度
}
```

实测 10 万个 int：764 μs → **586 μs**（去掉逐位范围判断、去掉逐元素方法边界，
数字用 `checkedInteger` 一次算完）。

`readDoubleArray()` 用 `fastDouble`，10 万元素 6883 μs / 17.3 MB → **2986 μs / 4.5 MB**。

> 注：曾尝试"先扫一遍数元素个数再定容"，实测净亏 —— 省的两次 memcpy（~80 μs）
> 比不上多扫一遍 1.1 M 字符（数百 μs）。已回退，保留倍增。

### 5.3 与 `JSONParser` 的关系

`JSONReader.readValue()` 在遇到 `{` 或 `[` 时会调用 `JSONParser` 建成树（子树），
所以"流式"只对流的最外层生效；嵌套对象仍是树。这是有意的：
外层的流式让调用方可以逐条处理百万条记录，内层的树让单条记录处理简单。

---

## 6. 序列化：JSONSerializer

`src/main/java/com/dingjson/serializer/JSONSerializer.java`（1552 行，最复杂的文件）

### 6.1 分发表取代 instanceof 链

原来的 `serialize()` 是 12 层 `instanceof`（String → Number → Boolean → … → bean），
每次递归都要从头比较。现在第一次见到某个类时算出它的"分派编号"，之后只查表：

```java
private static final int D_STRING = 0, D_NUMBER = 1, D_BOOLEAN = 2, D_CHAR = 3,
        D_DATE = 4, D_CALENDAR = 5, D_UUID = 6, D_TEMPORAL = 7,
        D_JSON_OBJECT = 8, D_JSON_ARRAY = 9, D_MAP = 10, D_COLLECTION = 11,
        D_ARRAY = 12, D_BEAN = 13;

private static final ClassValue<Integer> DISPATCH_CACHE = new ClassValue<Integer>() {
    @Override protected Integer computeValue(Class<?> type) {
        // 保持与旧 instanceof 链完全相同的优先级：
        // JSONObject 是 Map、JSONArray 是 Collection，必须先判
        if (type == String.class) return D_STRING;
        ...
        if (JSONObject.class.isAssignableFrom(type)) return D_JSON_OBJECT;
        ...
        return D_BEAN;
    }
};

static void serialize(Object value, StringBuilder sb, int depth, DingJsonConfig config) {
    if (value == null) { sb.append("null"); return; }
    if (value instanceof String) { appendQuoted(sb, (String) value); return; }  // 最热的类型走短路
    switch (DISPATCH_CACHE.get(value.getClass())) {
        case D_NUMBER: ...
        ...
    }
}
```

`String` 保留短路是因为它出现在几乎每个对象里，省一次表查。
`ClassValue` 而不是 `HashMap<Class, Integer>`：无锁、随类卸载自动回收、命中成本接近字段读。

实测收益在噪声内（±3%），保留的理由是**结构简化**：12 个分支变成一个 switch，
新增类型只改一处。已在 commit 里标注"非提速项"。

### 6.2 字符串输出：一趟扫描，成段拷贝

`appendQuoted` 是序列化里最热的函数（每个键、每个字符串值都要过一遍）：

```java
static void appendQuoted(StringBuilder sb, String s) {
    sb.append('"');
    int i = 0;
    while (i < len) {
        char c = s.charAt(i);
        boolean loneSurrogate = ...;                       // 孤立代理项必须转义
        if ((c < 0x80 && ESCAPE[c]) || loneSurrogate) {    // 一张 128 项 boolean 表
            switch (c) { case '"': sb.append("\\\""); ... default: sb.append("\\u").append(HEX[...]) }
            i++;
        } else {
            int j = i + 1;
            while (j < len) {                              // 找"安全区间"的右端
                c = s.charAt(j);
                if (c < 0x80 && ESCAPE[c]) break;
                // 代理对整体通过，孤立代理项就地跳出让它被转义
                ...
                j++;
            }
            sb.append(s, i, j);                            // 整段一次拷贝
            i = j;
        }
    }
    sb.append('"');
}
```

关键点：

1. **`ESCAPE[]` 布尔表**（128 项）代替 12 个 `case` 的 `c == '"' || c == '\\' || ...`
2. **成段 append** 而不是逐字符 append：`StringBuilder.append(CharSequence, start, end)` 是
   `System.arraycopy`，比 N 次 `append(char)` 快得多
3. **手写 HEX 表**代替 `String.format("\\u%04x", c)`
4. **直接写目标缓冲**：早期版本是 `quote()` 返回 `String` 再 `append`，每次多一个中间
   `String`。改成 `appendQuoted(StringBuilder, String)` 后：
   序列化 1000 串 43.6 μs / 220 B → **19.5 μs / 76 B**（2.2x，分配降 2.9 倍）

注意 `< > & = '` 也在这张表里（Gson 同款 HTML 转义）。这是安全默认，**不可关闭**。

### 6.3 pretty 序列化：独立的递归，不是后处理

`prettySerialize` 是另一套递归（`prettySerializeJSONObject` / `…Array` / `…Map` /
`…Collection` / `…Bean`），**不是**先紧凑再插缩进。原因：插缩进要重新解析，
后处理比直接递归慢得多，也容易破坏字符串内的空白。

`JSONWriter` 是第三条路径（链式 API），它有独立的 64 层状态栈：

```java
private final int[] state;      // 0 = root, 1 = object, 2 = array, 3 = key-waits-value
private final boolean[] first;  // 每层"还没写过元素"，用来决定逗号
```

`state[depth] == 3` 就是"刚调过 `key()` 还没给值"的状态 —— `endObject()` 看到它直接抛异常，
这就是"绝不输出 `{"a":}`"的实现方式。

---

## 7. Bean 反射层

Bean 序列化的核心不是"调用反射"，而是"尽量别调用反射"。

### 7.1 FieldInfo：每个类算一次

```java
private static final class FieldInfo {
    final String name, jsonName;
    final Class<?> type;
    final boolean serializable, deserializable;
    final String format;          // @JSONField(format)
    final String key;             // 预先 quote 好的 "\"name\":" 片段
    final Field field;            // 回退用
    final long offset;            // Unsafe 偏移（可用时）
    final int kind;               // 预计算的类型编号，避免每字段 8 路 instanceof
}
```

字段发现 `discoverFields(Class)` 沿父类链走，跳过 `static`/`transient`/`synthetic`，
结果缓存在 `ClassValue<FieldInfo[]>`：

```java
private static final ClassValue<FieldInfo[]> FIELD_CACHE = new ClassValue<FieldInfo[]>() {
    @Override protected FieldInfo[] computeValue(Class<?> type) { return discoverFields(type); }
};
```

四层缓存，全在 `ClassValue` 里（无锁、可回收）：

| 缓存 | 内容 | 为什么 |
|---|---|---|
| `FIELD_CACHE` | `FieldInfo[]` | 字段发现要遍历父类链 + 读注解 |
| `FIELD_MAP_CACHE` | `Map<String, FieldInfo>` | 反序列化按键查字段 |
| `CTOR_CACHE` | 无参 `Constructor` | 每个解析实例都要 new |
| `CODEC_CACHE` | 生成的 codec 或 `NO_CODEC` | 见第 9 节 |
| `DISPATCH_CACHE` | 序列化分派编号 | 见 6.1 |

### 7.2 `key` 预引用：一次构建，永远复用

早期版本每个实例的每个字段都要 `quote(jsonName)` 一次：

```java
sb.append('"').append(jsonName).append("\":")     // 每次都要转义检查
```

现在 `FieldInfo` 构造时就算好 `"name":` 这个片段，热路径变成一次 `append(String)`。
序列化 bean 0.114 μs → 0.090 μs（~1.3x）。

### 7.3 Unsafe 直访 + 反射回退

```java
static final boolean AVAILABLE;      // Unsafe 存在 且 未指定 -Ddingjson.unsafe=false

private static void putField(Object bean, FieldInfo f, Object value) {
    if (UnsafeFields.AVAILABLE && f.offset != 0) {
        switch (f.kind) {                 // 预计算的 kind，一次跳转
            case KIND_INT: UnsafeFields.putInt(bean, f.offset, (Integer) value); return;
            ...
            default: UnsafeFields.putObject(bean, f.offset, value); return;
        }
    }
    try { f.field.set(bean, value); } catch (IllegalAccessException e) { throw new JSONException(...); }
}
```

为什么用 Unsafe 而不是 `Field.set`：`Field.set` 每次都要过访问检查 + 装箱 + 类型校验；
Unsafe 是一次带偏移的内存写，与手写 `PUTFIELD` 同一量级。

**但 Unsafe 在 JDK 25 已标记为将要移除**，所以：

- `UnsafeFields.AVAILABLE` 在 JDK 移除 Unsafe 时自动为 false，全部走反射
- `pom.xml` 里跑两遍全部测试：一遍正常（Unsafe），一遍 `-Ddingjson.unsafe=false`（纯反射）
- 两条路径都必须 508 个测试全绿

这是"依赖内部 API 但不被它绑架"的标准做法：把风险限定在**性能**，不影响**功能**。

---

## 8. Bean 反序列化

两条路径，行为一致：

### 8.1 流式（`deserializeFromReader`）

```java
private static Object deserializeFromReader(JSONReader reader, Class<?> targetType, int depth, ...) {
    if (isScalar(targetType)) return readScalar(reader, targetType);   // 标量直接读
    Object bean = newInstanceOf(targetType);
    reader.readBeginObject();
    while (reader.hasNext()) {
        String key = reader.readKey();
        FieldInfo f = fieldMap(targetType).get(key);      // 哈希查一次
        if (f == null || !f.deserializable) { reader.skipValue(); continue; }   // 未知字段：纯扫描跳过
        putField(bean, f, readFieldValue(reader, f, depth + 1, config));   // 按声明类型读
        reader.skipComma();
    }
    reader.readEndObject();
    return bean;
}
```

关键点：**按字段的声明类型读**。知道目标字段是 `int`，就直接 `reader.readInt()`，
不会先读成 `Integer` 再转换；知道是 `String`，就直接 `readString()`。
未知字段用 `skipValue()` 纯扫描跳过（不物化、不分配）。

`readScalar` 处理"JSON 里的类型和目标类型不一致"的宽松转换（`"1"` → `1`、
数字 → 字符串等），逻辑是显式的 if 链，每种类型一段，没有通用反射。

### 8.2 树路径（`deserializeBean(JSONObject, Class)`）

```java
T instance = newInstanceOf(targetType);
for (FieldInfo f : fieldsOf(targetType)) {
    if (!f.deserializable) continue;
    if (!source.containsKey(f.jsonName)) continue;
    Object raw = source.get(f.jsonName);
    Object typed;
    if (raw instanceof String && f.format.length() > 0 && f.type == Date.class) {
        typed = parseDate((String) raw, dateFormatter(f.format));    // 字段级格式
    } else {
        typed = deserialize(raw, f.field.getGenericType());          // 递归转
    }
    if (typed != null || raw == null) putField(instance, f, typed);
}
```

`f.field.getGenericType()` 让 `List<User>`、`Map<String, Order>` 这类嵌套泛型也能还原
（`deserializeInternal` 处理 `ParameterizedType` / `GenericArrayType` / 裸 `Class`）。

---

## 9. 编译期 codec

运行期的上限是 `FieldInfo` 查表 + kind 分派 + Unsafe 写。要突破只能在**编译期**生成代码。

### 9.1 组成

| 角色 | 文件 | 职责 |
|---|---|---|
| 标记注解 | `annotation/DingJsonSerializable.java` | `@Retention(SOURCE)`，只给处理器看 |
| 契约 | `codec/DingJsonCodec.java` | `write(T, StringBuilder, int, DingJsonConfig)` / `read(JSONObject, DingJsonConfig)` |
| 生成器 | `processor/DingJsonCodecProcessor.java` | `AbstractProcessor`，生成 `<BinaryName>$$DingJsonCodec` |
| 运行期支撑 | `serializer/CodecSupport.java` | 与反射路径逐分支对齐的写/读助手 |
| 发现与回退 | `JSONSerializer.codecOf()` | `ClassValue` + `Class.forName`，找不到即 `NO_CODEC` |

### 9.2 生成的代码

```java
public final class User$$DingJsonCodec implements DingJsonCodec<User> {
    @Override
    public void write(User b, StringBuilder out, int depth, DingJsonConfig config) {
        out.append('{');
        CodecSupport.writeInt(out, KEY_id, b.id);                       // GETFIELD
        CodecSupport.writeRef(out, KEY_name, b.name, depth, config);
        out.append('}');
    }

    private static final String KEY_id = CodecSupport.key("id");        // 一次构建

    @Override
    public User read(JSONObject src, DingJsonConfig config) {
        User b = new User();                                           // 直接 new
        if (src.containsKey("id"))   b.id   = CodecSupport.toInt(src.get("id"), config);
        if (src.containsKey("name")) b.name = (String) CodecSupport.convert(src.get("name"), String.class, config);
        return b;
    }
}
```

代码里没有 `reflect`、没有 `Unsafe`、没有 `instanceof`、没有 `Method.invoke`。

### 9.3 关键设计决策

**为什么生成 Java 源码而不是字节码？**
生成 `.java` 让 javac 帮我们编译，不需要自带 classfile 写入器 —— 这是"零依赖"的硬约束。
当初否决 ASM 方案的理由（工程量 ~10x、边际 ~20%）在这里依然适用；不同的是
"生成源码"这条路成本低了一个数量级。

**为什么生成失败只发 NOTE 而不报错？**
注解是**纯加速**，不是功能依赖。处理不了的类（`private` 字段、泛型类、抽象类、
跨包不可见）必须安全退回反射，而不是让用户的构建挂掉：

```java
try {
    generate(type);
} catch (Abort abort) {
    note(element, "no codec generated (" + abort.getMessage()
            + "); DingJson keeps using reflection for " + type.getSimpleName());
}
```

`DingJsonCodecProcessorTest` 用真实 javac 在临时目录里编译四种"坏类"，
断言**编译成功 + 没有生成 codec**，把这个契约钉住。

**为什么不生成注册表？**
JVM 没有"编译期副作用"：生成的代码不会自己运行。所以自动发现只有两条路 ——
`Class.forName(类名 + "$$DingJsonCodec")`（0.286 μs/类，一次性）或 `ServiceLoader`
（实测 19.4 μs + 扫 classpath，反而更慢）。因此默认走 `Class.forName`，
需要"完全零反射"的场景用 `DingJson.registerCodec(...)` 显式注册（1 行/类）。
详见 [usage.md §11.3](usage.md#113-显式注册零反射)。

### 9.4 逐字节等价

codec 是同一件事的第二份实现，必须与反射路径**完全等价**。保证手段：

- `CodecSupport` 的每个助手方法都对应反射路径的一个分支（`writeInt` ↔ `case KIND_INT`，
  `toDate` ↔ 字段级 format 分支…）
- `CodecGenerationTest` 定义一对同形类（`CodecBean` 带注解、`ReflectBean` 不带），
  20 个字段覆盖全部类型，逐字节比对输出：紧凑、`writeNullFields(false)`、
  容器内嵌套、`NaN`/`Infinity`、HTML 转义、`@JSONField` 改名/只写、日期格式
- 测试套件跑三遍配置：Unsafe 路径、纯反射路径、`-Ddingjson.codec.disabled=true`

---

## 10. 安全设计

| 威胁 | 防线 | 代码位置 |
|---|---|---|
| 超大输入 OOM | `maxInputBytes`（默认 10 MB），字节/流入口边读边拒 | `DingJson.readAll` |
| 深嵌套栈溢出 | `MAX_DEPTH = 512`，三个入口（树/流/skip）都检查 | `JSONParser`、`JSONReader` |
| 超长字符串/巨大数组 | `maxStringLength` / `maxArraySize` / `maxObjectSize` | `JSONTokener.checkStringLength` 等 |
| XSS | `< > & = '` → `\u003c` 等，不可关闭 | `appendQuoted` 的 `ESCAPE[]` 表 |
| AutoType 漏洞 | **没有** AutoType；`@type` 只是普通键 | 设计上不存在 |
| lone surrogate 数据损坏 | 输出时转义，避免 UTF-8 编码替换成 `?` | `appendQuoted` 的 `loneSurrogate` 分支 |
| 日期宽松解析 | `DateTimeFormatter`（严格），不用 `SimpleDateFormat` | `dateFormatter` |
| 异常逃逸成 `Error` | 所有 `StackOverflowError` 在入口捕获转 `JSONException` | `DingJson` / `JSONSerializer` / `JSONPath` |

设计原则：**失败必须是可 catch 的 `JSONException`**。所以每个递归入口都有

```java
try {
    return deep(...);
} catch (StackOverflowError e) {
    throw new JSONException("Nesting too deep to deserialize", e);
}
```

因为容器/虚拟线程的栈可能只有 512 KB，`MAX_DEPTH` 在那种环境未必先触发，
所以两层都要有。

---

## 11. 性能工程

### 11.1 方法论

1. **先量再改**：每个候选都用 `/tmp` 下的探针实测，不做"看起来会快"的改动。
2. **交替对跑**：`git stash` 前后各跑一轮，多轮取最小，避开机器负载漂移
   （这台机器上 load 会冲到 40+）。
3. **测分配量**：用 `ThreadMXBean.getCurrentThreadAllocatedBytes()`，
   因为 GC 压力往往比 CPU 更决定吞吐。
4. **无收益就回退**：定容预扫描、`toJSONBytes` 直编 UTF-8 都被实测否决了。

### 11.2 结果总表（main → 本分支）

| 场景 | 吞吐 | 分配 |
|---|---|---|
| 解析成树 | 1.866 → **1.139 μs**（1.64x） | 5368 → 4280 B |
| 解析 `byte[]` | 2.501 → **1.476 μs**（1.69x） | 7480 → 6392 B |
| 解析 Bean | 0.497 → **0.333 μs**（1.49x） | 672 → 568 B |
| `isValid`（复杂） | 1.729 → **0.591 μs**（2.93x） | 4128 → **1032 B** |
| 序列化树 | 2.382 → **1.480 μs**（1.61x） | 8112 → 4272 B |
| 序列化 Bean | 0.296 → **0.181 μs**（1.64x） | 696 → 200 B |
| `readDoubleArray` 10 万 | 6883 → **2986 μs**（2.30x） | 17.3 MB → **4.5 MB** |

### 11.3 具体技术清单

| 技术 | 收益 | 对应 commit |
|---|---|---|
| `appendQuoted` 直写目标缓冲 | 2.2x / 分配 2.9x 降 | `1b169d4` |
| `skipString`/`skipNumber` 纯扫描 | `isValid` 2.93x | `f9b32b7` |
| 控制字符检查并入扫描（去预扫） | 初始化 1.82 → 0.14 μs | `336345b` |
| 流式数组融合扫描 + `checkedInteger` | `readIntArray` 1.3x | `42510d8` |
| `FieldInfo.kind` 预计算 + `ClassValue` | 结构简化（噪声内） | `7d50292` |
| 零分配 `fastDouble` | `readDoubleArray` 3x / 分配 4x 降 | `750fc47` |
| key 预引用 + 构造器缓存 | Bean 1.3x | `806ff19` |
| 分发表 + 编译期 codec | Bean 1.05–1.2x | `6397bc4` |
| `registerCodec`（零反射入口） | 去掉 0.286 μs/类 | `f9ec7a7` |

---

## 12. 被否决的方案

每个都是实测后否决，不是没想过：

| 方案 | 否决理由 |
|---|---|
| 数组定容预扫描 | 省两次 memcpy（~80 μs），代价是全输入多扫一遍（1.1 M 字符，数百 μs），净亏 |
| `toJSONBytes` 直编 UTF-8 | 2.202 → 2.139 μs（噪声内）。JDK 9+ 的 `StringBuilder` 是 Latin1 紧凑存储，`getBytes(UTF_8)` 走 SIMD，String→bytes 几乎免费；改造要重写整条序列化链 |
| 多版本 jar + `VarHandle` | 只能从 jar 的 `META-INF/versions/9` 生效（classpath 测试走不到），要维护第二份字段访问实现，**零性能收益**。改为把"反射回退"纳入测试 |
| 运行期字节码生成（ASM 风格） | 工程量 ~10x，边际 ~20%，且要自带 classfile 写入器，与零依赖冲突。**改为编译期生成源码**（第 9 节） |
| `byte[]` 原生 tokener | 当前 `bytes → String → char[]` 有 3 次拷贝，但 `parse(byte[])` 只比 `parse(String)` 慢 0.1–0.3 μs。收益只在超大文档，要大改全部扫描器 |
| `readBooleanArray` 融合扫描 | 体积小、非热路径 |
| 编译期注册表（ServiceLoader / 索引文件） | 实测都比 `Class.forName` 慢（19.4 μs / 6.9 μs vs 0.286 μs），且引入增量编译的部分注册问题 |

---

## 13. 代码地图

```
src/main/java/com/dingjson/                     6421 行 / 21 个文件
├── DingJson.java                    886   门面：所有静态 API、输入源适配、上限校验
├── DingJsonMapper.java              289   绑定配置的门面实例
├── DingJsonConfig.java              191   不可变配置 + builder
├── JSONPath.java                    220   JSONPath 子集求值
├── TypeReference.java                46   泛型捕获
├── exception/JSONException.java      16   唯一异常类型
├── element/
│   ├── JsonElement.java              84   共同父类
│   ├── JSONObject.java              204   Map<String,Object> 视图
│   └── JSONArray.java               230   List<Object> 视图
├── parser/
│   ├── JSONTokener.java             773   ★ 字符级扫描：字符串/数字/转义/控制字符
│   ├── JSONParser.java              253   建树递归下降 + skipValue 纯扫描
│   └── JSONReader.java              675   流式 token 游标 + 数组批量读
├── serializer/
│   ├── JSONSerializer.java         1552   ★★ 序列化全链 + Bean 反射层 + codec 发现
│   ├── JSONWriter.java              203   链式流式写（带状态栈）
│   ├── CodecSupport.java            195   生成代码的助手（与反射分支对齐）
│   └── UnsafeFields.java            116   Unsafe 直访 + 回退开关
├── annotation/
│   ├── JSONField.java                53   字段级注解
│   └── DingJsonSerializable.java     30   编译期 codec 标记
├── codec/DingJsonCodec.java          34   生成代码的接口契约
└── processor/DingJsonCodecProcessor 371   注解处理器
```

读代码的推荐顺序：

1. `JSONTokener`（字符怎么读）→ 2. `JSONParser`（树怎么建）→ 3. `JSONSerializer`
的 `serialize`/`appendQuoted`（值怎么写）→ 4. `JSONReader`（流式怎么走）→
5. `FieldInfo` + `putField`/`serializeBean`（Bean 怎么映射）→ 6. `DingJsonCodecProcessor`
（代码怎么生成）

---

## 14. 测试策略

`mvn -o test` → **508 个测试**，跑两遍（Unsafe / 纯反射）= 1016 次执行。

| 测试类 | 覆盖 |
|---|---|
| `JSONTestSuiteTest` | JSONTestSuite 318 用例（100% 通过），语法边界 |
| `DingJsonParseTest` / `DingJsonSerializeTest` | 基本行为、数字、转义、日期 |
| `DingJsonBeanTest` | 字段映射、继承、注解、泛型 |
| `DingJsonConfigTest` | 每个配置项的正反向 |
| `DingJsonSecurityTest` / `DingJsonHardeningTest` | 攻击样例：深度炸弹、超长串、控制字符、lone surrogate、XSS、AutoType |
| `DingJsonEnterpriseTest` | 大文档、并发、流式、JSONPath |
| `DingJsonNumberParseTest` | 2 万随机字面量 + 34 边界，比对 `doubleToLongBits` |
| `UnsafeFieldsFallbackTest` | 强制 `-Ddingjson.unsafe=false` 后行为一致 |
| `CodecGenerationTest` | 生成 codec 与反射路径**逐字节**比对 |
| `DingJsonCodecProcessorTest` | 真实 javac：能生成 / 该拒绝时只发 NOTE |
| `ExplicitCodecRegistrationTest` | 显式注册生效、迟到注册被拒 |

风格：**不进则断言回退**。所有"加速路径"都有一组"等价性测试"，
所有"降级路径"（反射、无 codec）都必须跑同一套行为测试。
所以优化只影响速度，不影响语义 —— 这是这份代码敢用 Unsafe 和 codegen 的前提。

---

## 15. 扩展指南

### 加一个新支持的类型（如 `java.time.Duration`）

1. `JSONSerializer`：在 `serialize` 的分发表里加 `D_DURATION`，`computeValue` 里加判定
2. `executeSerialization` 里输出（`Duration` 适合输出 ISO 字符串）
3. 流式读取：`readScalar` 加一段 `if (type == Duration.class) return Duration.parse(reader.readString());`
4. 树路径：`convert` 加一段
5. `CodecSupport` 加对应助手（`convert` 会自动覆盖，通常不用改）
6. 测试：`DingJsonBeanTest` 加字段，`CodecGenerationTest` 的两个同形类同时加

**不要**在 `serialize` 里加 `instanceof` —— 走分发表，保持结构一致。

### 加一个配置项

1. `DingJsonConfig` 加 final 字段 + getter + builder 方法 + 构造参数
2. 校验放在 builder（如"负数抛 `IllegalArgumentException`"）
3. `defaults()` 里给默认值
4. 在使用处读 `config.xxx()`，注意 `config` 可能是 `null`（`normalize` 之后不会是）
5. `DingJsonConfigTest` 覆盖默认值 + 自定义值 + 边界

### 改热路径的规矩

1. 先写探针（`/tmp` 下，用 `ThreadMXBean` 测时间和分配）
2. `git stash` 前后交替跑 3 轮，取最小值，变化 < 5% 视为噪声
3. 没有可测收益的改动，commit message 里必须写明"非提速项"
4. 改完跑 `mvn -o test`，两遍都要绿
