# DingJson 🏺

> **鼎** — 稳如泰山，一言九鼎。

A secure, enterprise-ready Java JSON library with **zero runtime dependencies**.

| | |
|---|---|
| ✅ [JSONTestSuite](https://github.com/nst/JSONTestSuite) 100% 通过 | 318 个合规用例（95 合法 + 188 攻击向量 + 35 实现定义） |
| 🔒 安全默认 | 深度限制（防 DoS）、HTML 转义（防 XSS）、无 AutoType（防 RCE）、NaN/Infinity 防护 |
| ⚡ 高性能 | 手写字符级解析器；流式 `readIntArray` 零装箱（100k 数组 0.73ms，领先 fastjson2/Jackson/Gson） |
| 🪶 零依赖 | 纯 Java 8+，无第三方 jar，无传递 CVE |
| 🏢 企业级 | 可配置 Mapper、`@JSONField` 注解、JSONPath、流式读写、JDK 类型支持 |

## 快速开始

```java
import com.dingjson.*;

// 解析
JSONObject obj = DingJson.parseObject("{\"name\":\"Ding\",\"age\":3}");
String name = obj.getString("name");
User user = DingJson.parseObject(json, User.class);
List<User> users = DingJson.parseArray(json, User.class);

// 泛型（Gson TypeToken 同款）
List<List<Integer>> matrix = DingJson.parseObject(json, new TypeReference<List<List<Integer>>>() {});

// 序列化
String json = DingJson.toJSONString(user);
String pretty = DingJson.toJSONString(user, true);
byte[] bytes = DingJson.toJSONBytes(user);          // UTF-8，HTTP 直接可用

// IO 输入（HTTP / 配置文件场景，固定 UTF-8，非法编码直接报错）
User u1 = DingJson.parseObject(body, User.class);   // byte[]
User u2 = DingJson.parseObject(in, User.class);     // InputStream
User u3 = DingJson.parseObject(reader, User.class); // Reader
User u4 = DingJson.parseObject(file, User.class);   // File
DingJson.writeTo(file, user);                       // 写出（可 pretty）

// 一行式便捷
Object price = DingJson.eval(json, "$.items[0].price"); // 解析 + JSONPath 一步
boolean ok = DingJson.isValid(text);                    // 纯校验，不建树
JSONObject o = JSONObject.of("name", "Ding", "age", 3); // 快速构造
JSONArray a = JSONArray.of(1, 2, 3);
JSONObject copy = DingJson.deepCopy(o);                  // 深拷贝

// 流式读取（零装箱、免建树）
JSONReader r = DingJson.createReader(json);
r.readBeginObject();
while (r.peek() != '}') {
    switch (r.readKey()) {
        case "ids":  int[] ids = r.readIntArray(); break;
        default:     r.skipValue();
    }
}
r.readEndObject();

// 流式构建
JSONWriter w = JSONWriter.create();
w.beginObject().key("name").value("Ding").key("ids").beginArray().value(1).value(2).endArray().endObject();

// JSONPath
Object price = JSONPath.eval(tree, "$.items[0].price");
```

## 便捷 API

### 静态 config 重载（parse 家族与 mapper 行为对称）

```java
// 想用自定义限制但不想建 mapper？直接传 config
JSONObject obj = DingJson.parseObject(text, config);
User u = DingJson.parseObject(text, User.class, config);
List<User> list = DingJson.parseArray(text, User.class, config);
Object any = DingJson.parse(text, config);
JSONReader r = DingJson.createReader(text, config);
// 静态无 config 版本行为不变（默认限制：10MB 字符串 / 1000 万元素 / 100 万 key）
```

### eval — 解析 + JSONPath 一步到位

```java
Object price = DingJson.eval(json, "$.items[0].price");   // 无匹配返回 null
Double d = DingJson.eval(json, "$.items[0].price", Double.class); // 带类型转换
```

### isValid — 纯校验不建树

```java
boolean ok = DingJson.isValid(text);   // 常量内存，超大 payload 无 OOM 风险；超默认限制视为非法
```

### deepCopy — 深拷贝

```java
JSONObject copy = DingJson.deepCopy(tree);                    // 树：递归复制（快路径）
User copy = DingJson.deepCopy(user);                          // bean：序列化往返
List<Address> copy = DingJson.deepCopy(list,
        new TypeReference<List<Address>>() {});               // 泛型：元素类型精确保持
// 无 TypeReference 的集合元素退化为树节点（JSONObject/JSONArray），属明确行为
```

## 企业级配置（Jackson ObjectMapper 同款）

```java
DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
        .writeNullFields(false)                  // 省略 null 字段（@JsonInclude.NON_NULL）
        .dateFormat("yyyy-MM-dd HH:mm:ss")       // 全局日期格式
        .caseInsensitiveFields(true)             // 输入字段名大小写不敏感
        .failOnNonFinite(true)                   // NaN/Infinity 抛异常（默认输出 null）
        .maxInputBytes(10_000_000)               // 输入大小上限（默认 10 MB，0 = 不限）
        .build());

String json = mapper.toJSONString(bean);
User u = mapper.parseObject(json, User.class);
```

**输入大小上限**：`maxInputBytes` 默认 10 MB，对 `byte[]`/`InputStream`/`Reader`/`File`
入口在读取过程中即时生效（防无限流 OOM DoS）；字符串入口的 `maxStringLength`/
`maxArraySize`/`maxObjectSize`（默认 10M 字符 / 10M 元素 / 1M 键）只在解析阶段生效。
超出 double 范围的数字（如 `1e999`）解析为 `BigDecimal` 保精度；`readDouble()`
和 double 字段严格拒绝。

## 注解

```java
public class User {
    @JSONField(name = "user_name")           // JSON 中改名
    public String userName;

    @JSONField(serialize = false)            // 只写不读（如密码哈希）
    public String passwordHash;

    @JSONField(format = "yyyy-MM-dd")        // 日期格式（优先级高于全局配置）
    public Date updatedAt;
}
```

### 编译期 codec（可选，免反射）

给类加 `@DingJsonSerializable`，注解处理器会生成 `<类名>$$DingJsonCodec`，
序列化/反序列化改走生成的直线代码（无反射查表、无 `instanceof` 分派）：

```java
@DingJsonSerializable
public class User {
    public int id;
    public String name;
}

DingJson.toJSONString(user);   // 首次使用时发现 codec，之后一直用它
```

- 字段需对生成的类可见（`public`，或声明在与类同包）；`private`/`final` 字段、
  泛型类、抽象类会**编译期给一条 NOTE 并退回反射**，绝不阻断构建
- 输出与原反射路径**逐字节相同**（HTML 转义、`@JSONField(name/serialize/format)`、
  `writeNullFields`、日期格式全部一致，测试逐字节比对）
- 关闭：`-Ddingjson.codec.disabled=true`；JDK 23+ 需要 `-proc:full`（本项目 `pom.xml` 已加）
- 收益有限但真实：bean 序列化 1.05–1.2x、树路径反序列化约 1.05x
  —— 反射路径此前已做过 key 预引用、构造器缓存与 Unsafe 直访

## 内置 JDK 类型支持

| 类型 | 序列化 | 反序列化 |
|---|---|---|
| `Date` / `Calendar` | epoch millis 或格式化字符串 | 数字/格式化字符串 ✓ |
| `UUID` | 字符串 | ✓ |
| `BigDecimal` / `BigInteger` | 数字直出（**全精度，不走 double**） | ✓ |
| `LocalDate` / `LocalDateTime` / `Instant` | ISO-8601 | ✓ |

## 安全特性

- **深度限制**：`JSONParser.MAX_DEPTH = 512`，超深嵌套抛 `JSONException`（防 Billion-Laughs 类 DoS）
- **HTML 转义**：`< > & = '` 输出为 `\u003c` 等（Gson 同款，防 XSS）
- **无 AutoType**：`{"@type":"java.lang.Runtime"}` 只是普通 key，绝不实例化
- **循环引用检测**：深度计数（环必超限），DAG 不误杀
- **数字安全**：超出 long 范围拒绝；NaN/Infinity 默认输出 `null`
- **控制字符**：字符串内裸控制字符按 RFC 8259 拒绝
- **合规背书**：JSONTestSuite 318 用例 100% 通过

## 性能（JDK 25，详见 [BENCHMARK.md](BENCHMARK.md)）

| 场景 | DingJson | fastjson2 | Jackson | Gson | org.json |
|---|---|---|---|---|---|
| 复杂文档解析 (μs) | 7.3 | 2.9 | 7.2 | 9.1 | 15.4 |
| 序列化 (μs) | 5.5 | 2.2 | 3.6 | 18.2 | 53.3 |
| **100k 数组流式 (ms)** | **0.73 🥇** | 1.32 | 2.11 | 2.80 | 8.09 |

流式 `readIntArray` 全面领先（零装箱 + 零中间树）。

## Maven

```xml
<dependency>
    <groupId>com.dingjson</groupId>
    <artifactId>dingjson</artifactId>
    <version>1.0.0</version>
</dependency>
```

Java 8+，无传递依赖。

## 构建

```bash
mvn clean package      # 单元测试 + 合规测试 + Checkstyle
mvn test              # 488 个测试 + JSONTestSuite 318 用例
```

## JPMS 说明

DingJson 以**自动模块**（`com.dingjson`）方式在模块化项目中使用，无需 `module-info` 声明。
`Unsafe` 字段访问（可选加速）在受限 JDK 上自动降级为反射，行为完全一致。

## License

Apache 2.0
