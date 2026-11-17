#!/usr/bin/env bash
# Claude 리뷰 판정 확인 (REVIEW-GATE)
# 사용: check-claude-verdict.sh STATE < 리뷰 본문
# 종료 코드 0(통과, "PASS" 출력) / 1(실패, "::error::" 줄로 이유 출력)
# 본문 내용은 메시지에 옮기지 않는다(워크플로 명령 주입 방지). 본문은 검사 대상으로만 쓴다.

state=${1-}

if [ "$state" = "CHANGES_REQUESTED" ]; then
  echo "::error::BLOCK — Claude 리뷰가 변경을 요청했다(CHANGES_REQUESTED)."
  exit 1
fi
if [ "$state" != "APPROVED" ]; then
  echo "::error::판정 없음 — 현재 커밋에 대한 Claude의 승인 또는 변경 요청 리뷰가 없다."
  exit 1
fi

CR=$(printf '\r')
body=$(cat)
lines=()
while IFS= read -r line || [ -n "$line" ]; do
  lines+=("${line%"$CR"}")
done <<< "$body"

WS=$' \t'
STATUS_RE="^(확인함\(문제 없음\)|확인함|해당 없음) — [^$WS]"

# 점검표 줄 머리 뒤 나머지를 돌려준다(줄 앞 공백·탭, 목록 기호 '- ' 또는 '* ' 한 개만 허용)
strip_prefix() {
  local s=$1
  while [[ $s == [$WS]* ]]; do s=${s#?}; done
  if [[ $s == "- "* || $s == "* "* ]]; then s=${s#??}; fi
  REST=$s
}

for item in "A 보안" "B 정확성" "C TDD" "D 요구사항" "E 문서" "F 결정"; do
  head="$item:"
  count=0; found=""
  for line in "${lines[@]}"; do
    strip_prefix "$line"
    if [[ $REST == "$head"* ]]; then
      count=$((count + 1)); found=${REST#"$head"}
    fi
  done
  if [ "$count" -ne 1 ]; then
    echo "::error::점검표 항목 $item 줄이 ${count}줄이다(정확히 1줄이어야 한다)."
    exit 1
  fi
  while [[ $found == [$WS]* ]]; do found=${found#?}; done
  if ! [[ $found =~ $STATUS_RE ]]; then
    echo "::error::점검표 항목 $item 형식 오류 — '<상태> — <근거>' 형식이어야 하고 상태는 확인함, 확인함(문제 없음), 해당 없음 중 하나여야 한다."
    exit 1
  fi
done

first=""
for line in "${lines[@]}"; do
  [ -n "$line" ] && { first=$line; break; }
done
if [[ $first != "PASS:"* ]]; then
  echo "::error::첫 줄이 'PASS:'로 시작하지 않는다."
  exit 1
fi

echo "PASS"
exit 0
