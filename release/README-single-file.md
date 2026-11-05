# DingJson 单文件版

`DingJson.java` — 全部代码合并为一个文件（5,044 行 / 188 KB），**零依赖**，直接复制到公司项目即可使用。

## 使用方法

1. 把 `DingJson.java` 复制到项目的任意包（例如 `com.company.util`），文件内 `package` 声明可自行修改
2. 业务代码 `import` 该类即可，**只需 import 一个类**

## 与多文件版的差异（嵌套类型）

| 多文件版 | 单文件版 |
|---|---|
| `JSONObject obj` | `DingJson.JSONObject obj` |
| `JSONArray arr` | `DingJson.JSONArray arr` |
| `new TypeReference<List<User>>() {}` | `new DingJson.TypeReference<List<User>>() {}` |
| `@JSONField(name="x")` | `@DingJson.JSONField(name="x")` |
| `catch (JSONException e)` | `catch (DingJson.JSONException e)` |
| `JSONReader r = DingJson.createReader(json)` | `DingJson.JSONReader r = ...` |
| `JSONWriter.create()` | `DingJson.JSONWriter.create()` |
| `JSONPath.eval(...)` | `DingJson.JSONPath.eval(...)` |
| `DingJsonConfig.builder()` | `DingJson.DingJsonConfig.builder()` |
| `DingJsonMapper mapper` | `DingJson.DingJsonMapper mapper` |

其余 API（`parseObject` / `parseArray` / `toJSONString` / `toJSONBytes` / `createReader` / `mapper` / `eval`）完全一致。

## 示例

```java
import com.company.util.DingJson;

public class Demo {
    public static class User {
        @DingJson.JSONField(name = "user_name", format = "yyyy-MM-dd")
        public String userName;
        public int age;
    }

    public static void main(String[] args) {
        User u = DingJson.parseObject("{\"user_name\":\"Ding\",\"age\":3}", User.class);
        String json = DingJson.toJSONString(u);          // {"user_name":"Ding","age":3}

        DingJson.JSONObject obj = DingJson.parseObject(json);
        String name = obj.getString("user_name");

        int[] ids = DingJson.createReader("[1,2,3]").readIntArray();  // 流式零装箱

        DingJson.DingJsonMapper mapper = DingJson.mapper(
                DingJson.DingJsonConfig.builder().writeNullFields(false).build());
        String compact = mapper.toJSONString(u);
    }
}
```

## 功能清单

- 解析/序列化树、Bean 双向绑定（含嵌套泛型 `TypeReference`）
- 企业级：`@JSONField` 注解、可配置 `DingJsonMapper`、JSONPath、流式读写
- JDK 类型：`Date`/`UUID`/`BigDecimal`（全精度）/`LocalDate` 等
- 安全：深度限制、HTML 转义、无 AutoType、NaN 防护、循环检测
- 性能：手写字符级解析器、`readIntArray` 零装箱

## 更新方式

多文件源位于项目 `src/main/java/com/dingjson/`；修改源后运行：

```bash
python3 tools/generate_single_file.py   # 重新生成 release/DingJson.java
```

> 注意：`DingJson.java` 使用 `sun.misc.Unsafe`（可选加速，自动降级为反射）。
> 如公司 JDK 限制 Unsafe，行为完全一致，仅字段访问速度略降。
