#!/usr/bin/env bash
# =============================================================================
# 把编译好的 APK 推到一个独立分支，让开发机能走 git 通道取到。
#
# 为什么需要：
#   Actions 的 artifact 下载必须登录 github.com，而开发所在网络到 github.com:443
#   被企业代理 502（直连超时），网页和 artifact 都拿不到。git 走 SSH 是通的，
#   所以把 APK commit 到独立分支，开发机 git show / git fetch 就能取出。
#
# 用法（assemble job 成功后）：
#   env:
#     GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
#   run: bash tools/ci-publish-artifacts.sh
#
# 取用（开发机）：
#   git fetch origin artifacts
#   git show origin/artifacts:apk/app-debug.apk > app-debug.apk
# =============================================================================
set -uo pipefail

BRANCH="${1:-artifacts}"

if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "::warning::GITHUB_TOKEN 为空，跳过 APK 分支发布"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

DEST="$WORK/apk"
mkdir -p "$DEST"

shopt -s nullglob
found=0
for f in app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk; do
  cp "$f" "$DEST/" && found=$((found + 1))
done
shopt -u nullglob

if [ "$found" -eq 0 ]; then
  echo "::warning::没有找到任何 APK，跳过发布"
  exit 0
fi

( cd "$DEST" && sha256sum ./*.apk > SHA256SUMS.txt 2>/dev/null ) || true

cat > "$DEST/README.txt" <<EOF
分支用途：CI 编译产物回传（因为开发机到 github.com 不可达，拿不到 Actions artifact）

commit     : ${GITHUB_SHA:-}
ref        : ${GITHUB_REF:-}
run        : https://github.com/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-0}
generated  : $(date -u +%Y-%m-%dT%H:%M:%SZ)

取用方式：
  git fetch origin ${BRANCH}
  git show origin/${BRANCH}:apk/app-debug.apk > app-debug.apk

说明：
  - app-debug.apk        调试版，applicationId 带 .debug 后缀，可直接安装
  - app-release-unsigned.apk  未签名，仅供检查体积/产物，不能直接装
EOF

cd "$WORK" || exit 0
git init -q -b "$BRANCH" 2>/dev/null || exit 0
git config user.email "ci@users.noreply.github.com"
git config user.name "ci-bot"
# 二进制不用 diff/压缩，省时间
git config core.compression 0
git add -A || exit 0
git commit -q -m "artifacts: ${GITHUB_SHA:0:7} ($(date -u +%Y-%m-%dT%H:%M:%SZ))" || exit 0

if ! git push -f \
  "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" \
  "HEAD:refs/heads/${BRANCH}" 2>&1 | sed -E 's#(x-access-token:)[^@]+@#\1***@#g'
then
  echo "::warning::APK 分支推送失败（检查仓库 Workflow permissions 是否为 read-write）"
  exit 0
fi

echo "APK 已发布到分支 ${BRANCH} 的 apk/ 目录（共 ${found} 个）"
