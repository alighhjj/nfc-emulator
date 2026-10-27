#!/usr/bin/env bash
# =============================================================================
# 把 CI 失败信息回传给开发机。
#
# 为什么需要这个脚本：
#   开发所在网络（企业代理）到 github.com:443 是 502，Actions 网页日志打不开；
#   GitHub 的 job logs API 又要求 token。于是走两条不依赖网页的通道：
#
#   通道 A（无权限要求）：把关键错误行写成 check-run 注解。
#                         api.github.com 对公开仓库可匿名读 annotation。
#   通道 B（需 contents: write）：把完整日志 commit 到 ci-logs 分支，
#                         开发机 git fetch / contents API 都能读。
#
# 用法（在 workflow 的 if: failure() 步骤里）：
#   env:
#     GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
#   run: bash tools/ci-publish-log.sh ci-build.log
#
# 需要的环境变量：
#   GITHUB_TOKEN（注意：Actions 不会自动把它注入 run 步骤的 env，必须显式传）
#   GITHUB_REPOSITORY / GITHUB_SHA / GITHUB_JOB（Actions 自带）
# =============================================================================
set -uo pipefail

LOG="${1:-ci-build.log}"
BRANCH="${2:-ci-logs}"

if [ ! -f "$LOG" ]; then
  echo "::warning::找不到日志文件 $LOG，跳过回传"
  exit 0
fi

# ---------------------------------------------------------------------------
# 通道 A：注解关键错误行
# 只挑真正能定位问题的行，避免把几百行 build 进度全塞进注解里。
# 注意 Actions 的注解消息不能含换行，且需要转义 % 与 \r。
# ---------------------------------------------------------------------------
emit_annotations() {
  local pattern='^(e: |FAILURE:|\* What went wrong:|> Task .*FAILED|Execution failed for task|Caused by:|Unresolved reference|Could not resolve)'
  local hit=0
  local line
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    hit=$((hit + 1))
    line="${line//'%'/'%25'}"
    line="${line//$'\r'/}"
    printf '::error::%s\n' "${line:0:900}"
  done < <(grep -aE "$pattern" "$LOG" 2>/dev/null | head -n 60)
  echo "已输出 $hit 条错误注解"
}
emit_annotations

# ---------------------------------------------------------------------------
# 通道 B：把完整日志推到 ci-logs 分支
# ---------------------------------------------------------------------------
if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "::warning::GITHUB_TOKEN 为空，跳过日志分支发布（检查步骤是否显式 env 传入了 secrets.GITHUB_TOKEN）"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 目录名带上 job 名，多个 job 的日志不会互相覆盖
DEST="$WORK/${GITHUB_JOB:-job}"
mkdir -p "$DEST"
cp "$LOG" "$DEST/" 2>/dev/null || true

# 顺带把测试结果与 lint 报告带上（存在才带）
for extra in \
  core-nfcc/build/test-results/test \
  core-nfcc/build/reports/tests/test \
  app/build/reports/lint-results-debug.html \
  app/build/reports/lint-results-debug.txt
do
  if [ -e "$extra" ]; then
    mkdir -p "$DEST/$(dirname "$extra")"
    cp -r "$extra" "$DEST/$(dirname "$extra")/" 2>/dev/null || true
  fi
done

cat > "$DEST/README.txt" <<EOF
job        : ${GITHUB_JOB:-}
sha        : ${GITHUB_SHA:-}
ref        : ${GITHUB_REF:-}
run        : https://github.com/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-0}
generated  : $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

cd "$WORK" || exit 0
git init -q -b "$BRANCH" 2>/dev/null || exit 0
git config user.email "ci@users.noreply.github.com"
git config user.name "ci-bot"
git add -A || exit 0
git commit -q -m "ci(${GITHUB_JOB:-job}): failure log for ${GITHUB_SHA:0:7}" || exit 0

# 强制推送：这个分支只用于传日志，不需要保留历史。
# 开头已开 pipefail，必须用 `if !` 才能正确识别 push 失败；
# 顺手用 sed 把 URL 里的 token 打码，避免它出现在公开日志里。
if ! git push -f \
  "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" \
  "HEAD:refs/heads/${BRANCH}" 2>&1 | sed -E 's#(x-access-token:)[^@]+@#\1***@#g'
then
  echo "::warning::日志分支推送失败（多半是仓库的 Workflow permissions 是 read-only），但上面的错误注解仍可读"
  exit 0
fi

echo "日志已发布到分支 ${BRANCH} 的 ${GITHUB_JOB:-job}/ 目录"
