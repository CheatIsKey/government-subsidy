# government-subsidy

정부 혜택(공공서비스) 정보를 검색하고, AI 상담원이 근거와 함께 "나에게 맞는 지원금"을 안내하는 서비스입니다.

> 설계 단계입니다. 문서는 `docs/` 에 있습니다.

| 영역 | 기술 |
|---|---|
| 백엔드 | Spring Boot (Java 17) |
| 검색 | Elasticsearch (nori 형태소 분석, BM25 + 벡터 하이브리드 검색) |
| AI | 로컬 개발: EXAONE 3.5 (Ollama) · 공개 데모: Gemini API · 임베딩: bge-m3 |
| 데이터 | 행정안전부 공공서비스(혜택) 정보 API (공공데이터포털) |
| 배포 | AWS ECS Fargate |

## 사용한 AI 모델
- EXAONE 3.5 7.8B (LG AI Research) — 로컬 개발·학습 용도로만 사용, EXAONE AI Model License Agreement (NC)
- bge-m3 (BAAI) — MIT License

## 개발 방식
요구사항·예외 케이스 분석 → 사람 승인 → 테스트 먼저(Red) → 구현(Green) → 검증 순서로 개발합니다. 규칙은 [`CLAUDE.md`](CLAUDE.md), 단계별 AI 에이전트는 [`.claude/agents/`](.claude/agents) 에 있습니다.

## 브랜치 규칙

| 브랜치 | 용도 | 규칙 |
|---|---|---|
| `main` | 릴리스 | 직접 push 금지, force push·삭제 금지. `dev` → `main` PR로만 병합 |
| `dev` | 개발 기본 브랜치 | force push·삭제 금지. 작업 브랜치 PR로만 병합 |
| `feature/{기능명}`, `fix/{기능명}`, `docs/{기능명}` | 작업 | `dev`에서 만들고 `dev`로 PR |

PR은 CI(`test`)와 Claude 리뷰(`review`)를 통과해야 병합된다. Claude는 PASS면 Approve, BLOCK이면 Request changes를 남기고 `review` 검사를 실패시킨다.
