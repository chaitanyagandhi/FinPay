#!/usr/bin/env bash
#
# Prints the path of a JDK 21 installation, or exits 1 if none is found.
#
# The build enforces Java 21 (maven-enforcer-plugin, [21,22)), but a JDK 21 is not
# necessarily the machine default. On macOS, Homebrew's openjdk@21 is keg-only and is not
# picked up by /usr/libexec/java_home at all, so this probes the usual locations and
# validates each candidate by asking javac for its version.
#
# Usage:  export JAVA_HOME="$(infrastructure/scripts/java-home.sh)"

set -euo pipefail

candidates=()

# An already-configured JAVA_HOME wins if it is actually a 21.
if [[ -n "${JAVA_HOME:-}" ]]; then
  candidates+=("${JAVA_HOME}")
fi

# macOS: Homebrew (Apple silicon, then Intel), then the system JDK registry.
candidates+=(
  "/opt/homebrew/opt/openjdk@21"
  "/usr/local/opt/openjdk@21"
)
if [[ -x /usr/libexec/java_home ]]; then
  registry_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [[ -n "${registry_home}" ]]; then
    candidates+=("${registry_home}")
  fi
fi

# Linux: common distribution and SDKMAN layouts.
candidates+=(
  "/usr/lib/jvm/java-21-openjdk"
  "/usr/lib/jvm/java-21-openjdk-amd64"
  "/usr/lib/jvm/java-21-openjdk-arm64"
  "/usr/lib/jvm/temurin-21-jdk"
  "${HOME}/.sdkman/candidates/java/current"
)

for candidate in "${candidates[@]}"; do
  javac_bin="${candidate}/bin/javac"
  [[ -x "${javac_bin}" ]] || continue

  version="$("${javac_bin}" -version 2>&1 | awk '{print $2}')"
  if [[ "${version}" == 21.* ]]; then
    echo "${candidate}"
    exit 0
  fi
done

echo "No JDK 21 found. Install one, for example: brew install openjdk@21" >&2
exit 1
