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
