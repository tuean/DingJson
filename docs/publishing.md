# 发布到 Maven Central

DingJson 通过 **Central Publisher Portal**（`central.sonatype.com`）发布。
旧的 OSSRH（`s01.oss.sonatype.org`）已于 **2025-06-30 关停**，`nexus-staging-maven-plugin`
那套流程不再可用，pom 里已换成 `central-publishing-maven-plugin`。

- 坐标：`io.github.tuean:dingjson:<version>`
- groupId 用 `io.github.tuean`：Portal 对 `io.github.<GitHub 用户名>` 前缀做**自动验证**
  （登录的 GitHub 账号是 `tuean` 即可），不需要域名和 DNS TXT 记录

---

## 一次性准备（只做一次）

### 1. 注册 namespace

1. 用 GitHub 账号登录 <https://central.sonatype.com/>
2. `Namespaces` → `Add Namespace` → 填 `io.github.tuean` → 提交
3. 等 Portal 自动验证通过（通常几秒，状态变 `Verified`）

> 如果以后想改成 `com.dingjson`，那需要证明拥有 `dingjson.com`：Portal 会给出一个
> 随机 TXT 值，你去域名 DNS 加一条 `_sonatype-verification` 记录再验证。
> **groupId 一旦发布就无法更改**，要换只能改名重新发。

### 2. 生成 GPG 密钥

Central 要求每个 artifact 带 `.asc` 签名，缺签名直接拒收。

```bash
brew install gnupg          # 已装则跳过
gpg --full-generate-key     # 选 RSA and RSA，4096 位，填姓名/邮箱，设 passphrase
gpg --list-secret-keys --keyid-format=long
#   sec   rsa4096/ABCD1234EF567890 2026-11-16 [SC]
```

把公钥传上 keyserver（Portal 和下游用户都靠它验签）：

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234EF567890
gpg --keyserver keys.openpgp.org     --send-keys ABCD1234EF567890
```

### 3. 配置凭据

Portal 用的是 **User Token**，不是登录密码：

1. <https://central.sonatype.com/> → 右上角头像 → `View User Token` → `Generate`
2. 复制 `tools/settings-central.xml.example` 到 `~/.m2/settings.xml`，
   把 `username`/`password` 换成 token 的两段值

```xml
<servers>
  <server>
    <id>central</id>                      <!-- 必须与 pom 的 publishingServerId 一致 -->
    <username>token-username</username>
    <password>token-password</password>
  </server>
</servers>
```

> 国内网络若 `repo.maven.apache.org` 超时，把模板里注释掉的 `<proxies>` 段打开。

---

## 每次发布

```bash
# 1. 确认干净且全绿（两次 surefire 执行：Unsafe 路径 + 反射回退路径，各 534）
mvn -o clean test

# 2. 改版本号：pom.xml 的 <version> + CHANGELOG.md 加一节
#    （semver：修 bug 升 patch，加公开 API 升 minor，破坏性变更升 major）

# 3. 上传到 Portal（会跑 gpg:sign、source jar、javadoc jar、全部测试）
mvn -P release clean deploy

# 4. 到 https://central.sonatype.com/ → Deployments 找到这次 deployment
#    检查文件清单后点 Publish
#    （pom 里 autoPublish=false，故意留这一步人工确认）

# 5. 打 tag 并推送
git tag -a v1.1.0 -m "DingJson 1.1.0"
git push origin main --tags
```

发布后约 **2 小时**同步到 `repo1.maven.org`，可以用下面的地址确认：

```bash
curl -sI https://repo1.maven.org/maven2/io/github/tuean/dingjson/1.1.0/dingjson-1.1.0.jar
# 200 才算真的可拉；在此之前用 repo1.maven.org 拉会 404
```

---

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| `gpg: signing failed: Inappropriate ioctl for device` | 在无 TTY 的环境（IDE 终端、CI、agent）跑。本地终端里跑即可；脚本里跑需要 `export GPG_TTY=$(tty)` |
| `gpg: signing failed: No pinentry` | 同上；或改用 loopback 模式：`mvn -P release deploy -Dgpg.passphrase=$GPG_PASSPHRASE` 并在 pom 的 gpg-plugin 里加 `<gpgArguments><arg>--pinentry-mode</arg><arg>loopback</arg></gpgArguments>` |
| `401 Unauthorized` / `Could not transfer` | `settings.xml` 的 `<id>` 不是 `central`，或用了登录密码而不是 User Token |
| `Namespace not found` / `403` | namespace 还没验证通过，或 groupId 和 namespace 不一致 |
| `Invalid POM: missing ...` | Central 强制要求 `name`/`description`/`url`/`licenses`/`developers`/`scm`，缺一个就拒收 |
| `Javadoc jar is empty` / javadoc 报错 | `maven-javadoc-plugin` 版本太老（JDK 25 需要 3.11+），pom 已固定 3.11.2 |
| 发布错了想撤回 | Central 上的 artifact **不可删除**（只能申请 tombstone），所以 `autoPublish=false` 让人工确认 |

## 为什么不用 JitPack

JitPack 只需 public 仓库 + tag，五分钟能拉，但**使用者必须在 pom 里额外加
`https://jitpack.io` 仓库**，而且不少公司的 CI 出于供应链安全会直接屏蔽 jitpack.io。
Maven Central 是 Maven 默认仓库，使用者一行依赖就能用。
