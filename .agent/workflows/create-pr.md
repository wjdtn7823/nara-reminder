---
description: Open Code Review(위임 모드)로 현재 브랜치를 리뷰한 뒤 master 대상 PR 을 만든다
---

# PR 생성 (OCR 리뷰 포함)

PR 을 만들기 전에 반드시 이 순서를 따른다. 리뷰는 OCR 위임 모드로, 이 에이전트의 모델이 직접 수행한다(OCR 쪽 LLM/API 키 불필요).

1. 사전 점검
   - `which ocr || npm install -g @alibaba-group/open-code-review`
   - 커밋되지 않은 변경이 있으면 사용자에게 커밋할지 묻는다.
   - `git fetch origin master`

2. OCR 리뷰 — `.agent/skills/open-code-review-delegate/SKILL.md` 절차를 그대로 따른다.
   - 대상 범위: `--from origin/master --to HEAD`
   - `ocr delegate preview --format json --from origin/master --to HEAD`
   - `ocr delegate rule --format json <reviewable_files...>`
   - 각 파일의 diff 를 리뷰하고, 모든 reviewable 파일을 reviewed / skipped(사유) 로 표시한다.

3. 결과 보고
   - Critical/High, Medium, Low 로 분류하고 `total_files`, `reviewed_files`, `skipped_files`, `coverage_rate` 를 함께 보고한다.
   - **Critical/High 가 있으면 PR 을 만들지 말고 멈춘다.** 수정할지 사용자에게 묻고, 수정했다면 2단계부터 다시 한다.

4. PR 생성 (Critical/High 가 없거나 사용자가 그대로 진행하라고 한 경우)
   - `git push -u origin HEAD`
   - `gh pr create --base master --fill` 로 만들고, 본문 끝에 아래 형식의 리뷰 요약을 붙인다.

     ```
     ## Open Code Review (delegate)
     - 범위: origin/master..HEAD, 커버리지: <reviewed>/<total>
     - High: <n> / Medium: <n> / Low: <n>
     - <주요 지적 한 줄씩>
     ```

5. 생성된 PR URL 을 사용자에게 알린다.
