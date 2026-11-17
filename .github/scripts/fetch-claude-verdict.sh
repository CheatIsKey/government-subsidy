#!/usr/bin/env bash
# 껍데기: PR 리뷰를 조회해 select-claude-review.jq로 고른 리뷰 JSON을 출력 (REVIEW-GATE).
# 사용: fetch-claude-verdict.sh REPO PR HEAD_SHA
# gh 실패·JSON 아님이면 0이 아닌 종료. 조회 로직은 3단계(구현)에서 채운다.
set -eo pipefail
echo "::error::not implemented" >&2
exit 1
