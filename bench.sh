#!/usr/bin/env bash
# DingJson 基准测试入口。数据与解读见 BENCHMARK.md。
#
#   ./bench.sh                        横向对比：DingJson vs fastjson1/2、Jackson、Gson、org.json
#   ./bench.sh ab <classes 目录> [轮数]  同 JVM A/B：与另一版本交替对比（默认 7 轮）
#
# A/B 模式的旧版本用 worktree 编一份即可：
#   git worktree add /tmp/dj100 <commit> && (cd /tmp/dj100 && mvn -o -q compile)
set -euo pipefail
cd "$(dirname "$0")"

# 超时的基准会留下占满 CPU 的 JVM，后续测量全部失真
if pgrep -f 'Xms1g' >/dev/null 2>&1; then
  echo "警告: 检测到残留的 'java -Xms1g' 进程，先杀掉再跑（否则数据不可信）" >&2
fi

mvn -o -q test-compile
[ -f cp.txt ] || mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/test-classes:target/classes:$(cat cp.txt)"

case "${1:-}" in
  ab)
    [ $# -ge 2 ] || { echo "用法: $0 ab <classes 目录> [轮数]" >&2; exit 2; }
    exec java -Xms1g -Xmx2g -cp "$CP" com.dingjson.ABBench "$2" "${3:-7}"
    ;;
  "")
    exec java -Xms1g -Xmx2g -cp "$CP" com.dingjson.Benchmark
    ;;
  *)
    echo "未知模式 '$1'：不带参数 = 横向对比，ab <classes 目录> = 版本 A/B" >&2
    exit 2
    ;;
esac
