#!/bin/sh
# Runs in a container of a Linux distribution, as root (the "Linux install commands" jobs of cross-platform.yml):
#   /w    the plugin's and the tests' classes and resources, and jna.jar
#   /jdk  a JDK built for glibc (mode "java")
# Mode "java" (glibc distributions):
#   1. the plugin's libheif backend must report LINUX_LIBHEIF_MISSING on the fresh system;
#   2. the install command the plugin suggests for this distribution is run, adapted to a non-interactive root shell
#      (no sudo; -y, -n, --noconfirm; the packages and repositories are unchanged);
#   3. the backend must then be available and decode the fixtures correctly.
# Mode "alpine" (musl: JNA's library in jna.jar is built for glibc): the suggested command is run and the libraries
#   must be installed. Mode "nix": the NixOS command (channel "nixpkgs" instead of "nixos" in the nixos/nix image) must
#   put libheif.so.1 where the plugin looks for it.
# $PREPARE is run first (e.g. apt-get update).
set -eu
mode=${1:-java}
export DEBIAN_FRONTEND=noninteractive
classpath=/w/classes/main:/w/resources/main:/w/classes/test:/w/resources/test:/w/jna.jar

check() {
  "$java" -Djava.awt.headless=true -cp "$classpath" cn.yooss.heic.linux.DistroCheck "$@"
}

adapt() {
  sed -e 's/sudo //g' \
      -e 's/apt install /apt-get install -y --no-install-recommends /g' \
      -e 's/dnf install /dnf install -y /g' \
      -e 's/zypper /zypper -n /g' \
      -e 's/pacman -S /pacman -Sy --noconfirm /g' \
      -e 's/nix-env -iA nixos\./nix-env -iA nixpkgs./g'
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
  nix)
    # LibheifRemedyTest pins the NixOS command: nix-env -iA nixos.libheif.lib
    echo "Suggested: nix-env -iA nixos.libheif.lib"
    nix-env -iA nixpkgs.libheif.lib
    ls -l "$HOME/.nix-profile/lib/libheif.so.1"
    ;;
  *)
    echo "unknown mode $mode"; exit 2
    ;;
esac
echo "== OK"
