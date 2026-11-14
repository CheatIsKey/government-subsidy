# API 설계

> 상태: **1단계 승인 (2026-11-14).** [미확인] 항목은 실제 API 확인 후 바뀔 수 있고, 바꿀 때는 다시 승인받는다. 모든 예시 값(서비스ID, 서비스명, URL)은 **설명용으로 지어낸 값**이고, 실제 데이터가 아니다.

## 1. 공통

| 항목 | 내용 |
|---|---|
| 기본 경로 | 공개 `/api/v1/**`, 운영 `/ops/v1/**` (보호 방법 Q-17), 상태 점검 `/actuator/health` |
| 인증 | 공개 API는 없음 (익명). 운영 API는 Q-17 |
| 형식 | 요청과 응답은 JSON (UTF-8). 스트리밍만 `text/event-stream` |
| 추적 | 모든 응답에 `X-Trace-Id` 헤더를 넣는다. 에러 본문에도 traceId를 넣는다 |
| 요청 한도 | 넘으면 429와 `Retry-After` 헤더 (OPS-1, 수치 Q-16) |
| 에러 형식 | RFC 9457 Problem Details(표준 에러 본문 형식, Spring 6이 기본 지원)에 `code`, `traceId`를 더한다 |

에러 본문 예시:
```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "code": "COMMON_RATE_LIMITED",
  "detail": "요청이 너무 많아요. 30초 뒤에 다시 시도해 주세요.",
  "traceId": "8f2c1a..."
}
```

## 2. 공개 API

### 2.1 `POST /api/v1/chat`: 상담 (한 번에 받는 방식)

요청:
```json
{ "question": "서울 사는 27살 취준생인데 월세 지원 받을 수 있는 거 있어?" }
```

응답 200:
```json
{
  "traceId": "8f2c1a...",
  "status": "ANSWERED",
  "answer": "말씀하신 조건(서울, 27세, 구직 중)에 맞을 수 있는 월세 지원이 있어요. (예시) 청년 월세 지원은 ... [1]",
  "conditions": {
    "age": 27,
    "region": "서울",
    "targets": [{ "code": "JA0327", "label": "구직자/실업자" }],
    "topics": ["월세"]
  },
  "citations": [
    {
      "ref": 1,
      "serviceId": "EXAMPLE-0001",
      "name": "(예시) 청년 월세 지원",
      "agencyName": "(예시) 서울특별시",
      "detailUrl": "https://example.invalid/benefit/EXAMPLE-0001",
      "sourceModifiedAt": "2026-10-01T00:00:00+09:00"
    }
  ],
  "notices": ["PII_MASKED"],
  "degraded": false,
  "disclaimer": "최종 자격은 담당 기관에서 확인하세요."
}
```

| 필드 | 설명 |
|---|---|
| status | `ANSWERED` (근거 있는 답변) / `NO_EVIDENCE` (근거 없음, 정해진 문구) / `LLM_FAILED` (근거 목록만 줌, Q-34) |
| conditions | 추출한 조건. 사용자가 시스템이 이해한 내용을 확인하는 용도 (CHAT-2) |
| citations | 인용 검증(CHAT-5)을 통과한 근거만 |
| notices | `PII_MASKED` (개인정보를 가림, Q-15), `CONDITIONS_IGNORED` (모순이 있어 조건을 적용하지 않음, Q-07) |
| degraded | 일부 기능이 빠진 상태로 답했는지 (예: 벡터 검색 없이 BM25만, Q-33) |
| disclaimer | Q-23이 승인되면 넣음 |

| 상태 코드 | 언제 |
|---|---|
| 200 | 정상. NO_EVIDENCE와 LLM_FAILED도 200 (요청 자체는 처리됐기 때문) |
| 400 | 질문 없음·빈 질문·형식 오류 (`CHAT_EMPTY_QUESTION`, `COMMON_INVALID_INPUT`) |
| 413 | 본문이 너무 큼 |
| 422 | 질문 길이 초과 (`CHAT_QUESTION_TOO_LONG`). 400으로 할지는 함께 확인 |
| 429 | 요청 한도 초과, 동시 생성 상한 초과 |
| 503 | 검색 불가(`SEARCH_UNAVAILABLE`), 하루 상한 소진(`CHAT_DAILY_LIMIT`), Q-34에서 "근거 목록만 주기"를 안 고른 경우의 LLM 장애 |
| 504 | 전체 시간 예산 초과 (`CHAT_TIMEOUT`) |

### 2.2 `POST /api/v1/chat/stream`: 상담 (SSE 스트리밍, CHAT-6, Q-18)

