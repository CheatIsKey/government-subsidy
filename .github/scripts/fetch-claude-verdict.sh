#!/usr/bin/env bash
# PR 리뷰를 조회해 select-claude-review.jq로 고른 리뷰 JSON을 출력 (REVIEW-GATE).
# 사용: fetch-claude-verdict.sh REPO PR HEAD_SHA
# gh 실패 또는 JSON이 아니면 종료 코드 1.
set -eo pipefail

if [ "$#" -ne 3 ]; then
  echo "::error::사용법: fetch-claude-verdict.sh REPO PR HEAD_SHA"
  exit 1
fi
REPO=$1; PR=$2; HEAD_SHA=$3
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

reviews=$(gh api "repos/$REPO/pulls/$PR/reviews" --paginate) || {
  echo "::error::PR 리뷰 조회(gh api)에 실패했다."
  exit 1
}

printf '%s\n' "$reviews" | jq -s --arg sha "$HEAD_SHA" -f "$DIR/select-claude-review.jq" || {
  echo "::error::리뷰 조회 결과를 해석하지 못했다(JSON이 아님)." >&2
  exit 1
}
