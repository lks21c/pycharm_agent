# Git Commit & Push

Commit all changes and push to remote repository.

## Instructions

1. Run `git status` to check current changes
2. Run `git diff` to review staged and unstaged changes
3. Run `git log --oneline -3` to see recent commit style
4. Analyze all changes and create a meaningful commit message:
   - Use conventional commit format (feat/fix/chore/refactor/docs)
   - Focus on "why" rather than "what"
   - Keep it concise (1-2 sentences)
5. Stage all relevant changes with `git add`
6. Commit with the message ending with:
   ```
   Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
   ```
7. Push to the current branch
8. Report the commit hash and summary

Do NOT commit files that may contain secrets (.env, credentials, API keys).
