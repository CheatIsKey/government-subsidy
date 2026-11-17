# 껍데기: claude[bot]이 $sha 에 낸 APPROVED/CHANGES_REQUESTED 중 마지막 리뷰 객체, 없으면 null (REVIEW-GATE).
# 사용: jq -s --arg sha <SHA> -f select-claude-review.jq   (입력은 gh api --paginate 페이지 배열들)
# 선택 로직은 3단계(구현)에서 채운다.
null
