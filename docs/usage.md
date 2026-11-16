# DingJson 使用文档

零依赖、Java 8 兼容的 JSON 库。本文覆盖全部公开 API；内部实现见
[architecture.md](architecture.md)，性能数据见 [../BENCHMARK.md](../BENCHMARK.md)。

- [1. 引入](#1-引入)
- [2. 快速开始](#2-快速开始)
- [3. 解析](#3-解析)
- [4. 序列化](#4-序列化)
- [5. 泛型：TypeReference](#5-泛型typereference)
- [6. 树模型](#6-树模型)
- [7. 流式读写](#7-流式读写)
- [8. JSONPath](#8-jsonpath)
- [9. 校验与深拷贝](#9-校验与深拷贝)
- [10. 配置](#10-配置)
- [11. 注解与编译期 codec](#11-注解与编译期-codec)
- [12. 支持的类型](#12-支持的类型)
- [13. 错误处理](#13-错误处理)
- [14. 并发与线程安全](#14-并发与线程安全)
- [15. 安全建议](#15-安全建议)
- [16. 性能建议](#16-性能建议)

---

## 1. 引入

### Maven

```xml
<dependency>
    <groupId>io.github.tuean</groupId>
    <artifactId>dingjson</artifactId>
    <version>1.1.0</version>
</dependency>
```

### 单文件版

`release/DingJson.java` 是一个可直接拷进项目的单文件版本（无需 Maven、无注解处理器）。
适合脚本、教学、或不允许引入新依赖的环境。功能与 jar 版一致，**不含**编译期 codec。

### 环境要求

- Java 8+（字节码 target 8）
- 运行时**零依赖**（`pom.xml` 里的 junit/jackson/fastjson 等全部是 `test` scope，只用于对比测试）

### JPMS

模块名 `com.dingjson`。不 `requires` 任何模块；`java.compiler` 只在注解处理器里用到，
是 `optional` 的，普通使用时不需要。

---

## 2. 快速开始

```java
import com.dingjson.DingJson;
import com.dingjson.element.JSONObject;

// 树模型
JSONObject obj = DingJson.parseObject("{\"name\":\"丁\",\"age\":30}");
String name = obj.getString("name");        // "丁"
int age = obj.getInteger("age");            // 30

// 任意结构
Object any = DingJson.parse("[1,2,3]");     // JSONArray

// 序列化
String json = DingJson.toJSONString(obj);   // {"name":"丁","age":30}

// Bean
User user = DingJson.parseObject(json, User.class);
String out = DingJson.toJSONString(user);
```

---

## 3. 解析

### 3.1 输入源 × 目标类型

`String` / `byte[]` / `InputStream` / `Reader` / `File` 五种输入，每种都有四类入口：

| 方法 | 返回 | 说明 |
|---|---|---|
| `parse(x)` | `Object` | 任意 JSON 值。对象→`JSONObject`，数组→`JSONArray`，标量→`String/Integer/Long/Double/Boolean/null` |
| `parseObject(x)` | `JSONObject` | 要求根是对象 |
| `parseObject(x, Class<T>)` | `T` | 映射成 Bean |
| `parseObject(x, TypeReference<T>)` | `T` | 带泛型的 Bean / 容器 |
| `parseArray(x)` | `JSONArray` | 要求根是数组 |
| `parseArray(x, Class<T>)` | `List<T>` | 元素映射成 Bean |
| `parseArray(x, TypeReference<T>)` | `List<T>` | 带泛型的元素类型 |

```java
DingJson.parseObject(new File("a.json"), User.class);
DingJson.parseArray(bytes, Order.class);
DingJson.parseObject(inputStream, new TypeReference<Map<String, List<User>>>() { });
```

所有入口都有带 `DingJsonConfig` 的重载。`byte[]`/流/File 入口受 `maxInputBytes` 限制（默认 10 MB）。

### 3.2 数字的类型选择（树模型）

解析成 `Object` 时不装箱成固定类型，而是选最小可容纳的类型：

| 输入 | 结果 |
|---|---|
| `1` | `Integer` |
| `3000000000` | `Long` |
| 超出 `long` 的整数、或带小数且精度可能丢失 | `BigDecimal`（保留原样，不降级为 double） |
| `1.5` | `Double` |
| `1e999` | `BigDecimal`（不会变成 `Infinity` 丢数据） |

需要确定类型时用 `getInteger/getLong/getDouble`。

### 3.3 两条解析链的区别

| 入口 | 实现 | 特点 |
|---|---|---|
| `parseObject(text, Class)`、`parseArray(text, Class)` | **流式**（`JSONReader`） | 不建中间树，边读边填字段，快；字段名大小写、宽松转换都在这里 |
| `parseObject(text, TypeReference)`、`parse(x)` 后自行转 | **树**（`JSONObject` → Bean） | 先建树再映射，慢一点，但泛型信息最完整 |

两者行为一致；`TypeReference` 走树路径是因为运行期需要 `java.lang.reflect.Type` 才能还原泛型参数。
流式路径的字段映射见 [3.4](#34-bean-字段映射规则)。

### 3.4 Bean 字段映射规则

输入时，字段来自：

1. `public` 字段，以及与 Bean 同包的 `protected`/包私有字段
2. 父类链上的同类字段（`java.lang.Object` 为止）
3. 跳过 `static`、`transient`、`synthetic` 字段

名字匹配：默认精确匹配字段名或 `@JSONField(name = ...)`；开启
`caseInsensitiveFields` 后忽略大小写。JSON 里多出来的字段被忽略，Bean 里缺失的字段
保持默认值（不会调用 setter，DingJson 只处理字段）。

类型转换是**宽松**的（与 Jackson 默认风格接近）：

```java
// 以下都能解析成 int a
{"a": 1}        // 数字
{"a": "1"}      // 字符串数字
{"a": true}     // Boolean.parseBoolean → false
```

`BigDecimal`/`BigInteger` 直接读原始数字文本，不经过 `double`，全精度无损。

**宽松不等于静默损坏**（1.0.1 起）。以下情况会抛 `JSONException` 而不是给出错值：

| 输入 | 目标字段 | 1.0.1 之前 | 现在 |
|---|---|---|---|
| `2147483648` | `int` | `-2147483648`（回绕） | 抛异常 |
| `70000` | `short` | `4464`（截断） | 抛异常 |
| `1e40` | `long`（`getLong`） | `Long.MAX_VALUE`（饱和） | 抛异常 |
| `"java.lang.Runtime"` | `Class<?>` | 原样写入 → 读取时 JVM 崩溃 | 抛异常 |
| `{"x":1}` | 接口/抽象类字段 | 原样写入 → 读取时 JVM 崩溃 | 抛异常 |
| 任意标量 | 数组字段 | 原样写入 → 读取时 JVM 崩溃 | 抛异常 |

小数进整型字段仍按 Java 规则**截断**（`1.9 → 1`，与 Jackson 一致），
超出目标类型范围才报错。异常消息带字段名：`Field 'age': For input string: "abc"`。

---

## 4. 序列化

```java
String json   = DingJson.toJSONString(obj);                 // 紧凑
String pretty = DingJson.toJSONString(obj, true);           // 缩进 2 空格
byte[] bytes  = DingJson.toJSONBytes(obj);                  // UTF-8
DingJson.writeTo(new File("out.json"), obj, true);          // 直接写文件
DingJson.writeTo(outputStream, obj);                        // 直接写流
```

带配置的重载都存在。`writeTo(File)` 内部是缓冲写，不会把整个字符串拼在内存里
（大对象也只用一份 `StringBuilder`）。

转成树结构而不输出字符串：

```java
JSONObject tree = DingJson.toJSON(bean);        // Bean → JSONObject
JSONArray  arr  = DingJson.toJSONArray(list);   // List → JSONArray
```

### 输出规则

- `null` 字段：默认输出 `"f":null`；`writeNullFields(false)` 时整个键省掉
- `NaN` / `±Infinity`：默认输出 `null`；`failOnNonFinite(true)` 时抛 `JSONException`
- `Date`：默认输出 epoch 毫秒；`dateFormat("yyyy-MM-dd")` 或字段级 `@JSONField(format)` 时输出字符串
- `char`：输出单字符字符串
- 枚举：输出 `name()`
- 数组 / `Collection` / `Map` / 任意 javabean 自动处理
- HTML 敏感字符 `< > & = '` 转义成 `\u003c` 等（Gson 同款，**不可关闭**，防 XSS）
- 孤立代理项（lone surrogate）转义成 `\uXXXX`，避免 UTF-8 编码时被替换成 `?`

---

## 5. 泛型：TypeReference

运行期拿不到泛型参数，需要显式携带。直接用 `TypeReference` 重载：

```java
List<User> users = DingJson.parseArray(text, new TypeReference<User>() { });
Map<String, List<User>> map = DingJson.parseObject(text, new TypeReference<Map<String, List<User>>>() { });
User[] arr = DingJson.parseObject(text, new TypeReference<User[]>() { });
```

`TypeReference` 只是 `new TypeReference<X>() { }` 捕获泛型的惯用法，实现见
`src/main/java/com/dingjson/TypeReference.java`。

---

## 6. 树模型

`JSONObject`（`Map<String,Object>`）与 `JSONArray`（`List<Object>`）都继承 `JsonElement`。

### JSONObject

```java
JSONObject obj = new JSONObject();
obj.put("k", 1);

JSONObject o2 = JSONObject.of("a", 1, "b", 2);   // 键值对快捷构造

obj.getString("k");        // 任意值 → String（无则 null）
obj.getInteger("k");       // → Integer
obj.getLong("k");
obj.getDouble("k");
obj.getBoolean("k");
obj.getJSONObject("k");    // 嵌套对象
obj.getJSONArray("k");     // 嵌套数组
obj.getObject("k", User.class);   // 转 Bean
obj.containsKey("k");
obj.keySet(); obj.values(); obj.entrySet();
```

### JSONArray

```java
JSONArray arr = JSONArray.of(1, "two", 3.0);
arr.getString(0);  arr.getInteger(1);  arr.getJSONObject(2);  arr.getJSONArray(0);
arr.size();  arr.isEmpty();  arr.contains(x);  arr.iterator();  arr.toArray();
for (Object v : arr) { ... }
```

`getInteger` 等对 `null` 和不存在返回 `null`（不是 0），避免把"缺失"和"零"混起来。

---

## 7. 流式读写

### 7.1 读：JSONReader

自描述式的 token 遍历，适合超大文档（不建完整树）：

`JSONReader` 工作在 `String` 上，不是 `Closeable`，用 `DingJson.createReader(text)` 创建：

```java
JSONReader r = DingJson.createReader("[{\"id\":1},{\"id\":2}]");
r.readBeginArray();
while (r.hasNext()) {
    JSONObject one = r.readJSONObject();      // 或 readValue() / readJSONArray()
    ...
}
r.readEndArray();
r.ensureEnded();                              // 校验后面没有垃圾
```

常用方法：

| 方法 | 作用 |
|---|---|
| `peek()` | 看下一个有效字符（不移动） |
| `hasNext()` | 当前位置后面还有 `,` 或值 |
| `readBeginObject()` / `readEndObject()` | 结构断言，不匹配抛 `JSONException` |
| `readBeginArray()` / `readEndArray()` | 同上 |
| `skipComma()` | 跳过元素分隔符（可缺省） |
| `readKey()` | 读对象的键 |
| `readValue()` | 读任意值（对象/数组会建成树） |
| `skipValue()` | 跳过任意值，**不物化**（纯扫描，零分配） |
| `readString/readInt/readLong/readDouble/readBoolean/readNull` | 读标量 |
| `readNumberRaw()` | 读数字原文（保精度，配合 `BigDecimal`） |
| `ensureEnded()` | 断言输入已耗尽 |

**上限说明**：`maxArraySize`/`maxObjectSize` 由解析器产出的数组/对象自动检查，
适用于 `readValue()` 与全部批量方法。若你**手动逐元素循环**（`readBeginArray()` +
`while (r.peek() != ']')`），元素边界只存在于你的循环里，计数由调用方负责——
需要硬上限时用 `readValue()` 或批量方法。

### 7.2 批量数组读取

数值数组走专门的融合扫描，比逐个 `readInt()` 快一个数量级：

```java
JSONReader r = DingJson.createReader("[1,2,3,4,5]");
r.readBeginArray();
int[]   a = r.readIntArray();
long[]  b = r.readLongArray();
double[] c = r.readDoubleArray();
boolean[] d = r.readBooleanArray();
String[]  e = r.readStringArray();
```

这些方法自己吃掉开头的 `[` 和结尾的 `]`，直接返回原生数组（不是 `List`）。

### 7.3 写：JSONWriter

链式、无状态泄漏、防止生成非法 JSON：

```java
String json = DingJson.createWriter()
        .beginObject()
            .key("id").value(1)
            .key("tags").beginArray().value("a").value("b").endArray()
            .key("meta").beginObject().key("ok").value(true).endObject()
        .endObject()
        .toString();
// {"id":1,"tags":["a","b"],"meta":{"ok":true}}
```

`key()` 之后必须有值 —— `key("a").endObject()` 会抛 `JSONException`，不会输出 `{"a":}`。
`beginObject/endObject` 不配对同样抛异常。

---

## 8. JSONPath

```java
Object v = DingJson.eval(json, "$.items[0].price");
String name = DingJson.eval(json, "$.user.name", String.class);
```

支持的子集（安全、无脚本执行）：

| 语法 | 含义 |
|---|---|
| `$` | 根 |
| `.field` | 对象成员 |
| `[n]` | 数组下标（负数从末尾算） |
| `[*]` | 数组全部元素，或对象全部值 |
| `..field` / `..[n]` | 递归下降（搜索整个子树） |

匹配不到返回 `null`。多结果场景返回 `List`（用 `JSONPath.eval(root, path)` 直接拿）。

---

## 9. 校验与深拷贝

```java
DingJson.isValid(text);       // 只校验，不建树。比 parse 快 ~3x，零对象分配（约 1 KB 常量开销）
```

`isValid` 不实例化任何 `JSONObject`/`JSONArray`/`String`，直接在字符数组上扫描并校验
语法、转义、控制字符、数字格式。

```java
JSONObject copy = DingJson.deepCopy(obj);                 // 深拷贝树
JSONArray  copy = DingJson.deepCopy(arr);
User copy       = DingJson.deepCopy(user);                // 深拷贝 Bean（走序列化/反序列化）
List<User> copy = DingJson.deepCopy(list, new TypeReference<List<User>>() { });
```

---

## 10. 配置

不可变对象，用 builder 构造，可当常量复用：

```java
DingJsonConfig config = DingJsonConfig.builder()
        .writeNullFields(false)
        .dateFormat("yyyy-MM-dd HH:mm:ss")
        .failOnNonFinite(true)
        .caseInsensitiveFields(true)
        .maxStringLength(1_000_000)
        .maxArraySize(100_000)
        .maxObjectSize(10_000)
        .maxInputBytes(2 * 1024 * 1024)
        .maxDepth(128)
        .build();

DingJson.toJSONString(bean, config);
DingJson.parseObject(text, User.class, config);
```

| 选项 | 默认 | 作用 |
|---|---|---|
| `writeNullFields` | `true` | 序列化时是否输出值为 `null` 的字段 |
| `dateFormat` | `null`（epoch 毫秒） | 全局日期格式；字段级 `@JSONField(format)` 优先 |
| `failOnNonFinite` | `false` | `NaN`/`Infinity` 抛异常而不是输出 `null` |
| `caseInsensitiveFields` | `false` | 字段名匹配忽略大小写 |
| `maxStringLength` | `10_000_000` | 单个字符串最大长度，0 = 不限 |
| `maxArraySize` | `10_000_000` | 数组最大元素数，0 = 不限 |
| `maxObjectSize` | `1_000_000` | 对象最大键值对数，0 = 不限 |
| `maxInputBytes` | `10_000_000` | 字节/流/Reader/File 入口最大读入字节数，0 = 不限 |
| `maxDepth` | `512` | 对象/数组最大嵌套深度（必须 ≥ 1）。小栈环境（容器、虚拟线程）建议 64–128 |

`builder(base)` 可以在已有配置上改一项，其余继承。

### Mapper（配置实例化）

不想每个调用都传 config，用绑定好的 `DingJsonMapper`：

```java
DingJsonMapper mapper = DingJson.mapper(config);

mapper.toJSONString(bean);
mapper.parseObject(text, User.class);
mapper.parseArray(bytes, Order.class);
mapper.config();               // 取回配置
```

`mapper()` 用默认配置。`DingJson` 上的静态方法永远用默认配置，两者互不影响。

---

## 11. 注解与编译期 codec

### 11.1 `@JSONField`

```java
public class User {
    @JSONField(name = "user_name")            // 改 JSON 键名
    public String userName;

    @JSONField(serialize = false)             // 只读不写（如密码哈希）
    public String passwordHash;

    @JSONField(deserialize = false)           // 只写不读
    public String internalFlag;

    @JSONField(format = "yyyy-MM-dd")         // 字段级日期格式，优先于全局
    public Date birthday;
}
```

| 属性 | 默认 | 说明 |
|---|---|---|
| `name` | `""`（用字段名） | JSON 中的键名 |
| `serialize` | `true` | 是否参与输出 |
| `deserialize` | `true` | 是否参与输入 |
| `format` | `""` | 日期格式（仅对 `Date` 生效） |

### 11.2 `@DingJsonSerializable`（编译期 codec，可选）

给类加注解，注解处理器在编译期生成 `<类名>$$DingJsonCodec`，把反射换成
**生成的直线代码**（直接 `GETFIELD`，无字段查表、无 `instanceof` 分派）：

```java
@DingJsonSerializable
public class User {
    public int id;
    public String name;
}

DingJson.toJSONString(user);   // 自动发现并使用生成的 codec
```

生成的代码形如：

```java
public final class User$$DingJsonCodec implements DingJsonCodec<User> {
    public void write(User b, StringBuilder out, int depth, DingJsonConfig config) {
        out.append('{');
        CodecSupport.writeInt(out, KEY_id, b.id);
        CodecSupport.writeRef(out, KEY_name, b.name, depth, config);
        out.append('}');
    }
    private static final String KEY_id = CodecSupport.key("id");
    ...
}
```

注意事项：

- 字段必须对生成的类可见（`public`，或声明在同一个包）
- `private`/`final` 字段、泛型类、抽象类会**自动跳过**并给一条编译期 NOTE，
  构建不失败，该类型继续走反射路径
- 输出与反射路径**逐字节相同**（测试里用一对同形类比对）
- 需要 JDK 的注解处理开启。JDK 23+ 默认不再运行 classpath 上的处理器，
  需在编译参数里加 `-proc:full`（本项目 `pom.xml` 已加）
- 关闭发现：`-Ddingjson.codec.disabled=true`（排查问题用）

### 11.3 显式注册（零反射）

自动发现每类要做一次 `Class.forName`（约 0.3 μs，一次性）。要完全去掉它：

```java
public class App {
    static {
        DingJson.registerCodec(User.class, new User$$DingJsonCodec());
    }
}
```

注册后该类型的编解码路径**不含任何反射 API**。必须在首次使用前注册，
迟到的注册会抛 `JSONException`（不会静默失效）。适用于 GraalVM native-image、
受限 ClassLoader 等场景。

---

## 12. 支持的类型

| 类别 | 序列化 | 反序列化 |
|---|---|---|
| `String` / 基本类型 / 包装类 | ✓ | ✓（宽松：字符串数字可转数值） |
| `char` / `Character` | 单字符字符串 | 要求长度 1 |
| `BigDecimal` / `BigInteger` | 原样输出，全精度 | ✓（读原文，不经 double） |
| `Date` | epoch 毫秒或格式化字符串 | 毫秒数 / 格式化字符串 / ISO |
| `Calendar` | 同上 | 毫秒数 |
| `java.sql.Date` | 同上 | 同 `Date` |
| `LocalDate` / `LocalDateTime` / `Instant` | ISO-8601 | ✓ |
| `UUID` | 字符串 | ✓ |
| 枚举 | `name()` | ✓（`valueOf`） |
| 数组（含多维、基本类型数组） | ✓ | ✓ |
| `Collection` / `List` / `Set` | ✓ | 需要 `TypeReference` 提供元素类型 |
| `Map` | ✓（非字符串键按 Jackson 风格字符串化） | 同上 |
| `JSONObject` / `JSONArray` / `JsonElement` | ✓ | ✓ |
| 任意 javabean | 字段 | 字段 |

不支持：`Optional`（会当普通 bean 处理）、循环引用（环会撞深度上限，DAG 正常）、
自定义 serializer/deserializer 插件（本库无此机制）。

---

## 13. 错误处理

所有错误统一是 `com.dingjson.exception.JSONException`（`RuntimeException` 子类），
**没有受检异常**：

```java
try {
    User u = DingJson.parseObject(text, User.class);
} catch (JSONException e) {
    e.getMessage();     // 含位置信息，如 "Unexpected character '}' at position 42"
    e.getCause();       // 底层 NumberFormatException / DateTimeParseException 等
}
```

典型抛错场景：

| 场景 | 消息大意 |
|---|---|
| 语法错误 | `Unexpected character 'x' at position N` |
| 嵌套超过 512 层 | `Exceeded maximum nesting depth of 512`（而不是 `StackOverflowError`） |
| 数字溢出目标类型 | `Cannot convert ...` |
| 字符串内裸控制字符 | `Unexpected control character` |
| 超长字符串/超大数组/超大输入 | `exceeds max...` |
| `JSONWriter` 用错 | `endObject() after key() without a value` |

深嵌套的 `StackOverflowError` 在序列化、反序列化、`deepCopy`、`JSONPath` 入口都被
捕获并转成 `JSONException`，容器/小栈线程里也能正常 catch。

---

## 14. 并发与线程安全

- `DingJson` 的所有静态方法、`JSONObject`/`JSONArray`、`JSONWriter`、`JSONReader` 实例
  **都不是**线程安全的。`JSONObject`/`JSONArray` 是普通 `HashMap`/`ArrayList` 包装。
- 每个线程用自己的实例即可，没有全局锁。
- 内部缓存（字段信息、构造器、codec 发现结果）全部是 `ClassValue`，
  按类缓存、无锁、GC 可回收，多线程并发首次访问同一个类是安全的。
- `DingJsonConfig` 不可变，可以跨线程共享。
- `DingJsonMapper` 持有不可变 config，只读，可以跨线程共享。

---

## 15. 安全建议

| 风险 | 内置防护 |
|---|---|
| 内存耗尽（超大输入） | `maxInputBytes` 默认 10 MB，字节/流/Reader/File 入口边读边拒 |
| 深嵌套栈溢出 | 深度上限 512，抛 `JSONException` |
| 超长字符串 / 超大数组 | `maxStringLength` / `maxArraySize` / `maxObjectSize` |
| XSS（JSON 嵌进 HTML） | `< > & = '` 默认转义，不可关闭 |
| AutoType / 反序列化漏洞 | **没有** AutoType。`{"@type":"java.lang.Runtime"}` 只是普通键 |
| 数据损坏（lone surrogate） | 输出时转义成 `\uXXXX` |
| 日期宽松解析 | 用 `DateTimeFormatter`（严格，`2023-02-30` 不会滚成 3 月） |

对外部输入的建议：

```java
DingJsonConfig untrusted = DingJsonConfig.builder()
        .maxInputBytes(1 << 20)      // 1 MB
        .maxStringLength(64 * 1024)
        .maxArraySize(1000)
        .maxObjectSize(1000)
        .build();
```

**没有** `SecurityManager` 依赖，也没有反射构造任意类的路径（只按声明的字段类型构造）。

---

## 16. 性能建议

1. **数值数组用批量 API**：`readIntArray()` 比循环 `readInt()` 快约 10 倍
   （融合扫描 + 定长缓冲，避免逐个装箱/边界检查）。
2. **只要校验就不要 `parse`**：用 `isValid`，快约 3 倍且几乎零分配。
3. **超大文档用流式**：`DingJson.createReader(text)` 逐条读，避免整棵树驻留。
4. **热点 Bean 加 `@DingJsonSerializable`**：bean 序列化再快 5–20%，
   分配量不变（反射路径早已做到每字段零分配）。
5. **千万别拿 `JSONObject` 当读缓冲循环 `put`**：直接用 `JSONReader` 或 Bean 映射。
6. **复用 `DingJsonConfig`**：builder 只在启动时构造一次。
7. **不需要 `null` 字段就别输出**：`writeNullFields(false)` 少写字节，
   对网络传输和下游解析都更快。

实测数据（JDK 25，详见 [../BENCHMARK.md](../BENCHMARK.md)）：

| 场景 | 吞吐 |
|---|---|
| 解析小对象成树 | ~1.1 μs / 次 |
| 解析 Bean | ~0.33 μs / 次 |
| 序列化 Bean | ~0.18 μs / 次（带 codec 更低） |
| 序列化树 | ~1.5 μs / 次 |
| `readDoubleArray` 10 万元素 | ~3.0 ms |

---

## 附：完整方法索引

```
DingJson.parse / parseObject / parseArray        （String/byte[]/InputStream/Reader/File）
DingJson.toJSONString / toJSONBytes / writeTo
DingJson.toJSON / toJSONArray
DingJson.eval / isValid / deepCopy
DingJson.createReader / createWriter
DingJson.mapper / registerCodec
JSONReader.read* （含 readIntArray 等批量方法）
JSONWriter.beginObject/endObject/key/value*/toString
JSONPath.eval
DingJsonConfig.builder().…build()
DingJsonMapper.<所有静态方法>
```
