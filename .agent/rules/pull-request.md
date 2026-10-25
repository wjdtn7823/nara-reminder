---
trigger: always_on
---

# PR 규칙

- Pull Request 를 만들거나 "PR 올려줘" 같은 요청을 받으면 `gh pr create` 를 바로 실행하지 말고 항상 `/create-pr` 워크플로(`.agent/workflows/create-pr.md`)를 따른다.
- 워크플로의 Open Code Review 단계를 건너뛰지 않는다. Critical/High 지적이 있으면 사용자 확인 없이 PR 을 만들지 않는다.
