#!/usr/bin/env bash
# 装系统依赖，带重试和备用镜像。
#
# 为什么不用一行 `apt-get install`：hosted runner 偶尔整体解析不了 *.ubuntu.com
# （packages.microsoft.com 却正常），apt 会对每个源反复重试，一次要耗掉十几分钟，
# 最后只留一行 exit code 100，看着像代码挂的。
#
# 这里的做法：
#   1. 已经装好的包直接跳过，根本不联网；
#   2. 先用默认源试，每次尝试前做一次极快的 DNS 探测，解析不了就快速跳过这一轮，
#      不让 apt 自己在那儿长时间重试；
#   3. 默认源实在不通，就找一个能解析、能下 Release 的镜像，写一份临时源，
#      用 `-o Dir::Etc::sourcelist=` 让 apt 只读这一份——系统源文件一个字都不改；
#   4. 全失败才真正报错，并把原因写清楚。
set -uo pipefail

# 注意走 stderr：find_mirror 是用命令替换取的，日志混进 stdout 会被当成镜像地址
log() { printf '[apt-install] %s\n' "$*" >&2; }

if [ "$#" -eq 0 ]; then
  log "没有传入包名"
  exit 1
fi

# runner 上 apt 用的就是这几个域名
MIRROR_HOSTS=(archive.ubuntu.com security.ubuntu.com azure.archive.ubuntu.com)

# 备用镜像：先清华/中科大，再阿里/腾讯/华为
MIRROR_CANDIDATES=(
  mirrors.tuna.tsinghua.edu.cn
  mirrors.ustc.edu.cn
  mirrors.aliyun.com
  mirrors.cloud.tencent.com
  repo.huaweicloud.com
)

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

# 允许直接指定代号，方便本地验证镜像兜底这段逻辑
codename=${APT_INSTALL_CODENAME:-}
if [ -z "$codename" ] && [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  codename=${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}
fi

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

dns_ok() {
  local host
  for host in "${MIRROR_HOSTS[@]}"; do
    if getent ahostsv4 "$host" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

# 探一个能提供当前 codename 的镜像；成功时把 base URI 打出来
find_mirror() {
  local host
  if [ -z "$codename" ]; then
    log "读不到发行版代号，跳过镜像兜底"
    return 1
  fi
  if ! command -v curl >/dev/null 2>&1; then
    log "没有 curl，跳过镜像兜底"
    return 1
  fi
  for host in "${MIRROR_CANDIDATES[@]}"; do
    if curl -fsSL -m 8 -o /dev/null "https://$host/ubuntu/dists/$codename/Release" 2>/dev/null; then
      log "可用镜像：$host（$codename）"
      printf '%s\n' "https://$host/ubuntu"
      return 0
    fi
    log "镜像不可用：$host"
  done
  return 1
}

# 写一份只含该镜像的临时源；系统源文件不动
#
# 必须是旧式单行 `deb ...`：通过 -o Dir::Etc::sourcelist 指定的文件走的是老解析器，
# 写成 deb822（Types:/URIs:）会直接报 "Type 'Types:' is not known"。
SRC_FILE=""
write_mirror_sources() {
  local uri="$1"
  local opt=""
  local suite
  local suites=()
  if [ -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ]; then
    opt="[signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] "
  else
    # 找不到 Ubuntu 官方 keyring 时放行签名，否则 apt 连索引都读不了
    opt="[trusted=yes] "
  fi

  # 只写镜像上真的存在的 suite：少一个 -security，apt-get update 就会整轮报错
  for suite in "$codename" "$codename-updates" "$codename-security"; do
    if curl -fsSL -m 8 -o /dev/null "$uri/dists/$suite/Release" 2>/dev/null; then
      suites+=("$suite")
    else
      log "镜像没有 $suite，跳过"
    fi
  done
  if [ "${#suites[@]}" -eq 0 ]; then
    log "镜像上找不到 $codename 的任何索引"
    return 1
  fi

  SRC_FILE=$(mktemp 2>/dev/null) || return 1
  : >"$SRC_FILE"
  for suite in "${suites[@]}"; do
    echo "deb ${opt}${uri} ${suite} main universe restricted multiverse" >>"$SRC_FILE"
  done
  [ -s "$SRC_FILE" ]
}

# $1 传临时源路径则只用它，传空则用系统默认源
try_install() {
  local extra=()
  if [ -n "${1:-}" ]; then
    extra=(-o "Dir::Etc::sourcelist=$1" -o "Dir::Etc::sourceparts=-")
  fi
  # 单条命令再套一层超时，避免某次卡死把整个 job 拖到 6 小时上限
  timeout 420 sudo apt-get update "${APT_OPTS[@]}" "${extra[@]}" &&
    timeout 420 sudo apt-get install "${APT_OPTS[@]}" "${extra[@]}" "${missing[@]}"
}

# 第一阶段：默认源，最多两轮，DNS 不通就快速过掉
default_rounds=$((attempts < 2 ? attempts : 2))
i=0
while [ "$i" -lt "$default_rounds" ]; do
  i=$((i + 1))
  if dns_ok; then
    log "第 $i/$default_rounds 次：默认源安装 ${missing[*]}"
    if try_install ""; then
      log "安装完成：${missing[*]}"
      exit 0
    fi
    log "默认源第 $i 次失败"
  else
    log "第 $i/$default_rounds 次：默认源域名解析失败"
  fi
  if [ "$i" -lt "$default_rounds" ]; then
    sleep $((i * backoff_base))
  fi
done

# 第二阶段：换镜像再来
log "默认源不可用，尝试备用镜像"
mirror_uri=""
if mirror_uri=$(find_mirror) && write_mirror_sources "$mirror_uri"; then
  log "改用镜像：$mirror_uri"
  j=0
  while [ "$j" -lt "$attempts" ]; do
    j=$((j + 1))
    log "第 $j/$attempts 轮（镜像）：安装 ${missing[*]}"
    if try_install "$SRC_FILE"; then
      log "安装完成：${missing[*]}（镜像 $mirror_uri）"
      rm -f "$SRC_FILE"
      exit 0
    fi
    log "镜像第 $j 轮失败"
    if [ "$j" -lt "$attempts" ]; then
      sleep $((j * backoff_base))
    fi
  done
fi

[ -n "$SRC_FILE" ] && rm -f "$SRC_FILE"

log "默认源与备用镜像都没能装成：${missing[*]}"
log "如果反复卡在解析上，是 runner 网络问题，过一段时间重跑即可"
exit 1
