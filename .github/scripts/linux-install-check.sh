#!/bin/sh
# Runs the libheif install command the plugin suggests, as root in a container of a Linux distribution:
#   /w    the plugin's and the tests' classes and resources, and jna.jar
#   /jdk  a JDK built for glibc (mode "java")
# Mode "java": the backend must report LINUX_LIBHEIF_MISSING, then the command is run (without sudo, non-interactive),
#   then the backend must be available and decode the fixtures.
# Mode "alpine": the command is run and the libraries must be installed (JNA's library in jna.jar needs glibc).
# $PREPARE runs first (e.g. apt-get update). $KNOWN lists the decode checks this distribution's libheif fails.
set -eu
mode=${1:-java}
export DEBIAN_FRONTEND=noninteractive
classpath=/w/classes/main:/w/resources/main:/w/classes/test:/w/resources/test:/w/jna.jar

check() {
  "$java" -Djava.awt.headless=true -Dheic.check.known="${KNOWN:-}" -cp "$classpath" cn.yooss.heic.linux.DistroCheck "$@"
}

adapt() {
  sed -e 's/sudo //g' \
      -e 's/apt install /apt-get install -y --no-install-recommends /g' \
      -e 's/dnf install /dnf install -y /g' \
      -e 's/zypper /zypper -n /g' \
      -e 's/pacman -S /pacman -Sy --noconfirm /g'
}

run() {
  echo "Suggested: $1"
  adapted=$(printf '%s\n' "$1" | adapt)
  echo "Running:   $adapted"
  sh -c "$adapted"
}

if [ -f /etc/os-release ]; then echo "== $(. /etc/os-release && echo "$PRETTY_NAME") ($mode)"; fi
if [ -n "${PREPARE:-}" ]; then sh -c "$PREPARE"; fi

case "$mode" in
  java)
    java=/jdk/bin/java
    check status LINUX_LIBHEIF_MISSING
    command=$(check command LINUX_LIBHEIF_MISSING)
    run "$command"
    check status available
    check decode
    ;;
  alpine)
    apk add --no-cache openjdk17-jre-headless > /dev/null
    java=/usr/lib/jvm/java-17-openjdk/bin/java
    command=$(check command LINUX_LIBHEIF_MISSING)
    run "$command"
    ls -l /usr/lib/libheif.so.1 /usr/lib/libde265.so.0
    ;;
  *)
    echo "unknown mode $mode"; exit 2
    ;;
esac
echo "== OK"
