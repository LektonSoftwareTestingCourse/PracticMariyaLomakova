#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_root"
mkdir -p target/practice-5/logs
compose_file="$project_root/services/api-tests/compose.yaml"
compose_project=${API_TEST_PROJECT:-smp-api-tests}
compose=(docker compose -p "$compose_project" -f "$compose_file")

# Фиксированная версия Maven позволяет запускать проверки при наличии только Java и Docker.
maven=${MVN:-}
if [[ -z "$maven" ]]; then
    if command -v mvn >/dev/null 2>&1; then
        maven=$(command -v mvn)
    else
        tools_dir="$project_root/target/practice-5/tools"
        maven="$tools_dir/apache-maven-3.9.9/bin/mvn"
        if [[ ! -x "$maven" ]]; then
            mkdir -p "$tools_dir"
            distribution=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz
            curl -fsSL "$distribution" -o "$tools_dir/maven.tar.gz"
            curl -fsSL "$distribution.sha512" -o "$tools_dir/maven.sha512"
            expected=$(cat "$tools_dir/maven.sha512")
            if command -v sha512sum >/dev/null 2>&1; then
                actual=$(sha512sum "$tools_dir/maven.tar.gz" | awk '{print $1}')
            else
                actual=$(shasum -a 512 "$tools_dir/maven.tar.gz" | awk '{print $1}')
            fi
            [[ "$expected" == "$actual" ]]
            tar -xzf "$tools_dir/maven.tar.gz" -C "$tools_dir"
        fi
    fi
fi

finish() {
    result=$?
    trap - EXIT
    "${compose[@]}" logs --no-color > target/practice-5/logs/compose.log 2>&1 || true
    if [[ ${API_TEST_KEEP_STACK:-0} != 1 ]]; then
        "${compose[@]}" down -v --remove-orphans || true
    fi
    exit "$result"
}
trap finish EXIT

if [[ ${API_TEST_REUSE_STACK:-0} != 1 ]]; then
    "$maven" -B -ntp -f services/pom.xml \
        -pl common,gateway,card-management,switch,authorization,terminal-simulator,merchant-acquirer,transaction-logger,bin-lookup \
        -am clean package 2>&1 | tee target/practice-5/logs/build.log
    "$maven" -B -ntp -f services/pom.xml -Papi-tests -DskipTests package
    "${compose[@]}" up -d --build
fi

for ((attempt=1; attempt<=120; attempt++)); do
    if curl -fsS http://localhost:18080/health >/dev/null 2>&1; then
        break
    fi
    if [[ "$attempt" == 120 ]]; then
        echo 'API stack did not become ready within 240 seconds' >&2
        exit 1
    fi
    sleep 2
done

for ((run=1; run<=${API_TEST_RUNS:-1}; run++)); do
    "$maven" -B -ntp -f services/pom.xml -Papi-tests \
        -Dapi.compose.file="$compose_file" -Dapi.compose.project="$compose_project" \
        "$@" clean verify 2>&1 | tee "target/practice-5/logs/run-$run.log"
    mkdir -p "target/practice-5/run-$run"
    cp -R services/api-tests/target/surefire-reports "target/practice-5/run-$run/"
done