- 브라우저 기본 `EventSource`는 GET만 지원한다. 그런데 GET으로 보내면 질문이 URL에 들어가서 접속 로그에 남는다(개인정보 문제). 그래서 **POST + fetch 스트림 읽기**를 제안한다.
- 요청 본문은 2.1과 같다. 처리를 시작하기 전에 생긴 에러(400/429 등)는 일반 HTTP 에러로 돌려준다.

이벤트 순서:
```
event: conditions
data: {"age":27,"region":"서울","targets":[{"code":"JA0327","label":"구직자/실업자"}],"topics":["월세"]}

event: sources
data: {"candidates":[{"ref":1,"serviceId":"EXAMPLE-0001","name":"(예시) 청년 월세 지원"}]}

event: token
data: {"text":"말씀하신 조건에 "}

event: verdict
data: {"status":"ANSWERED","verifiedRefs":[1],"removedRefs":[]}

event: done
data: {"traceId":"8f2c1a...","degraded":false}
```
스트리밍 도중 에러가 나면 `event: error` / `data: {"code":"CHAT_LLM_TIMEOUT","message":"..."}`를 보낸 뒤 연결을 닫는다. HTTP 상태는 이미 200으로 나간 뒤다. `verdict`가 `NO_EVIDENCE`이면 화면은 이미 받은 글자를 지우고 정해진 문구로 바꾼다 (Q-21).

### 2.3 `GET /api/v1/benefits/{serviceId}`: 혜택 상세 (BENEFIT-1~3)

응답 200:
```json
{
  "serviceId": "EXAMPLE-0001",
  "status": "ACTIVE",
  "name": "(예시) 청년 월세 지원",
  "purpose": "...",
  "target": "...",
  "selectionCriteria": "...",
  "supportContent": "...",
  "applyMethod": "...",
  "applyDeadline": "...",
  "agency": { "type": "...", "name": "(예시) 서울특별시", "department": "..." },
  "contact": "...",
  "conditions": [
    { "group": "나이", "text": "19 ~ 34세" },
    { "group": "생애·대상", "code": "JA0327", "text": "구직자/실업자" }
  ],
  "links": { "detailUrl": "https://example.invalid/...", "onlineApplyUrl": null },
  "source": { "name": "행정안전부 공공서비스(혜택) 정보", "modifiedAt": "2026-10-01T00:00:00+09:00", "syncedAt": "2026-11-14T03:10:00+09:00" }
}
```

| 상태 코드 | 에러 코드 | 언제 |
|---|---|---|
| 200 | | 서비스가 있음 (ACTIVE) |
| 400 | COMMON_INVALID_INPUT | ID 형식이 이상함 (허용 형식은 Q-30) |
| 404 | BENEFIT_NOT_FOUND | 없는 ID |
| 410 | BENEFIT_REMOVED | 삭제된 서비스 (Q-12) |
| 429 | COMMON_RATE_LIMITED | 한도 초과 |
| 503 | DB_UNAVAILABLE | DB 장애 |

### 2.4 `GET /api/v1/search?q=...&size=10`: 공개 검색 (SEARCH-6, Q-26 결정: hybrid 고정)

응답 200: `{ "items": [{ "serviceId": "...", "name": "...", "agencyName": "...", "snippet": "...", "detailUrl": "..." }], "degraded": false }`
상태 코드: 200 / 400 / 429 / 503. 공개 경로에서는 `mode`를 받지 않는다 (hybrid로 고정, 제안).

## 3. 운영 API (Q-17 결정: 인터넷에 열지 않음. local 프로필 전용)

> **응답 모양 (2026-11-14 결정)**
> - 목록(`GET /ops/v1/sync-runs`, `.../failures`)은 `{"items":[...]}`, 최신 실행이 먼저(runId 내림차순).
> - 실행 단건은 평평한 필드: `runId, type, status, errorCode, startedAt, finishedAt, sourceTotalCount, fetched, created, updated, unchanged, removed, failed`.
> - 실패 항목: `stage, serviceId, page, httpStatus, errorCode, message`.
> - 에러는 Problem Details(`application/problem+json`)에 `status`, `code`. 예: 없는 runId → 404 `SYNC_RUN_NOT_FOUND`.
> - 실행 중 건수는 페이지 하나를 처리할 때마다 따로 커밋해서 반영한다(`fetched`, `heartbeat_at`).

