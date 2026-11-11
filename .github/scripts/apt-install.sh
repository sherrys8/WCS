#!/usr/bin/env bash
# 装系统依赖，带重试。
#
# 为什么不用一行 `apt-get install`：hosted runner 偶尔整体解析不了 *.ubuntu.com
# （packages.microsoft.com 却正常），apt 会对每个源反复重试，一次要耗掉十几分钟，
# 最后只留一行 exit code 100，看着像代码挂的。
#
# 这里的做法：
#   1. 已经装好的包直接跳过，根本不联网；
#   2. 每次尝试前先做一次极快的 DNS 探测，解析不了就等几秒再来，
#      不会让 apt 自己在那儿长时间重试；
#   3. 退避重试若干次，全失败才真正报错，并把原因写清楚。
set -uo pipefail

log() { printf '[apt-install] %s\n' "$*"; }

if [ "$#" -eq 0 ]; then
  log "没有传入包名"
  exit 1
fi

# runner 上 apt 用的就是这几个域名；任一解析失败就没必要往下走
MIRROR_HOSTS=(archive.ubuntu.com security.ubuntu.com azure.archive.ubuntu.com)

dns_ok() {
  local host
  for host in "${MIRROR_HOSTS[@]}"; do
    if getent ahostsv4 "$host" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

missing=()
for pkg in "$@"; do
  if dpkg -s "$pkg" >/dev/null 2>&1; then
    log "$pkg 已安装，跳过"
  else
    missing+=("$pkg")
  fi
done

if [ "${#missing[@]}" -eq 0 ]; then
  log "依赖已全部就绪"
  exit 0
fi

APT_OPTS=(
  -y
  -o Acquire::Retries=2
  -o Acquire::ForceIPv4=true
  -o Acquire::http::Timeout=20
  -o Acquire::https::Timeout=20
)

# 这两个可以在本地调小做验证，CI 里用默认值
attempts=${APT_INSTALL_ATTEMPTS:-6}
backoff_base=${APT_INSTALL_BACKOFF:-15}

for i in $(seq 1 "$attempts"); do
  if ! dns_ok; then
    log "第 $i/$attempts 次：apt 源域名解析失败，稍后再试"
  else
    log "第 $i/$attempts 次：安装 ${missing[*]}"
    # 单条命令再套一层超时，避免某次卡死把整个 job 拖到 6 小时上限
    if timeout 420 sudo apt-get update "${APT_OPTS[@]}" \
      && timeout 420 sudo apt-get install "${APT_OPTS[@]}" "${missing[@]}"; then
      log "安装完成：${missing[*]}"
      exit 0
    fi
    log "第 $i/$attempts 次失败"
  fi

  if [ "$i" -lt "$attempts" ]; then
    backoff=$((i * backoff_base))
    log "${backoff}s 后重试"
    sleep "$backoff"
  fi
done

log "重试 ${attempts} 次仍失败：${missing[*]}"
log "如果反复卡在解析上，是 runner 网络问题，过一段时间重跑即可"
exit 1
