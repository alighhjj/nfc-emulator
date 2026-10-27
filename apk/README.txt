分支用途：CI 编译产物回传（因为开发机到 github.com 不可达，拿不到 Actions artifact）

commit     : 4336ecf0af07d93f9eac025ebdcfbc44ec123f0d
ref        : refs/heads/main
run        : https://github.com/alighhjj/nfc-emulator/actions/runs/40501466090
generated  : 2026-10-27T12:11:20Z

取用方式：
  git fetch origin artifacts
  git show origin/artifacts:apk/app-debug.apk > app-debug.apk

说明：
  - app-debug.apk        调试版，applicationId 带 .debug 后缀，可直接安装
  - app-release-unsigned.apk  未签名，仅供检查体积/产物，不能直接装