| 메서드 | 경로 | 요청 | 응답 | 상태 코드 |
|---|---|---|---|---|
| POST | `/ops/v1/sync-runs` | `{"type":"INCREMENTAL"}` 또는 `{"type":"FULL"}` | `{"runId":42,"status":"RUNNING"}` | 202 / 400 / 401 / 409 `SYNC_ALREADY_RUNNING` |
| GET | `/ops/v1/sync-runs?size=20` | | 실행 목록 | 200 / 401 |
| GET | `/ops/v1/sync-runs/{runId}` | | 상태, 건수, 에러 요약 | 200 / 401 / 404 `SYNC_RUN_NOT_FOUND` |
| GET | `/ops/v1/sync-runs/{runId}/failures` | | 실패 목록 | 200 / 401 / 404 |
| POST | `/ops/v1/index-rebuilds` | `{}` | `{"runId":43,"newIndex":"benefits_v2"}` | 202 / 401 / 409 |
| POST | `/ops/v1/search-debug` | `{"q":"월세","mode":"bm25","size":10}` | 방식별 순위와 점수 | 200 / 400 `SEARCH_INVALID_MODE` / 401 |
| POST | `/ops/v1/eval-runs` | `{"datasetVersion":"v1","modes":["bm25","vector","hybrid"],"k":[5,10]}` | `{"evalRunId":7}` | 202 / 400 / 401 / 409 |
| GET | `/ops/v1/eval-runs/{id}` | | 방식 × k 별 nDCG·Recall 표, 실패 질문 목록, 환경 기록 | 200 / 401 / 404 |
| GET | `/actuator/health` | | `{"status":"UP"}`만 공개 | 200 / 503 |

동기화는 원래 **별도 Scheduled Task 프로세스**에서 돈다. 그래서 API 서버에서 `POST /ops/v1/sync-runs`를 부르면 (a) API 서버 안에서 직접 동기화를 돌리거나 (b) ECS RunTask를 호출해야 한다. (b)라면 API 서버에 AWS 권한(IAM)이 필요하다. Q-17에서 정한다.

## 4. 에러 코드 체계

형식은 `<영역>_<이유>`다. 영역은 COMMON, CHAT, SEARCH, BENEFIT, SYNC, OPS.

| 코드 | HTTP | 언제 | 사용자 메시지 (예) |
|---|---|---|---|
| COMMON_INVALID_INPUT | 400 | 형식 오류, 필수 필드 없음 | 요청 형식이 올바르지 않아요. |
| COMMON_PAYLOAD_TOO_LARGE | 413 | 본문이 너무 큼 | 요청이 너무 커요. |
| COMMON_RATE_LIMITED | 429 | IP 한도 초과 | 잠시 뒤 다시 시도해 주세요. |
| CHAT_EMPTY_QUESTION | 400 | 질문이 비었거나 공백뿐 | 질문을 입력해 주세요. |
| CHAT_QUESTION_TOO_LONG | 422 | 길이 초과 (Q-39) | 질문은 N자 이내로 써 주세요. |
| CHAT_BUSY | 429 | 동시 생성 상한 초과 (Q-16) | 지금 상담이 몰려 있어요. |
| CHAT_DAILY_LIMIT | 503 | 하루 LLM 상한 소진 (OPS-7) | 오늘 상담 가능 횟수를 다 썼어요. |
| CHAT_LLM_TIMEOUT | 504 / SSE error | LLM 시간 초과 | 답변 생성이 늦어지고 있어요. |
| CHAT_LLM_UNAVAILABLE | 503 / SSE error | LLM 다운, 5xx | AI 답변을 만들 수 없어요. |
| CHAT_LLM_QUOTA_EXCEEDED | 503 / SSE error | Gemini 429 (무료 한도) | AI 답변을 만들 수 없어요. |
| CHAT_TIMEOUT | 504 | 전체 시간 예산 초과 | 처리 시간이 초과됐어요. |
| SEARCH_UNAVAILABLE | 503 | ES 장애 (Q-32) | 검색을 할 수 없어요. |
| SEARCH_INVALID_MODE | 400 | 모르는 mode | |
| BENEFIT_NOT_FOUND | 404 | 없는 ID | 해당 서비스를 찾을 수 없어요. |
| BENEFIT_REMOVED | 410 | 삭제된 서비스 | 더 이상 제공되지 않는 서비스예요. |
| DB_UNAVAILABLE | 503 | DB 장애 | |
| SYNC_ALREADY_RUNNING | 409 | 동기화나 재구축이 진행 중 | |
| SYNC_RUN_NOT_FOUND | 404 | | |
| OPS_UNAUTHORIZED | 401 | 운영 인증 실패 (Q-17) | |

LLM 에러 원문, 스택 트레이스, 내부 주소는 응답에 넣지 않는다.

동기화 실행 기록에만 쓰는 코드(HTTP 응답이 아님): `AUTH_FAILED`, `SOURCE_RATE_LIMITED`, `SOURCE_UNAVAILABLE`, `SOURCE_EMPTY`, `SCHEMA_CHANGED`, `MASS_REMOVAL_SUSPECTED`, `EMBEDDING_DIM_MISMATCH`, `ABORTED_STALE`.
