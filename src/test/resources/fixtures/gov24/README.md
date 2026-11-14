# 공공 API 실제 응답 표본

2026-11-14에 행정안전부 공공서비스(혜택) 정보 API(공공데이터포털, 식별자 15113968)를 직접 호출해 받은 응답이다.
공공데이터이며, 서비스 키는 들어 있지 않다. 테스트의 가짜 API 서버가 이 파일을 돌려준다.

| 파일 | 내용 |
|---|---|
| serviceList-page1-perPage3.json | `page=1, perPage=3` 실제 응답 (null 값 포함) |
| serviceList-no-data.json | `perPage=3000` 이상일 때 실제로 오는 응답. HTTP 200인데 data가 없다 (SYNC-1-E4) |
| serviceList-beyond-last-page.json | 마지막 페이지 다음을 요청했을 때의 실제 응답 모양 |
| error-invalid-key-401.json | 잘못된 키일 때 HTTP 401 본문 |
