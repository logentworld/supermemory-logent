# supermemory-logent

[supermemoryai/supermemory](https://github.com/supermemoryai/supermemory)를 실제로 포크해
Logent의 `memory` 장기 기억 도구를 `.lpg` 플러그인으로 묶은 저장소예요. 원본 저장소의
앱·패키지·이력은 그대로 두고, Logent에 필요한 Kotlin 플러그인만 `logent` 브랜치에 얹어요.

- 원본: `supermemoryai/supermemory` main `16ed414e0e0c61c946b7687ec813b2064fe75dd4`
  (`server-v0.0.9` 이후 스냅샷, MIT)
- 포크: `logentworld/supermemory-logent` (부모: `supermemoryai/supermemory`)
- 원본 README 원문: [`README.upstream.md`](README.upstream.md)
- Logent 변경 내역: [`NOTICE`](NOTICE)
- 플러그인 id: `com.sleepysoong.logent.supermemory`, 도구 이름: `memory`

`logent-plugin.json`을 `scripts/plugin-pack.py build --definition logent-plugin.json`의
공통 정의로 사용해요. 플러그인 key는 `sleepysoong:supermemory`, `dependencies`는 `[]`예요.
`sleepysoong`는 명시한 publisher 별칭이며 GitHub 인증 계정이나 소유권을 뜻하지 않아요.
key와 의존성에서 생성한 manifest의 `requires`는 서명 metadata에 포함되고,
`content-public-key.der.b64`에 고정한 공개키로 서명과 파일 해시를 검증해요.

## 무엇을 하나요

Supermemory v5 REST로 장기 기억을 저장·회상하고, 네임스페이스 프로필을 읽고, id로 정확히
삭제해요. 코어 앱에서 쓰던 동작·응답 정규화·크기·시간 한도·오류 문구를 그대로 옮겼고,
네트워크는 [`PluginHost.http`](https://github.com/logentworld/plugin)만 사용해요. 호스트가
HTTPS·공용 주소·리다이렉트·시간·응답 크기를 검사해요.

| action | 요청 | 하는 일 |
|---|---|---|
| `save` | `POST /ns/{ns}/document` | `content`를 `dreaming=instant`로 저장해요. |
| `recall` | `POST /ns/{ns}/search` | `searchMode=hybrid`로 관련 기억·문서 조각을 찾아요. |
| `context` | `GET/POST /ns/{ns}/profile`, 예비 `POST /v4/profile` | 네임스페이스 프로필을 정리해요. |
| `forget` | `DELETE /ns/{ns}/memories` | `ids`에 적은 기억만 정확히 삭제해요. |

`forget`은 의미 기반 일괄 삭제를 지원하지 않아요. 먼저 `recall`로 id를 확인해 주세요.
기억 본문과 오류 응답은 `<untrusted_web>` 경계 안에서 데이터로만 보여 줘요.

## 설정

| 키 | 종류 | 기본값 | 설명 |
|---|---|---|---|
| `api-key` | `SECRET` | 없음 | `console.supermemory.ai` API 키예요. 비밀로 저장하고 로그·프롬프트·도구 결과에 넣지 않아요. |
| `namespace` | `TEXT` | `logent` | 기억을 나눌 이름이에요. 영문·숫자·`_`·`-`·`:` 만 쓸 수 있어요. |

키가 없으면 요청을 보내지 않고 설정을 안내하는 오류를 돌려줘요. 키를 바꾸면 다음 호출부터
바로 반영돼요.

## 구조

```text
apps/, packages/, skills/   upstream 원본 (수정하지 않아요)
plugin/                     Logent Kotlin 플러그인 (:plugin)
  src/main/kotlin/com/sleepysoong/logent/supermemory/
    SupermemoryPlugin.kt    LogentPlugin 구현, 설정·프롬프트
    SupermemoryTool.kt      memory action 디스패치
    SupermemorySpec.kt      모델 도구 스키마
    SupermemoryClient.kt    v5 REST 요청·응답 정규화
  src/test/kotlin/com/sleepysoong/logent/supermemory/
scripts/                    Gradle 래퍼 실행, 테스트, .lpg 패키징·서명
```

`plugin/`은 코어 앱을 import하지 않고 `com.sleepysoong.logent:logent-plugin-api:4.0.0` 계약만 구현해요.
API artifact와 소스 커밋은 [`plugin-api.lock`](plugin-api.lock)에 함께 고정해요.
upstream의 설치 스크립트와 Claude 마켓플레이스 파일은 `.lpg`에 넣지 않아요.

## 검증과 빌드

Android SDK 37, JDK 21, Python 3.11 이상이 필요해요. API artifact는 GitHub Packages에서
받으므로 읽기 권한이 있는 `GITHUB_ACTOR`와 `GITHUB_TOKEN`을 환경에 지정해 주세요.

```bash
./scripts/test.sh          # JVM 단위 테스트 + Android lint
./scripts/build.sh         # 서명된 .lpg, AAR, 소스 ZIP, SHA256SUMS
```

서명 개인키는 저장소 밖에만 두고 `LOGENT_PLUGIN_SIGNING_KEY_FILE`로 지정해요. 공개키는
`content-public-key.der.b64`와 짝이 맞아야 해요. `build/dist/`에
`logent-supermemory-v<VERSION>.lpg`가 생겨요.

## upstream 업데이트

```bash
git fetch upstream
git switch logent
git merge upstream/main
./scripts/test.sh
git push origin logent
```

합친 뒤 `UPSTREAM_COMMIT`을 갱신하고, Logent 변경이 `plugin/` 밖으로 새지 않았는지 확인해요.

## 라이선스

upstream 코드와 문서는 MIT이며 루트 [`LICENSE`](LICENSE)를 그대로 보존해요.
`plugin/`의 Logent Kotlin 통합 코드는 GPL-3.0([`plugin/LICENSE`](plugin/LICENSE))이에요.
원본·변경 고지는 [`NOTICE`](NOTICE)에 있어요.
