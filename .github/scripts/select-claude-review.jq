# claude[bot]이 $sha 에 낸 APPROVED/CHANGES_REQUESTED 중 마지막 리뷰 객체, 없으면 null (REVIEW-GATE).
# 사용: jq -s --arg sha <SHA> -f select-claude-review.jq   (입력은 gh api --paginate 페이지 배열들)
[.[][] | select(.user.login == "claude[bot]" and .commit_id == $sha and (.state == "APPROVED" or .state == "CHANGES_REQUESTED"))] | last
