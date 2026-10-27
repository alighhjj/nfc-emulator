#!/usr/bin/env bash
# =============================================================================
# 把 CI 失败日志推到仓库里的固定分支，供开发机拉取。
#
# 为什么需要这个脚本：
#   开发所在网络（企业代理）到 github.com:443 是 502，Actions 网页日志打不开；
#   GitHub 的 job logs API 又要求 token。唯一能稳定走通的通道是 git 本身。
#   所以失败时让 runner 把日志 commit 到一个分支，开发机 git fetch 就能看。
#
# 用法（在 workflow 的 if: failure() 步骤里）：
#   bash tools/ci-publish-log.sh ci-build.log
#
# 需要的环境变量（Actions 自带）：
#   GITHUB_TOKEN / GITHUB_REPOSITORY / GITHUB_SHA / GITHUB_JOB
# =============================================================================
set -uo pipefail

LOG="${1:-ci-build.log}"
BRANCH="${2:-ci-logs}"

if [ ! -f "$LOG" ]; then
  echo "::warning::找不到日志文件 $LOG，跳过发布"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 目录名带上 job 名，多个 job 的日志不会互相覆盖
DEST="$WORK/$GITHUB_JOB"
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
job        : $GITHUB_JOB
sha        : $GITHUB_SHA
ref        : ${GITHUB_REF:-}
run        : https://github.com/$GITHUB_REPOSITORY/actions/runs/${GITHUB_RUN_ID:-0}
generated  : $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

cd "$WORK" || exit 0
git init -q -b "$BRANCH" 2>/dev/null || exit 0
git config user.email "ci@users.noreply.github.com"
git config user.name "ci-bot"
git add -A || exit 0
git commit -q -m "ci($GITHUB_JOB): failure log for ${GITHUB_SHA:0:7}" || exit 0

# 强制推送：这个分支只用于传日志，不需要保留历史。
# 注意开头已开 pipefail，必须用 `if !` 才能正确识别 push 失败；
# 顺手用 sed 把 URL 里的 token 打码，避免它出现在公开日志里。
if ! git push -f \
  "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" \
  "HEAD:refs/heads/${BRANCH}" 2>&1 | sed -E 's#(x-access-token:)[^@]+@#\1***@#g'
then
  echo "::warning::日志推送失败（分支保护或权限不足），请检查 permissions: contents: write"
  exit 0
fi

echo "日志已发布到分支 ${BRANCH} 的 ${GITHUB_JOB}/ 目录"
