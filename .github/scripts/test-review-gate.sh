#!/usr/bin/env bash
# REVIEW-GATE PR 리뷰 판정 확인 테스트 (docs/cases.md "## REVIEW-GATE PR 리뷰 판정 확인")
# 같은 파일 아래쪽에 REVIEW-GATE-G 잠금 파일 경비 검사 테스트가 있다 ("REVIEW-GATE-G 잠금 파일 경비 검사" 블록).
# 실행: bash .github/scripts/test-review-gate.sh
# 하나라도 실패하면 종료 코드 1. 외부 의존성 없음.
# jq가 필요한 테스트(리뷰 선택 단위, 가짜 gh)는 jq가 없으면 skip하지 않고 "jq 없음"으로 실패한다.
#
# 자동 테스트 대상이 아닌 케이스:
#   병합 뒤 수동 확인: REVIEW-GATE-S1, REVIEW-GATE-F9, REVIEW-GATE-C1, REVIEW-GATE-R2
#   검증 불가: REVIEW-GATE-C2

set -u

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CHECK="$DIR/check-claude-verdict.sh"
SELECT="$DIR/select-claude-review.jq"
FETCH="$DIR/fetch-claude-verdict.sh"

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT

# ---------- 작은 테스트 틀 ----------
PASSED=0; FAILED=0; FAILED_NAMES=(); MSGS=""

fail() { MSGS+="    $*"$'\n'; }

run_test() {  # run_test "<케이스 ID와 설명>" <함수> [인자...]
  local name=$1; shift
  MSGS=""
  "$@"
  if [ -z "$MSGS" ]; then
    PASSED=$((PASSED + 1)); printf 'ok   %s\n' "$name"
  else
    FAILED=$((FAILED + 1)); FAILED_NAMES+=("$name"); printf 'FAIL %s\n%s' "$name" "$MSGS"
  fi
}

assert_eq() { [ "$1" == "$2" ] || fail "$3: 기대 [$2], 실제 [$1]"; }

# ---------- 판정 스크립트 실행 ----------
# check [STATE]  — 표준 입력은 BODY 그대로. 결과는 OUT(stdout+stderr), CODE.
check() { OUT=$(printf '%s' "$BODY" | bash "$CHECK" "$@" 2>&1); CODE=$?; }

expect_pass() {
  assert_eq "$CODE" 0 "종료 코드"
  assert_eq "$OUT" "PASS" "출력"
}

# expect_fail [글자...]  — 종료 코드 1, PASS 줄 없음, 주어진 글자를 모두 담은 '::error::' 줄이 있음
expect_fail() {
  assert_eq "$CODE" 1 "종료 코드"
  local line s ok found=0
  while IFS= read -r line; do
    [ "$line" == "PASS" ] && fail "실패여야 하는데 PASS 줄이 나옴"
    case $line in
      '::error::'*)
        ok=1; for s in "$@"; do [[ $line == *"$s"* ]] || ok=0; done
        [ $ok -eq 1 ] && found=1 ;;
    esac
  done <<< "$OUT"
  [ $found -eq 1 ] || fail "[$*]를 담은 '::error::' 줄이 없음. 출력: $OUT"
}

# ---------- 본문 만들기 ----------
HEADERS=("A 보안:" "B 정확성:" "C TDD:" "D 요구사항:" "E 문서:" "F 결정:")

