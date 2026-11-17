#!/usr/bin/env bash
# REVIEW-GATE PR 리뷰 판정 확인 테스트 (docs/cases.md "## REVIEW-GATE PR 리뷰 판정 확인")
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

# ---------- 결과 ----------
echo
echo "통과 $PASSED, 실패 $FAILED"
if [ "$FAILED" -gt 0 ]; then
  printf '  실패: %s\n' "${FAILED_NAMES[@]}"
  exit 1
fi
exit 0
