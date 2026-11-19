#!/usr/bin/env bash
# 잠금 파일 경비 검사 (REVIEW-GATE-G)
# PR이 잠금 파일(.github/, .coderabbit/, .coderabbit.yaml 등)을 건드리면 실패한다. 실패가 기본(fail-closed)이다.
# 입력(환경 변수): REPO(예: o/r), PR(예: 7), EXPECTED_HEAD_SHA(이벤트가 알려 준 PR head.sha)
set -euo pipefail

: "${REPO:?REPO 필요}" "${PR:?PR 필요}" "${EXPECTED_HEAD_SHA:?EXPECTED_HEAD_SHA 필요}"

die() { echo "::error::$1"; exit 1; }

# PR 정보에서 "changed_files head.sha" 한 줄을 읽는다
read_meta() {
  gh api "repos/$REPO/pulls/$PR" | jq -er '"\(.changed_files) \(.head.sha)"' | tr -d '\r'
}

# 1) PR 정보: 새 커밋이 올라왔는지, 바뀐 파일이 몇 개인지
meta=$(read_meta) || die "PR 정보 조회 실패"
changed=${meta%% *}; head1=${meta#* }
[ "$head1" = "$EXPECTED_HEAD_SHA" ] || die "새 커밋이 올라와 다시 검사 필요 (검사 시작 시 head 불일치)"

# 2) 파일 목록: 전체 쪽을 한 배열로 모아 읽는다. 하나라도 실패하면 실패
pages=$(gh api "repos/$REPO/pulls/$PR/files?per_page=100" --paginate --slurp) || die "파일 목록 조회 실패"
count=$(jq -e '[.[][]] | length' <<< "$pages" | tr -d '\r') || die "파일 목록 조회 실패: 응답이 JSON 배열이 아님"
[ "$count" = "$changed" ] || die "파일 목록을 끝까지 읽지 못함 (읽은 것 $count개, changed_files $changed개)"

# 잠금 규칙: 루트 기준, 대소문자 무시. filename과 previous_filename 둘 다 본다. 출력은 PR에 적힌 원래 대소문자
locked=$(jq -r '
  def locked: ascii_downcase
    | (startswith(".github/") or startswith(".coderabbit/")
       or . == ".coderabbit.yaml" or . == ".coderabbit.yml" or . == ".coderabbit.config.ts");
  [.[][] | (.filename, .previous_filename) | select(type == "string" and locked)] | unique[]' <<< "$pages" | tr -d '\r')

# 3) PR 정보 다시: 읽는 사이 새 커밋이 올라왔는지
meta=$(read_meta) || die "PR 정보 조회 실패"
[ "${meta#* }" = "$EXPECTED_HEAD_SHA" ] || die "새 커밋이 올라와 다시 검사 필요 (검사 중 head 변경)"

if [ -n "$locked" ]; then
  while IFS= read -r p; do
    echo "::error::잠긴 파일 변경: $p"
  done <<< "$locked"
  exit 1
fi
echo "잠금 파일 변경 없음"
