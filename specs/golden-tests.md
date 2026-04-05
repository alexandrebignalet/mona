# Golden Test Coverage Check

**Applies when any changed file matches these paths:**

- `infrastructure/llm/` (prompt or tool definition changes)
- `application/` (new or modified use cases)
- `domain/service/` (new domain logic that LLM tools interact with)

Before committing, answer:

| Question | Action if "yes" |
|----------|----------------|
| Does this change add a new Claude tool definition? | Add golden test cases in `test/golden/` covering typical inputs and edge cases. |
| Does this change modify how an existing tool's parameters are parsed? | Update existing golden tests to match new parameter schema. |
| Does this change add a new user-facing action type? | Add ≥3 golden test cases: happy path, edge case, French slang variant. |

If all answers are "no", skip — no golden test changes needed.
