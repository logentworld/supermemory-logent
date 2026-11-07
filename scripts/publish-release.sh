#!/usr/bin/env bash
# 태그와 VERSION이 같은 산출물만 GitHub 릴리스에 올려요.
set -euo pipefail
cd "$(dirname "$0")/.."
built=false
if [[ "${1:-}" == --built ]]; then built=true; shift; fi
version="$(tr -d '[:space:]' < VERSION)"
tag="v$version"
[[ "$(git rev-parse "$tag^{commit}")" == "$(git rev-parse HEAD)" ]] || { echo "현재 커밋과 VERSION 태그가 달라요." >&2; exit 1; }
if [[ "$built" == false ]]; then
    key="${LOGENT_PLUGIN_SIGNING_KEY_FILE:-${LOGENT_CONTENT_SIGNING_KEY_FILE:-}}"
    : "${key:?플러그인 .lpg를 서명할 Git 밖 개인 키 파일을 지정해 주세요.}"
    export LOGENT_PLUGIN_SIGNING_KEY_FILE="$key"
    ./scripts/test.sh
    ./scripts/build.sh
fi
name="logent-supermemory"
dist="$PWD/build/dist"
assets=("$dist/$name-v$version.lpg" "$dist/$name-v$version.aar" "$dist/$name-v$version-source.zip" "$dist/SHA256SUMS")
for asset in "${assets[@]}"; do [[ -s "$asset" ]] || { echo "릴리스 산출물이 없어요: $asset" >&2; exit 1; }; done
repo="$(git remote get-url origin)"
if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
    gh release upload "$tag" "${assets[@]}" --repo "$repo" --clobber
else
    gh release create "$tag" "${assets[@]}" --repo "$repo" --verify-tag --title "$name $tag" --notes "${1:-서명된 .lpg 플러그인 패키지와 보조 AAR·소스 ZIP을 배포해요.}"
fi
