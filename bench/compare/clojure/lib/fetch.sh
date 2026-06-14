#!/usr/bin/env bash
# Fetch the Clojure runtime jars used by the head-to-head benchmark.
# These are gitignored (the clojure jar alone is ~4 MB); run this once.
set -euo pipefail
cd "$(dirname "$0")"
base="https://repo1.maven.org/maven2"
curl -sSLO "$base/org/clojure/clojure/1.12.1/clojure-1.12.1.jar"
curl -sSLO "$base/org/clojure/spec.alpha/0.5.238/spec.alpha-0.5.238.jar"
curl -sSLO "$base/org/clojure/core.specs.alpha/0.4.74/core.specs.alpha-0.4.74.jar"
echo "fetched clojure jars into $(pwd)"
