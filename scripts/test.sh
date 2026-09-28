#!/usr/bin/env bash
# 跑既有用例（12 个）：先构建，再执行 poolleak.PoolTest。
# classpath 分隔符按平台判定：Windows（Git Bash / MSYS / Cygwin）是 `;`，其余是 `:`。
set -euo pipefail

cd "$(dirname "$0")/.."

bash scripts/build.sh

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
    *) CPSEP=':' ;;
esac

exec java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp "out${CPSEP}out-test" poolleak.PoolTest