# checklist ["<줄 머리>" "<대신 쓸 줄>"]...  → CL. 대신 쓸 줄이 빈 문자열이면 그 항목 줄을 뺀다.
checklist() {
  local -A rep=()
  while [ $# -ge 2 ]; do rep["$1"]=$2; shift 2; done
  local h; CL=""
  for h in "${HEADERS[@]}"; do
    if [ -n "${rep[$h]+x}" ]; then
      [ -n "${rep[$h]}" ] && CL+="${rep[$h]}"$'\n'
    else
      CL+="$h 확인함 — 근거 파일:줄"$'\n'
    fi
  done
}

# make_body [checklist 인자...]  → BODY = "PASS: 요약" + 점검표
make_body() { checklist "$@"; BODY="PASS: 요약"$'\n'"$CL"; }

to_crlf() { BODY=${BODY//$'\n'/$'\r\n'}; }

# 본문 빌더 (단위 테스트와 R1 회귀 테스트가 함께 쓴다)
b_N1() { make_body; }
b_N2() {
  make_body "B 정확성:" "B 정확성: 확인함(문제 없음) — 예외 경로 확인" \
            "C TDD:" "C TDD: 해당 없음 — 문서만 바뀜" \
            "F 결정:" "F 결정: 해당 없음 — 관련 결정 없음"
}
b_N3() {
  checklist
  BODY="PASS: 요약"$'\n\n'"이 PR은 판정 확인을 스크립트로 옮긴다. 아래는 점검표다."$'\n\n'"$CL"$'\n'"제안: 변수 이름을 더 짧게 할 수 있다."$'\n'
}
b_E1() { make_body "${HEADERS[$1]}" ""; }
b_E2() { make_body "D 요구사항:" "D 요구사항: 확인함 — x"$'\n'"D 요구사항: 확인함 — x"; }
b_E3() { make_body "A 보안:" "A 보안: 문제 — 키가 로그에 나감"; }
b_E4() { make_body "${HEADERS[$1]}" "${HEADERS[$1]} 확인 못 함 — 시간 부족"; }
b_E5() { make_body "A 보안:" "$1"; }
b_E6() { make_body "A 보안:" "A 보안: $1 — x"; }
b_E7() { BODY=""; }
b_E11() { make_body "A 보안:" "참고: A 보안: 확인함 — x"; }
b_E12() { make_body; BODY+="참고로 A 보안: 문제 라는 표현은 예시일 뿐이다."$'\n'; }
b_E12_unknown() { make_body; BODY+="참고: '확인 못 함'은 상태 예시다."$'\n'; }
b_E13() { checklist; BODY="PASS: 요약"$'\n'"A 보안: 항목은 다음을 본다"$'\n'"$CL"; }
b_E14() { make_body "A 보안:" "A 보안: 확인함 — x B 정확성: 문제" "B 정확성:" ""; }
b_B1() {  # 모든 점검표 줄 앞에 같은 접두를 붙인다
  local h args=()
  for h in "${HEADERS[@]}"; do args+=("$h" "$1$h 확인함 — 근거"); done
  make_body "${args[@]}"
}
b_B2() { make_body "A 보안:" "$1"; }
b_B3() { make_body "A 보안:" "$1"; }
b_B4() { make_body "$1" "$2"; }
b_B5() { make_body "A 보안:" "A 보안: 확인함 — 문제가 있으나 사소"; }
b_B6() { make_body; to_crlf; }
b_B7() { make_body "A 보안:" "A 보안: "; to_crlf; }
b_B8() { make_body; BODY=${BODY%$'\n'}; }
b_B9() { make_body "A 보안:" "$1 확인함 — x"; }
b_B10() { make_body; BODY="$1"$'\n'"${BODY#*$'\n'}"; }
SPECIAL='.*[](){}^$|+?\ %s %d % :: 특수문자'
b_B11() {  # $1 = A 상태
  checklist "A 보안:" "A 보안: $1 — $SPECIAL"
  BODY="PASS: 요약 $SPECIAL"$'\n'"$SPECIAL"$'\n'"$CL"
}
b_B12() {  # $1 = F 상태
  local cmd='$(touch '"$TMPD"'/pwned) `touch '"$TMPD"'/pwned`'
  checklist "A 보안:" "A 보안: 확인함 — -n -e \\n \\ $cmd" "F 결정:" "F 결정: $1 — x"
  BODY="PASS: 요약"$'\n'"-n"$'\n'"-e"$'\n'"--help"$'\n'"\\"$'\n'"$cmd"$'\n'"$CL"
}
b_B13() { make_body "A 보안:" "$1"; }
b_B13_crlf() { make_body "A 보안:" "A 보안: 확인함 — "; to_crlf; }
b_S3() {  # $1 = A 상태. 근거 안의 \r 뒤에도 명령을 숨긴다
  checklist "A 보안:" "A 보안: $1 — ::error::INJECT3 ::add-mask::INJECT1"$'\r'"::add-mask::INJECT5"
  BODY="PASS: 요약"$'\n'"::add-mask::INJECT1"$'\n'"::warning::INJECT2"$'\n'"::error::INJECT3"$'\n'"::stop-commands::INJECT4"$'\n'"$CL"
}

# ---------- 단위: 정상 ----------
t_pass() { "$@"; check APPROVED; expect_pass; }        # t_pass <빌더> [인자...]
t_fail() {                                             # t_fail "<글자1>|<글자2>" <빌더> [인자...]
  local IFS='|'; local -a want=($1); shift; IFS=$' \t\n'
  "$@"; check APPROVED; expect_fail "${want[@]}"
}

run_test "REVIEW-GATE-N1 정상 점검표" t_pass b_N1
run_test "REVIEW-GATE-N2 확인함·확인함(문제 없음)·해당 없음 섞임" t_pass b_N2
run_test "REVIEW-GATE-N3 점검표 앞뒤 설명·제안 문단" t_pass b_N3

# ---------- 단위: 예외 ----------
for i in 0 1 2 3 4 5; do
  h=${HEADERS[$i]}
  run_test "REVIEW-GATE-E1 '$h' 줄 없음 → 0줄" t_fail "${h%:}|0줄" b_E1 "$i"
done
run_test "REVIEW-GATE-E2 같은 항목 2번 → 2줄" t_fail "D 요구사항|2줄" b_E2
run_test "REVIEW-GATE-E3 상태 '문제'" t_fail "A 보안" b_E3
run_test "REVIEW-GATE-E4 A 상태 '확인 못 함'" t_fail "A 보안" b_E4 0
run_test "REVIEW-GATE-E4 F 상태 '확인 못 함'" t_fail "F 결정" b_E4 5
run_test "REVIEW-GATE-E5 줄 머리 뒤 비어 있음" t_fail "A 보안" b_E5 "A 보안:"
run_test "REVIEW-GATE-E5 줄 머리 뒤 공백만" t_fail "A 보안" b_E5 "A 보안:   "
run_test "REVIEW-GATE-E6 상태 자리에 'OK'" t_fail "A 보안" b_E6 "OK"
run_test "REVIEW-GATE-E6 상태 자리에 '확인'" t_fail "A 보안" b_E6 "확인"
run_test "REVIEW-GATE-E7 빈 본문 → 첫 항목 0줄" t_fail "A 보안|0줄" b_E7

t_state() { b_N1; check "$1"; expect_fail "$2"; }      # t_state STATE <기대 글자>
run_test "REVIEW-GATE-E8 CHANGES_REQUESTED → BLOCK" t_state CHANGES_REQUESTED "BLOCK"
run_test "REVIEW-GATE-E9 NONE → 판정 없음" t_state NONE "판정 없음"
run_test "REVIEW-GATE-E10 COMMENTED → 판정 없음" t_state COMMENTED "판정 없음"
run_test "REVIEW-GATE-E10 빈 문자열 → 판정 없음" t_state "" "판정 없음"
run_test "REVIEW-GATE-E10 소문자 approved → 판정 없음" t_state approved "판정 없음"
t_no_arg() { b_N1; check; expect_fail "판정 없음"; }
run_test "REVIEW-GATE-E10 인자 없음 → 판정 없음" t_no_arg

run_test "REVIEW-GATE-E11 줄 머리가 문장 중간에만 → 누락" t_fail "A 보안|0줄" b_E11
run_test "REVIEW-GATE-E12 문장 중간의 'A 보안: 문제'는 세지 않음" t_pass b_E12
run_test "REVIEW-GATE-E12 설명 문단의 '확인 못 함' 예시는 세지 않음" t_pass b_E12_unknown
run_test "REVIEW-GATE-E13 설명 문단이 줄 머리로 시작 → 2줄" t_fail "A 보안|2줄" b_E13
run_test "REVIEW-GATE-E14 한 줄에 두 항목, B 줄 없음 → B 누락" t_fail "B 정확성|0줄" b_E14

# ---------- 단위: 경계 ----------
run_test "REVIEW-GATE-B1 줄 앞 공백" t_pass b_B1 "  "
run_test "REVIEW-GATE-B1 줄 앞 탭" t_pass b_B1 $'\t'
run_test "REVIEW-GATE-B1 줄 앞 '- '" t_pass b_B1 "- "
run_test "REVIEW-GATE-B1 줄 앞 '* '" t_pass b_B1 "* "
run_test "REVIEW-GATE-B1 줄 앞 들여쓴 '  - '" t_pass b_B1 "  - "

# 목록 기호는 '- ' 또는 '* ' 한 개만(기호 뒤 공백 필수). 그 밖의 서식은 거부
run_test "REVIEW-GATE-B2 굵게 서식 거부" t_fail "A 보안" b_B2 "**A 보안:** 확인함 — x"
run_test "REVIEW-GATE-B2 줄 전체 굵게 거부" t_fail "A 보안" b_B2 "**A 보안: 확인함 — x**"
run_test "REVIEW-GATE-B2 기호 뒤 공백 없음 '-' 거부" t_fail "A 보안" b_B2 "-A 보안: 확인함 — x"
run_test "REVIEW-GATE-B2 기호 뒤 공백 없음 '*' 거부" t_fail "A 보안" b_B2 "*A 보안: 확인함 — x"
run_test "REVIEW-GATE-B2 기호 두 개 '- - ' 거부" t_fail "A 보안" b_B2 "- - A 보안: 확인함 — x"
run_test "REVIEW-GATE-B2 번호 서식 거부" t_fail "A 보안" b_B2 "1. A 보안: 확인함 — x"
run_test "REVIEW-GATE-B2 인용 서식 거부" t_fail "A 보안" b_B2 "> A 보안: 확인함 — x"
run_test "REVIEW-GATE-B2 표 서식 거부" t_fail "A 보안" b_B2 "| A 보안: 확인함 — x |"
run_test "REVIEW-GATE-B2 백틱 서식 거부" t_fail "A 보안" b_B2 '`A 보안:` 확인함 — x'

run_test "REVIEW-GATE-B3 콜론 뒤 공백 0개" t_pass b_B3 "A 보안:확인함 — x"
run_test "REVIEW-GATE-B3 콜론 뒤 공백 여러 개" t_pass b_B3 "A 보안:    확인함 — x"

run_test "REVIEW-GATE-B4 상태 뒤 다른 글자 '확인함인 척'" t_fail "A 보안" b_B4 "A 보안:" "A 보안: 확인함인 척 — x"
run_test "REVIEW-GATE-B4 지침 형식 줄 복사" t_fail "A 보안" b_B4 "A 보안:" "A 보안: 확인함 | 해당 없음 | 문제 | 확인 못 함 — x"
run_test "REVIEW-GATE-B4 '확인함(문제 있음)'" t_fail "B 정확성" b_B4 "B 정확성:" "B 정확성: 확인함(문제 있음) — 예외 처리 누락"
run_test "REVIEW-GATE-B4 '해당 없음이 아님'" t_fail "B 정확성" b_B4 "B 정확성:" "B 정확성: 해당 없음이 아님 — 근거"

# B5: 근거 문장의 "문제" 단어는 형식으로 막을 수 없다(의미 판단은 Claude 몫). 한계로 기록된 동작.
run_test "REVIEW-GATE-B5 근거에 '문제' 단어 → 통과(한계)" t_pass b_B5
run_test "REVIEW-GATE-B6 CRLF 정상 본문" t_pass b_B6
run_test "REVIEW-GATE-B7 CRLF, 상태 비움" t_fail "A 보안" b_B7
run_test "REVIEW-GATE-B8 끝에 줄바꿈 없음" t_pass b_B8
run_test "REVIEW-GATE-B9 줄 머리에 NBSP → 누락" t_fail "A 보안|0줄" b_B9 $'A\xc2\xa0보안:'
run_test "REVIEW-GATE-B9 줄 머리에 전각 공백 → 누락" t_fail "A 보안|0줄" b_B9 $'A\xe3\x80\x80보안:'
# B10: 점검표가 맞은 뒤 첫 줄(앞 빈 줄은 건너뜀)이 'PASS:'(대문자, 앞 공백 없음)로 시작해야 한다
run_test "REVIEW-GATE-B10 APPROVED인데 첫 줄 BLOCK:" t_fail "첫 줄" b_B10 "BLOCK: 요약"
run_test "REVIEW-GATE-B10 APPROVED인데 첫 줄이 PASS:로 시작하지 않음" t_fail "첫 줄" b_B10 "요약: 문제 없음"
run_test "REVIEW-GATE-B10 첫 줄 'PASS 요약'(콜론 없음)" t_fail "첫 줄" b_B10 "PASS 요약"
run_test "REVIEW-GATE-B10 첫 줄 소문자 'pass:'" t_fail "첫 줄" b_B10 "pass: 요약"
run_test "REVIEW-GATE-B10 첫 줄 앞 공백 ' PASS:'" t_fail "첫 줄" b_B10 " PASS: 요약"
run_test "REVIEW-GATE-B10 첫 줄 '요약', 둘째 줄 'PASS:'" t_fail "첫 줄" b_B10 "요약"$'\n'"PASS: x"
run_test "REVIEW-GATE-B10 본문 앞 빈 줄 뒤 'PASS:' → 통과" t_pass b_B10 $'\n'"PASS: 요약"
run_test "REVIEW-GATE-B11 정규식 특수문자, 정상 상태 → 통과" t_pass b_B11 "확인함"
run_test "REVIEW-GATE-B11 정규식 특수문자, 상태 '문제' → 실패" t_fail "A 보안" b_B11 "문제"

t_b12() {  # t_b12 <F 상태> pass|fail
  rm -f "$TMPD/pwned"
  b_B12 "$1"; check APPROVED
  if [ "$2" == pass ]; then expect_pass; else expect_fail "F 결정"; fi
  [ -e "$TMPD/pwned" ] && fail "본문의 \$(...)·백틱 명령이 실행됨"
}
run_test "REVIEW-GATE-B12 '-' 줄·백슬래시·\$(...) 실행 없음, 통과" t_b12 "확인함" pass
run_test "REVIEW-GATE-B12 '-' 줄·백슬래시·\$(...) 실행 없음, 실패" t_b12 "문제" fail

run_test "REVIEW-GATE-B13 근거 없음" t_fail "A 보안" b_B13 "A 보안: 확인함"
run_test "REVIEW-GATE-B13 짧은 대시" t_fail "A 보안" b_B13 "A 보안: 확인함 - x"
run_test "REVIEW-GATE-B13 대시 뒤 근거 비움" t_fail "A 보안" b_B13 "A 보안: 확인함 — "
run_test "REVIEW-GATE-B13 근거가 공백뿐" t_fail "A 보안" b_B13 "A 보안: 확인함 —    "
run_test "REVIEW-GATE-B13 en dash '–'" t_fail "A 보안" b_B13 "A 보안: 확인함 – x"
run_test "REVIEW-GATE-B13 '--'" t_fail "A 보안" b_B13 "A 보안: 확인함 -- x"
run_test "REVIEW-GATE-B13 대시 앞뒤 붙여 씀 '확인함—x'" t_fail "A 보안" b_B13 "A 보안: 확인함—x"
run_test "REVIEW-GATE-B13 대시 뒤 붙여 씀 '확인함 —x'" t_fail "A 보안" b_B13 "A 보안: 확인함 —x"
run_test "REVIEW-GATE-B13 CRLF, 대시 뒤 근거 비움" t_fail "A 보안" b_B13_crlf

# ---------- 단위: 권한 ----------
# S3: 출력을 \r·\n으로 나눈 어느 줄도 본문에서 온 명령(INJECT)으로 시작하지 않는다.
#     '::' 줄은 스크립트 자신의 '::error::'뿐이어야 한다
no_injected_command() {
  local line out=${OUT//$'\r'/$'\n'}
  while IFS= read -r line; do
    case $line in
      '::error::INJECT'*|'::error:: INJECT'*) fail "본문의 명령이 줄 머리로 나옴: $line" ;;
      '::error::'*) ;;
      '::'*) fail "본문의 명령이 줄 머리로 나옴: $line" ;;
    esac
  done <<< "$out"
}
t_s3() {  # t_s3 <A 상태> pass|fail
  b_S3 "$1"; check APPROVED
  if [ "$2" == pass ]; then expect_pass; else expect_fail "A 보안"; fi
  no_injected_command
}
run_test "REVIEW-GATE-S3 워크플로 명령 흉내, 통과 본문" t_s3 "확인함" pass
run_test "REVIEW-GATE-S3 워크플로 명령 흉내, 실패 본문(줄을 메시지에 옮길 때)" t_s3 "문제" fail

# ---------- 단위: 회귀 R1 ----------
# 분리 전 "판정 확인" 단계 인라인 코드(claude-code-review.yml, 커밋 42a1c74)를 그대로 옮긴 기준.
# 일부러 바꾸는 동작은 비교 대상에서 뺀다: B2 전부(줄 전체 굵게, 기호 뒤 공백 없는 '-'·'*' 포함),
# B4, B10 전부(새 입력 포함), B13 전부(새 입력 포함).
inline_reference() (
  state=$1; body=$(cat)
  case "$state" in
    APPROVED)
      for item in "A 보안:" "B 정확성:" "C TDD:" "D 요구사항:" "E 문서:" "F 결정:"; do
        lines=$(grep -E "^[[:space:]*-]*${item}" <<< "$body" || true)
        count=$(grep -c . <<< "$lines" || true)
        if [ -z "$lines" ] || [ "$count" -ne 1 ]; then exit 1; fi
        if ! grep -qE "^[[:space:]*-]*${item}[[:space:]]*(확인함|해당 없음)" <<< "$lines"; then exit 1; fi
      done
      echo "PASS" ;;
    *) exit 1 ;;
  esac
)
t_r1() {  # t_r1 STATE <빌더> [인자...]
  local state=$1 old; shift
  rm -f "$TMPD/pwned"
  "$@"; check "$state"
  printf '%s' "$BODY" | inline_reference "$state" >/dev/null 2>&1; old=$?
  assert_eq "$CODE" "$old" "종료 코드(인라인 코드와 같아야 함)"
}
R1_CORPUS=(
  "b_N1" "b_N2" "b_N3"
  "b_E1 0" "b_E1 1" "b_E1 2" "b_E1 3" "b_E1 4" "b_E1 5"
  "b_E2" "b_E3" "b_E4 0" "b_E4 5" "b_E6 OK" "b_E6 확인" "b_E7"
  "b_E11" "b_E12" "b_E13" "b_E14"
  "b_B3 A 보안:확인함 — x"
  "b_B5" "b_B6" "b_B7" "b_B8" "b_B11 확인함" "b_B11 문제" "b_B12 확인함" "b_B12 문제"
  "b_S3 확인함" "b_S3 문제"
)
for entry in "${R1_CORPUS[@]}"; do
  builder=${entry%% *}; arg=${entry#"$builder"}; arg=${arg# }
  if [ -n "$arg" ]; then
    run_test "REVIEW-GATE-R1 인라인과 같은 판정 ($entry)" t_r1 APPROVED "$builder" "$arg"
  else
    run_test "REVIEW-GATE-R1 인라인과 같은 판정 ($entry)" t_r1 APPROVED "$builder"
  fi
done
for st in CHANGES_REQUESTED NONE COMMENTED "" approved; do
  run_test "REVIEW-GATE-R1 인라인과 같은 판정 (상태 '$st', N1 본문)" t_r1 "$st" b_N1
done

# ---------- 리뷰 선택 단위 (jq 필요) ----------
SHA=aaaa1111
OTHER_SHA=bbbb2222

need_jq() {
  command -v jq >/dev/null 2>&1 && return 0
  fail "jq 없음 (이 테스트는 jq가 필요하다. 규칙상 skip하지 않고 실패로 센다)"
  return 1
}
rv() {  # rv ID LOGIN STATE COMMIT [BODY]
  printf '{"id":%s,"user":{"login":"%s"},"state":"%s","commit_id":"%s","body":"%s"}' "$1" "$2" "$3" "$4" "${5:-본문 $1}"
}
# select_reviews "<페이지1 배열>" ["<페이지2 배열>"...]  → OUT = 고른 결과를 {id,state,body}로 줄인 것(없으면 null)
select_reviews() {
  local pages; pages=$(printf '%s\n' "$@")
  OUT=$(printf '%s' "$pages" | jq -s --arg sha "$SHA" -f "$SELECT" 2>&1 | jq -c 'if . == null then null else {id,state,body} end' 2>&1)
}
C='claude[bot]'

t_s2() { need_jq || return  # t_s2 <로그인>...  — 각 로그인의 APPROVED만 있음
  local login reviews="" id=0
  for login in "$@"; do id=$((id + 1)); reviews+="${reviews:+,}$(rv $id "$login" APPROVED $SHA)"; done
  select_reviews "[$reviews]"
  assert_eq "$OUT" "null" "선택 결과"; }
t_f1() { need_jq || return
  select_reviews "[$(rv 1 "$C" APPROVED $SHA),$(rv 2 "$C" CHANGES_REQUESTED $SHA)]"
  assert_eq "$OUT" '{"id":2,"state":"CHANGES_REQUESTED","body":"본문 2"}' "선택 결과"; }
t_f2() { need_jq || return
  select_reviews "[$(rv 1 "$C" CHANGES_REQUESTED $SHA),$(rv 2 "$C" APPROVED $SHA 'PASS: 새 승인')]"
  assert_eq "$OUT" '{"id":2,"state":"APPROVED","body":"PASS: 새 승인"}' "선택 결과"; }
t_f3() { need_jq || return
  select_reviews "[$(rv 1 "$C" APPROVED $OTHER_SHA)]"
  assert_eq "$OUT" "null" "선택 결과"; }
t_block_then_other() { need_jq || return  # t_block_then_other <로그인> <커밋>  — Claude BLOCK 뒤 다른 리뷰의 APPROVED
  select_reviews "[$(rv 1 "$C" CHANGES_REQUESTED $SHA),$(rv 2 "$1" APPROVED "$2")]"
  assert_eq "$OUT" '{"id":1,"state":"CHANGES_REQUESTED","body":"본문 1"}' "선택 결과"; }
t_f4_only() { need_jq || return
  select_reviews "[$(rv 1 "$C" COMMENTED $SHA),$(rv 2 "$C" DISMISSED $SHA)]"
  assert_eq "$OUT" "null" "선택 결과"; }
t_f4_after() { need_jq || return
  select_reviews "[$(rv 1 "$C" APPROVED $SHA),$(rv 2 "$C" COMMENTED $SHA),$(rv 3 "$C" DISMISSED $SHA)]"
  assert_eq "$OUT" '{"id":1,"state":"APPROVED","body":"본문 1"}' "선택 결과"; }
t_f5_last_page() { need_jq || return
  select_reviews "[$(rv 1 "$C" APPROVED $SHA)]" "[$(rv 2 alice COMMENTED $SHA),$(rv 3 "$C" CHANGES_REQUESTED $SHA)]"
  assert_eq "$OUT" '{"id":3,"state":"CHANGES_REQUESTED","body":"본문 3"}' "선택 결과"; }
t_f5_first_page() { need_jq || return
  select_reviews "[$(rv 1 "$C" APPROVED $SHA)]" "[$(rv 2 alice COMMENTED $SHA)]"
  assert_eq "$OUT" '{"id":1,"state":"APPROVED","body":"본문 1"}' "선택 결과"; }
t_f8() { need_jq || return
  select_reviews "[$(rv 1 "$C" APPROVED $SHA 'PASS: 첫 제출'),$(rv 2 "$C" APPROVED $SHA 'PASS: 두 번째 제출')]"
  assert_eq "$OUT" '{"id":2,"state":"APPROVED","body":"PASS: 두 번째 제출"}' "선택 결과"; }

run_test "REVIEW-GATE-S2 다른 봇·사람의 APPROVED만 → null" t_s2 'coderabbitai[bot]' alice
run_test "REVIEW-GATE-S2 사람 'claude'의 APPROVED만 → null" t_s2 claude
run_test "REVIEW-GATE-S2 'claude-impostor[bot]'의 APPROVED만 → null" t_s2 'claude-impostor[bot]'
run_test "REVIEW-GATE-F1 APPROVED → CHANGES_REQUESTED, 마지막 것" t_f1
run_test "REVIEW-GATE-F2 CHANGES_REQUESTED → APPROVED, 마지막 APPROVED와 본문" t_f2
run_test "REVIEW-GATE-F3 다른 커밋의 APPROVED만 → null" t_f3
run_test "REVIEW-GATE-S2 Claude BLOCK 뒤 다른 작성자 APPROVED는 무시" t_block_then_other alice "$SHA"
run_test "REVIEW-GATE-F3 현재 커밋 BLOCK 뒤 다른 커밋 APPROVED는 무시" t_block_then_other "$C" "$OTHER_SHA"
run_test "REVIEW-GATE-F4 COMMENTED·DISMISSED만 → null" t_f4_only
run_test "REVIEW-GATE-F4 APPROVED 뒤 COMMENTED·DISMISSED는 무시" t_f4_after
run_test "REVIEW-GATE-F5 2페이지, 마지막 판정이 2페이지" t_f5_last_page
run_test "REVIEW-GATE-F5 2페이지, 2페이지에 판정 없음 → 1페이지 것" t_f5_first_page
run_test "REVIEW-GATE-F8 같은 커밋에 두 번 제출, 마지막 것" t_f8

# ---------- 가짜 gh (jq 필요) ----------
# make_fake_gh "<gh가 할 일(bash)>"  — 받은 인자를 gh-args에 남긴다
make_fake_gh() {
  mkdir -p "$TMPD/bin"; rm -f "$TMPD/gh-args"
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" > "%s/gh-args"\n%s\n' "$TMPD" "$1" > "$TMPD/bin/gh"
  chmod +x "$TMPD/bin/gh"
}
fetch() { OUT=$(PATH="$TMPD/bin:$PATH" bash "$FETCH" o/r 7 "$SHA" 2>"$TMPD/fetch-err"); CODE=$?; }
PAGE1="[$(rv 1 "$C" APPROVED $SHA 'PASS: 옛 승인')]"
PAGE2="[$(rv 2 "$C" CHANGES_REQUESTED $SHA 'BLOCK: 새 판정')]"

t_fetch_ok() { need_jq || return
  make_fake_gh "printf '%s\n%s\n' '$PAGE1' '$PAGE2'"
  fetch
  assert_eq "$CODE" 0 "종료 코드"
  assert_eq "$(printf '%s' "$OUT" | jq -c '{id,state}' 2>&1)" '{"id":2,"state":"CHANGES_REQUESTED"}' "출력"
  assert_eq "$(cat "$TMPD/gh-args" 2>/dev/null)" "api repos/o/r/pulls/7/reviews --paginate" "gh 인자"
}
t_f6() { need_jq || return
  make_fake_gh "printf '%s\n' '$PAGE1'; exit 1"
  fetch
  [ "$CODE" -ne 0 ] || fail "종료 코드: 기대 [0이 아님], 실제 [$CODE], 출력 [$OUT]"
}
t_f7() { need_jq || return
  make_fake_gh "echo '<html>rate limited</html>'"
  fetch
  assert_eq "$CODE" 1 "종료 코드"
}
run_test "REVIEW-GATE-F6 대조: 모든 페이지 성공 → 마지막 판정 출력, gh 인자" t_fetch_ok
run_test "REVIEW-GATE-F6 1페이지 출력 뒤 gh 실패 → 0이 아닌 종료" t_f6
run_test "REVIEW-GATE-F7 gh가 JSON 아닌 값 출력 → 종료 코드 1" t_f7

# =====================================================================
# REVIEW-GATE-G 잠금 파일 경비 검사 (docs/cases.md "## REVIEW-GATE-G 잠금 파일 경비 검사", 2026-11-19 승인)
#
# 대상 1: .github/scripts/locked-files-guard.sh (Q7)
#   입력(환경 변수): REPO(예: o/r), PR(예: 7), EXPECTED_HEAD_SHA(이벤트의 PR head.sha)
#   gh 호출(이 순서, gh api만 씀. --jq·--slurp는 써도 되고 가짜 gh가 흉내 낸다):
#     1) gh api repos/$REPO/pulls/$PR                          → changed_files, head.sha
#     2) gh api repos/$REPO/pulls/$PR/files?per_page=100 --paginate → [{filename, status, previous_filename?}, ...] 쪽마다 배열
#     3) gh api repos/$REPO/pulls/$PR                          → head.sha 다시 확인 (G25)
#   판정:
#     - 잠금 파일(Q4, 루트 기준·대소문자 무시): '.github/'·'.coderabbit/'로 시작, 또는 '.coderabbit.yaml'·'.coderabbit.yml'·'.coderabbit.config.ts'와 같음.
#       filename과 previous_filename 둘 다 본다(G05). status(removed·changed·binary 등)와 상관없이 이름으로만 판정(G06·G07).
#     - 잠금 파일 없음 → 종료 0, '::error::' 줄 없음
#     - 잠금 파일 있음 → 종료 1, 걸린 경로마다 그 경로(PR에 적힌 대소문자 그대로)와 "잠긴 파일"을 담은 '::error::' 줄 (Q13)
#     - 읽은 파일 수 != changed_files → 종료 1, '::error::…파일 목록을 끝까지 읽지 못함' (G10)
#     - 파일 목록 gh 호출 실패 → 종료 1, '::error::…파일 목록 조회 실패' (G20). PR 정보 호출 실패 → 종료 1, '조회 실패'를 담은 '::error::'
#     - 1)이나 3)의 head.sha != EXPECTED_HEAD_SHA → 종료 1, '::error::…새 커밋이 올라와 다시 검사 필요' (G25)
# 대상 2: .github/workflows/locked-files-guard.yml (정적 검사: 파일 글자를 읽어 확인. 실제 실행은 시험 PR 몫)
#   on: pull_request_target만, types 5종, branches [dev], paths 거름 없음 · permissions 블록은 모두 contents/pull-requests read 둘뿐
#   · secrets 안 씀 · checkout은 base.sha + persist-credentials: false · run: 안에 '${{' 없음, env로 REPO·PR·EXPECTED_HEAD_SHA·GH_TOKEN(github.token)
#   · 'if:' 없음 · 워크플로 이름 "Locked Files Guard", 작업 하나 'locked-files' · concurrency group locked-files-<PR 번호>, cancel-in-progress: true
#   · timeout-minutes: 5
#
# 자동 테스트 대상이 아닌 케이스:
#   시험 PR: REVIEW-GATE-G01(실제 병합 흐름. 아래 정상 단위 테스트는 경비 판정만), G02, G13, G14, G15, G24(동시 취소. 설정 글자만 정적 검사), G27
#   설정 GET·명령 확인: G19, G29, G30
#   문서·기록: G18, G22, G26, G28, G31
#   일부만 정적 검사(실제 동작은 검증 불가): G16(fork 시험), G21(실제 시간 초과), G23(이벤트별 실제 실행은 시험 PR)
# =====================================================================
GUARD="$DIR/locked-files-guard.sh"
WF="$DIR/../workflows/locked-files-guard.yml"
GD="$TMPD/guard"   # 가짜 gh 실행 파일
GS="$TMPD/guard-state"  # 가짜 gh가 돌려줄 응답과 호출 기록

# 가짜 gh: GS의 pr-<n>(n번째 PR 정보 호출, 없으면 앞 번호 것), page-<n>(파일 목록 쪽)을 돌려준다.
#   pr-fail 있으면 PR 정보 호출 실패. files-fail-after=k 이면 k쪽을 내보낸 뒤 files-fail-msg로 실패.
#   --paginate 없으면 첫 쪽만 준다. 준비 안 된 호출(커밋 목록 등)은 실패. 호출마다 "<엔드포인트> pag=<0|1>"을 calls에 남긴다.
mkdir -p "$GD"
cat > "$GD/gh" <<'FAKE_GH'
#!/usr/bin/env bash
D=$GUARD_FAKE_DIR
ep=""; jqf=""; pag=0; slurp=0
while [ $# -gt 0 ]; do
  case $1 in
    api) ;;
    --paginate) pag=1 ;;
    --slurp) slurp=1 ;;
    --jq|-q) jqf=$2; shift ;;
    -H|--header|-X|--method|-f|-F|--field|--raw-field|-t|--template|--hostname|--cache|-p|--preview) shift ;;
    -*) ;;
    *) [ -z "$ep" ] && ep=${1#/} ;;
  esac
  shift
done
echo "$ep pag=$pag" >> "$D/calls"
out() { if [ -n "$jqf" ]; then jq -r "$jqf"; else cat; fi; }
case ${ep%%\?*} in
  repos/o/r/pulls/7)
    n=$(( $(cat "$D/pr-n" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$D/pr-n"
    if [ -e "$D/pr-fail" ]; then cat "$D/pr-fail" >&2; exit 1; fi
    while [ "$n" -gt 1 ] && [ ! -e "$D/pr-$n" ]; do n=$((n - 1)); done
    out < "$D/pr-$n" ;;
  repos/o/r/pulls/7/files)
    k=0; all=""
    for p in "$D"/page-*; do
      [ -e "$p" ] || continue
      if [ -e "$D/files-fail-after" ] && [ "$k" -ge "$(cat "$D/files-fail-after")" ]; then cat "$D/files-fail-msg" >&2; exit 1; fi
      k=$((k + 1))
      if [ "$slurp" = 1 ]; then all+="${all:+,}$(cat "$p")"; else out < "$p"; fi
      [ "$pag" = 1 ] || break
    done
    [ "$slurp" = 1 ] && printf '[%s]\n' "$all" | out
    exit 0 ;;
  *) echo "가짜 gh: 준비 안 된 호출 $ep" >&2; exit 1 ;;
esac
FAKE_GH
chmod +x "$GD/gh"

PATCH='"patch":"@@ -1 +1 @@\n-a\n+b"'
fe() {  # fe <파일 이름> [상태] [옛 이름]  → 파일 목록 항목 하나 (글자 파일이라 patch 있음)
  if [ -n "${3:-}" ]; then printf '{"filename":"%s","status":"%s","previous_filename":"%s",%s}' "$1" "${2:-modified}" "$3" "$PATCH"
  else printf '{"filename":"%s","status":"%s",%s}' "$1" "${2:-modified}" "$PATCH"; fi
}
pr_meta() { printf '{"number":7,"changed_files":%s,"head":{"sha":"%s"}}' "$1" "$2"; }  # pr_meta <changed_files> <head.sha>
many() {  # many <시작> <끝> [잠긴 파일 번호]  → MANY = 쉼표로 이은 항목들 (src/f<번호>.txt, 잠긴 번호는 .github/workflows/late.yml)
  local i; MANY=""
  for ((i = $1; i <= $2; i++)); do
    if [ "$i" == "${3:-}" ]; then MANY+="${MANY:+,}{\"filename\":\".github/workflows/late.yml\",\"status\":\"added\",$PATCH}"
    else MANY+="${MANY:+,}{\"filename\":\"src/f$i.txt\",\"status\":\"added\",$PATCH}"; fi
  done
}
# guard_fixture <changed_files> <쪽 배열>...  — PR 정보(head.sha = SHA)와 파일 목록 쪽을 준비
guard_fixture() {
  rm -rf "$GS"; mkdir -p "$GS"
  pr_meta "$1" "$SHA" > "$GS/pr-1"; shift
  local i=0 p
  for p in "$@"; do i=$((i + 1)); printf '%s\n' "$p" > "$GS/page-$i"; done
}
guard() { OUT=$(GUARD_FAKE_DIR="$GS" REPO=o/r PR=7 EXPECTED_HEAD_SHA="$SHA" PATH="$GD:$PATH" bash "$GUARD" 2>&1); CODE=$?; }

error_lines() { grep '^::error::' <<< "$OUT"; }
error_line_has() {  # error_line_has <글자>...  — 주어진 글자를 모두 담은 '::error::' 줄이 하나라도 있어야 함
  local line s ok errs; errs=$(error_lines)
  if [ -n "$errs" ]; then
    while IFS= read -r line; do
      ok=1; for s in "$@"; do [[ $line == *"$s"* ]] || ok=0; done
      [ $ok -eq 1 ] && return 0
    done <<< "$errs"
  fi
  fail "[$*]를 담은 '::error::' 줄이 없음. 출력: $OUT"
}
expect_guard_pass() {
  assert_eq "$CODE" 0 "종료 코드"
  local errs; errs=$(error_lines)
  [ -z "$errs" ] || fail "통과여야 하는데 '::error::' 줄이 나옴: $errs"
}
expect_guard_fail() { assert_eq "$CODE" 1 "종료 코드"; error_line_has "$@"; }  # expect_guard_fail <글자>...
expect_locked() {  # expect_locked <걸려야 할 경로>...
  assert_eq "$CODE" 1 "종료 코드"
  local p; for p in "$@"; do error_line_has "잠긴 파일" "$p"; done
}

# ---------- 경비 단위: 정상 ----------
t_g_pass() { need_jq || return
  guard_fixture 2 "[$(fe docs/guide.md),$(fe src/main/java/App.java)]"
  guard; expect_guard_pass
  assert_eq "$(cat "$GS/calls" 2>/dev/null)" \
    $'repos/o/r/pulls/7 pag=0\nrepos/o/r/pulls/7/files?per_page=100 pag=1\nrepos/o/r/pulls/7 pag=0' \
    "gh 호출 순서(PR 정보 → 파일 목록 --paginate → PR 정보)"
}
run_test "REVIEW-GATE-G01 docs/·src/만 바뀜 → 통과, gh 호출 순서" t_g_pass

# ---------- 경비 단위: 잠긴 경로 (G03) ----------
t_g_locked() { need_jq || return  # t_g_locked <잠긴 경로>  — 잠금 아닌 파일 하나와 함께
  guard_fixture 2 "[$(fe docs/readme.md),$(fe "$1")]"
  guard; expect_locked "$1"
  [[ $(error_lines) == *docs/readme.md* ]] && fail "잠금 아닌 docs/readme.md가 '::error::'에 나옴"
}
for p in .github/workflows/x.yml .github/scripts/check-claude-verdict.sh .github/CODEOWNERS \
         .coderabbit.yaml .coderabbit.yml .coderabbit.config.ts .coderabbit/foo.md; do
  run_test "REVIEW-GATE-G03 잠긴 경로 '$p' → 실패, 경로 출력" t_g_locked "$p"
done

# ---------- 경비 단위: 비슷한 이름 (G04) ----------
t_g_lookalike() { need_jq || return
  guard_fixture 1 "[$(fe "$1")]"
  guard; expect_guard_pass
}
for p in docs/.github/x.md .githubx/a .github-notes.md .coderabbit.yaml.bak .coderabbitignore src/.coderabbit.yaml; do
  run_test "REVIEW-GATE-G04 비슷한 이름 '$p' → 통과" t_g_lookalike "$p"
done

# ---------- 경비 단위: 이름 바꾸기·삭제·권한 비트·바이너리·대소문자 ----------
t_g_rename() { need_jq || return  # t_g_rename <새 이름> <옛 이름> <걸려야 할 경로>
  guard_fixture 1 "[$(fe "$1" renamed "$2")]"
  guard; expect_locked "$3"
}
run_test "REVIEW-GATE-G05 이름 바꾸기 잠금→밖 → 옛 이름으로 실패" t_g_rename docs/a.sh .github/scripts/a.sh .github/scripts/a.sh
run_test "REVIEW-GATE-G05 이름 바꾸기 밖→잠금 → 새 이름으로 실패" t_g_rename .github/workflows/a.yml docs/a.yml .github/workflows/a.yml

t_g_raw() { need_jq || return  # t_g_raw <항목 JSON> <걸려야 할 경로>
  guard_fixture 1 "[$1]"
  guard; expect_locked "$2"
}
run_test "REVIEW-GATE-G06 잠긴 파일 삭제(removed) → 실패" t_g_raw \
  '{"filename":".github/workflows/old.yml","status":"removed","additions":0,"deletions":3,"changes":3}' .github/workflows/old.yml
run_test "REVIEW-GATE-G07 권한 비트만 바뀜(changed, patch 없음) → 실패" t_g_raw \
  '{"filename":".github/scripts/run.sh","status":"changed","additions":0,"deletions":0,"changes":0}' .github/scripts/run.sh
run_test "REVIEW-GATE-G07 바이너리(patch 없음) → 실패" t_g_raw \
  '{"filename":".github/assets/logo.png","status":"modified","additions":0,"deletions":0,"changes":0}' .github/assets/logo.png

for p in .GitHub/workflows/x.yml .CodeRabbit.yaml .CODERABBIT/notes.md; do
  run_test "REVIEW-GATE-G08 대소문자만 다름 '$p' → 실패" t_g_locked "$p"
done

# ---------- 경비 단위: 쪽 나눔·개수 ----------
t_g9_locked() { need_jq || return
  local p1 p2
  many 1 100; p1="[$MANY]"; many 101 150 120; p2="[$MANY]"
  guard_fixture 150 "$p1" "$p2"
  guard; expect_locked .github/workflows/late.yml
}
t_g9_clean() { need_jq || return  # 대조: 두 쪽 합이 changed_files와 같으면 통과 (1쪽만 세면 G10으로 잘못 실패)
  local p1 p2
  many 1 100; p1="[$MANY]"; many 101 150; p2="[$MANY]"
  guard_fixture 150 "$p1" "$p2"
  guard; expect_guard_pass
}
run_test "REVIEW-GATE-G09 파일 150개, 잠긴 파일이 2쪽(120번째) → 실패" t_g9_locked
run_test "REVIEW-GATE-G09 대조: 파일 150개, 잠금 없음 → 통과" t_g9_clean

t_g10() { need_jq || return  # t_g10 <changed_files>  — 목록은 3개
  guard_fixture "$1" "[$(fe docs/a.md),$(fe docs/b.md),$(fe docs/c.md)]"
  guard; expect_guard_fail "파일 목록을 끝까지 읽지 못함"
}
run_test "REVIEW-GATE-G10 changed_files 5, 읽은 것 3 → 실패" t_g10 5
run_test "REVIEW-GATE-G10 changed_files 2, 읽은 것 3 → 실패" t_g10 2

t_g11() { need_jq || return
  guard_fixture 0 "[]"
  guard; expect_guard_pass
}
run_test "REVIEW-GATE-G11 바뀐 파일 0개 → 통과" t_g11

t_g12() { need_jq || return  # 커밋1이 .github/를 고치고 커밋2가 되돌림: API 목록(최종 차이)에는 docs만 남음
  guard_fixture 1 "[$(fe docs/a.md)]"
  guard; expect_guard_pass
  grep -q commits "$GS/calls" 2>/dev/null && fail "커밋 목록을 읽음(최종 차이만 봐야 함): $(cat "$GS/calls")"
}
run_test "REVIEW-GATE-G12 되돌린 커밋, 최종 차이에 잠금 없음 → 통과" t_g12

# ---------- 경비 단위: 조회 실패 (G20) ----------
t_g20_files() { need_jq || return  # t_g20_files <몇 쪽 낸 뒤 실패> <gh 오류 글>  — 잠금 없는 파일 3개, 2쪽
  guard_fixture 3 "[$(fe docs/a.md),$(fe docs/b.md)]" "[$(fe docs/c.md)]"
  echo "$1" > "$GS/files-fail-after"; echo "$2" > "$GS/files-fail-msg"
  guard; expect_guard_fail "파일 목록 조회 실패"
}
run_test "REVIEW-GATE-G20 1쪽 낸 뒤 5xx → 실패" t_g20_files 1 "HTTP 502: Bad Gateway"
run_test "REVIEW-GATE-G20 403 → 실패" t_g20_files 0 "HTTP 403: Forbidden"
run_test "REVIEW-GATE-G20 429 → 실패" t_g20_files 0 "HTTP 429: Too Many Requests"
run_test "REVIEW-GATE-G20 네트워크 오류 → 실패" t_g20_files 0 "error connecting to api.github.com"
t_g20_html() { need_jq || return
  guard_fixture 1 '<html>rate limited</html>'
  guard; expect_guard_fail
}
run_test "REVIEW-GATE-G20 목록이 JSON 아님(종료 0) → 실패" t_g20_html
t_g20_pr() { need_jq || return
  guard_fixture 1 "[$(fe docs/a.md)]"
  echo "HTTP 500: Internal Server Error" > "$GS/pr-fail"
  guard; expect_guard_fail "조회 실패"
}
run_test "REVIEW-GATE-G20 PR 정보 조회 실패 → 실패" t_g20_pr

# ---------- 경비 단위: 새 커밋 (G25) ----------
t_g25() { need_jq || return  # t_g25 <다른 SHA를 줄 PR 정보 호출 번호 1|2>
  guard_fixture 1 "[$(fe docs/a.md)]"
  [ "$1" == 2 ] && pr_meta 1 "$SHA" > "$GS/pr-1"
  pr_meta 1 "$OTHER_SHA" > "$GS/pr-$1"
  guard; expect_guard_fail "새 커밋이 올라와 다시 검사 필요"
}
run_test "REVIEW-GATE-G25 목록 읽기 전 head.sha 다름 → 실패" t_g25 1
run_test "REVIEW-GATE-G25 목록 읽은 뒤 head.sha 다름 → 실패" t_g25 2

# ---------- 경비 워크플로 정적 검사 ----------
need_wf() { [ -f "$WF" ] && return 0; fail "워크플로 파일 없음: .github/workflows/locked-files-guard.yml"; return 1; }
load_wf() { WFT=$(tr -d '\r' < "$WF" | sed -E '/^[[:space:]]*#/d; s/[[:space:]]+#.*$//'); }  # 주석 뺀 글자 → WFT
ex() { printf '\\$\\{\\{ *%s *\\}\\}' "${1//./\\.}"; }  # ex github.repository → '${{ github.repository }}'에 맞는 정규식
# sub_block <정규식>  — 표준 입력에서 정규식에 맞는 첫 줄 아래, 그 줄보다 깊게 들여쓴 줄들
sub_block() { awk -v re="$1" 'started { if ($0 ~ /^[[:space:]]*$/) next; match($0, /^ */); if (RLENGTH <= ind) exit; print; next }
  $0 ~ re { match($0, /^ */); ind = RLENGTH; started = 1 }'; }
block_at() { awk -v n="$1" 'NR == n { match($0, /^ */); ind = RLENGTH; next }
  NR > n { if ($0 ~ /^[[:space:]]*$/) next; match($0, /^ */); if (RLENGTH <= ind) exit; print }' <<< "$WFT"; }
# top_keys  — 표준 입력 블록에서 가장 얕게 들여쓴 키 이름들 (정렬, 공백으로 이음)
top_keys() { awk '{ match($0, /^ */); d[NR] = RLENGTH; l[NR] = $0; if (min == "" || RLENGTH < min) min = RLENGTH }
  END { for (i = 1; i <= NR; i++) if (d[i] == min && l[i] !~ /^[[:space:]]*- /) { k = l[i]; sub(/^ */, "", k); sub(/:.*/, "", k); print k } }' |
  LC_ALL=C sort | tr '\n' ' '; }
# yaml_list <키>  — 표준 입력에서 키의 목록 값([a, b] 또는 '- a' 줄들). 정렬, 공백으로 이음
yaml_list() { awk -v key="$1" '
  started { if ($0 ~ /^[[:space:]]*- /) { v = $0; sub(/^[[:space:]]*- */, "", v); print v; next } exit }
  $0 ~ "^[[:space:]]*" key ":" { v = $0; sub("^[[:space:]]*" key ":[[:space:]]*", "", v)
    if (v ~ /^\[/) { gsub(/[][ ]/, "", v); n = split(v, a, ","); for (i = 1; i <= n; i++) if (a[i] != "") print a[i]; exit }
    if (v != "") { print v; exit }
    started = 1 }' | tr -d "\"'" | LC_ALL=C sort | tr '\n' ' '; }
# run_blocks  — 모든 run: 값(한 줄 값과 | · > 아래 줄들)
run_blocks() { awk '
  started { if ($0 ~ /^[[:space:]]*$/) next; match($0, /^ */); if (RLENGTH <= ind) started = 0; else { print; next } }
  /^[[:space:]]*(- )?run:/ { match($0, /^ */); ind = RLENGTH; v = $0; sub(/^[[:space:]]*(- )?run:[[:space:]]*/, "", v)
    if (v ~ /^[|>]/) started = 1; else print v }' <<< "$WFT"; }
count_re() { grep -cE "$1" <<< "$WFT"; }

t_wf_trigger() { need_wf || return; load_wf
  local n on pt
  n=$(grep -nE "^(on|\"on\"|'on'):[[:space:]]*$" <<< "$WFT" | head -1 | cut -d: -f1)
  [ -n "$n" ] || { fail "'on:' 블록 없음"; return; }
  on=$(block_at "$n")
  assert_eq "$(top_keys <<< "$on")" "pull_request_target " "이벤트 종류(pull_request_target만)"
  pt=$(sub_block '^[[:space:]]*pull_request_target:' <<< "$on")
  assert_eq "$(top_keys <<< "$pt")" "branches types " "pull_request_target 아래 키(paths 거름 없음)"
  assert_eq "$(yaml_list types <<< "$pt")" "edited opened ready_for_review reopened synchronize " "types"
  assert_eq "$(yaml_list branches <<< "$pt")" "dev " "branches"
}
t_wf_permissions() { need_wf || return; load_wf
  local n blk found=0
  [ "$(count_re '^[[:space:]]*permissions:[[:space:]]*[^[:space:]]')" -eq 0 ] || fail "permissions를 한 줄 값(write-all·{} 등)으로 씀"
  for n in $(grep -nE '^[[:space:]]*permissions:[[:space:]]*$' <<< "$WFT" | cut -d: -f1); do
    found=1
    blk=$(block_at "$n" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/:[[:space:]]+/: /' | LC_ALL=C sort)
    assert_eq "$blk" $'contents: read\npull-requests: read' "permissions 블록 내용"
  done
  [ $found -eq 1 ] || fail "permissions 블록 없음 (없으면 저장소 기본 권한이 쓰임)"
}
t_wf_no_secrets() { need_wf || return; load_wf
  local hit; hit=$(grep -nE 'secrets(\.|:|\[)' <<< "$WFT")
  [ -z "$hit" ] || fail "비밀 값을 씀: $hit"
}
t_wf_checkout() { need_wf || return; load_wf
  local nco hit
  nco=$(count_re 'uses:[[:space:]]*actions/checkout@')
  [ "$nco" -ge 1 ] || fail "기준 커밋 checkout 없음 (Q7: 판정 스크립트를 기준 커밋에서 실행)"
  assert_eq "$(count_re "^[[:space:]]*ref:[[:space:]]*$(ex github.event.pull_request.base.sha)[[:space:]]*$")" "$nco" "base.sha ref 수(checkout마다)"
  assert_eq "$(count_re '^[[:space:]]*ref:')" "$nco" "ref: 줄 수(base.sha 말고 다른 ref 없음)"
  assert_eq "$(count_re '^[[:space:]]*persist-credentials:[[:space:]]*false[[:space:]]*$')" "$nco" "persist-credentials: false 수"
  hit=$(grep -nE 'head\.sha' <<< "$WFT" | grep -vE "^[0-9]+:[[:space:]]*EXPECTED_HEAD_SHA:[[:space:]]*$(ex github.event.pull_request.head.sha)[[:space:]]*$")
  [ -z "$hit" ] || fail "head.sha를 EXPECTED_HEAD_SHA 말고 다른 곳에 씀: $hit"
  hit=$(grep -nE 'head\.ref|head_ref|head\.repo|refs/pull/|merge_commit_sha' <<< "$WFT")
  [ -z "$hit" ] || fail "PR 쪽 코드를 가리키는 값을 씀: $hit"
}
t_wf_run_no_expr() { need_wf || return; load_wf
  local rb hit; rb=$(run_blocks)
  [ -n "$rb" ] || fail "run: 단계 없음"
  hit=$(grep -n '\${{' <<< "$rb")
  [ -z "$hit" ] || fail "run: 안에 '\${{ }}'가 있음(env로 넘겨야 함): $hit"
  hit=$(grep -nE 'pull_request\.(title|body)|head\.label' <<< "$WFT")
  [ -z "$hit" ] || fail "PR 제목·본문·브랜치 표시를 씀: $hit"
}
t_wf_env() { need_wf || return; load_wf
  local k v
  for k in "EXPECTED_HEAD_SHA github.event.pull_request.head.sha" "PR github.event.pull_request.number" \
           "REPO github.repository" "GH_TOKEN github.token"; do
    v=${k#* }; k=${k%% *}
    [ "$(count_re "^[[:space:]]*$k:[[:space:]]*$(ex "$v")[[:space:]]*$")" -ge 1 ] || fail "env '$k: \${{ $v }}' 없음"
  done
  [[ $(run_blocks) == *.github/scripts/locked-files-guard.sh* ]] || fail "run:이 .github/scripts/locked-files-guard.sh를 부르지 않음"
}
t_wf_no_if() { need_wf || return; load_wf
  local hit; hit=$(grep -nE '^[[:space:]]*(- )?if:' <<< "$WFT")
  [ -z "$hit" ] || fail "'if:' 건너뛰기가 있음: $hit"
}
t_wf_job() { need_wf || return; load_wf
  local jobs job
  [ "$(count_re '^name:[[:space:]]*Locked Files Guard[[:space:]]*$')" -eq 1 ] || fail "워크플로 이름 'Locked Files Guard' 없음"
  jobs=$(sub_block '^jobs:' <<< "$WFT")
  assert_eq "$(top_keys <<< "$jobs")" "locked-files " "작업 id(하나뿐)"
  job=$(sub_block '^[[:space:]]+locked-files:' <<< "$jobs")
  local name; name=$(awk '{ match($0, /^ */); if (min == "" || RLENGTH < min) min = RLENGTH; d[NR] = RLENGTH; l[NR] = $0 }
    END { for (i = 1; i <= NR; i++) if (d[i] == min && l[i] ~ /^[[:space:]]*name:/) { v = l[i]; sub(/^[[:space:]]*name:[[:space:]]*/, "", v); print v } }' <<< "$job")
  [ -z "$name" ] || assert_eq "$name" "locked-files" "작업 name(검사 이름)"
}
t_wf_concurrency() { need_wf || return; load_wf
  assert_eq "$(count_re "^[[:space:]]*group:[[:space:]]*locked-files-$(ex github.event.pull_request.number)[[:space:]]*$")" 1 "concurrency group"
  assert_eq "$(count_re '^[[:space:]]*cancel-in-progress:[[:space:]]*true[[:space:]]*$')" 1 "cancel-in-progress: true"
}
t_wf_timeout() { need_wf || return; load_wf
  assert_eq "$(grep -E '^[[:space:]]*timeout-minutes:' <<< "$WFT" | sed -E 's/.*:[[:space:]]*//; s/[[:space:]]+$//' | tr '\n' ' ')" "5 " "timeout-minutes"
}
run_test "REVIEW-GATE-G23 이벤트: pull_request_target, types 5종, branches [dev] (Q8·Q10)" t_wf_trigger
run_test "REVIEW-GATE-G16 권한: contents·pull-requests read만" t_wf_permissions
run_test "REVIEW-GATE-G16 비밀 값(secrets) 안 씀" t_wf_no_secrets
run_test "REVIEW-GATE-G16 checkout은 base.sha, persist-credentials: false, PR 쪽 ref 없음 (Q7)" t_wf_checkout
run_test "REVIEW-GATE-G17 run: 안에 '\${{ }}' 없음, 제목·본문 안 씀" t_wf_run_no_expr
run_test "REVIEW-GATE-G17 env로 REPO·PR·EXPECTED_HEAD_SHA·GH_TOKEN, 경비 스크립트 실행 (Q7)" t_wf_env
run_test "REVIEW-GATE-G23 작업·단계 'if:' 없음 (Q8)" t_wf_no_if
run_test "REVIEW-GATE-G29 워크플로 'Locked Files Guard', 작업 'locked-files' 하나 (Q3)" t_wf_job
run_test "REVIEW-GATE-G24 concurrency locked-files-<PR 번호>, cancel-in-progress (Q11)" t_wf_concurrency
run_test "REVIEW-GATE-G21 timeout-minutes: 5 (Q11)" t_wf_timeout

# ---------- 외부 액션 버전 고정 (REVIEW-GATE-P01, 2026-11-24 승인) ----------
# 모든 워크플로의 uses: 는 40자 커밋 번호로 고정하고 줄 끝 주석에 버전을 적는다.
# 꼬리표(@v6)는 만든 쪽이 다른 코드로 옮길 수 있어, 토큰을 받는 액션이 바뀐 코드로 돌 수 있다.
t_uses_pinned() {
  local f line n=0 bad=""
  for f in .github/workflows/*.yml; do
    while IFS= read -r line; do
      n=$((n + 1))
      [[ "$line" =~ uses:[[:space:]]*[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}[[:space:]]+#[[:space:]]*v[0-9]+\.[0-9]+\.[0-9]+[[:space:]]*$ ]] || bad+="$f: $line"$'\n'
    done < <(tr -d '\r' < "$f" | grep -E '^[[:space:]]*(-[[:space:]]+)?uses:')
  done
  [ "$n" -ge 1 ] || fail "uses: 줄을 하나도 못 찾음"
  [ -z "$bad" ] || fail "커밋 번호 + 버전 주석으로 고정 안 된 uses:"$'\n'"$bad"
}
run_test "REVIEW-GATE-P01 모든 워크플로 uses: 가 40자 커밋 번호 + '# vX.Y.Z' 주석으로 고정" t_uses_pinned

# ---------- 결과 ----------
echo
echo "통과 $PASSED, 실패 $FAILED"
if [ "$FAILED" -gt 0 ]; then
  printf '  실패: %s\n' "${FAILED_NAMES[@]}"
  exit 1
fi
exit 0
