# AI platform (S-129)

How the in-product AI is built, for engineers adding or changing an AI feature. Operating it (keys, models,
budgets, dashboards, troubleshooting) is in the runbook: [runbooks/ai.md](../runbooks/ai.md). The data-residency
decision and its conditions are in DECISIONS.md ("AI provider and data residency").

## The shape

```
feature (catalogue, messaging, studio, …) ──► ai.api.AiCompletions ──► LlmClient (ObservedLlmClient)
                                                │  budgets (Valkey)        │  redaction, metrics, traces
                                                │  feature → model         ▼
                                                │  tool loop          OpenRouterClient | FakeLlmClient
                                                ▼
                                           ai.usage (no content)
```

- **`ca.northline.ai`** is a platform module with no business dependency. Its public package `ai.api` holds:
  - `LlmClient`: the port, in the OpenAI chat-completions shape (tool calling, `complete` / `stream`).
  - `AiCompletions`: what features call (`complete` for drafts and classification, `converse` for the tool loop).
  - `AssistantTool`: tools other modules contribute to the Studio assistant.
  - `Prompts` / `Prompt`, `AiFeature`, and the errors `AiUnavailable` (503) and `AiRateLimited` (429).
- **Features live where their data lives** and depend on `ai.api` only: listing copy in `catalogue`, reply
  suggestions in `messaging`, the assistant in `studio`, and so on. The reverse direction would create module cycles.
- **Provider:** `AI_PROVIDER=fake` (local, test, dev) or `openrouter` (staging, prod; `fake` is refused there).

## Adding an AI feature

1. Add a constant to `AiFeature` with its tier (`LIGHT` for classification and short drafts, `STANDARD` for
   reasoning with tools). The tier picks the default model; `OPENROUTER_MODEL_<FEATURE>` overrides it.
2. Write the prompt as `server/api/src/main/resources/ai/prompts/<name>.v1.md`.
   - State that every fact comes from the data given, and that the reply language is the person's.
   - For a JSON reply, include one `` ```json `` example block. The fake model returns it, so the screen works
     offline.
   - To change a prompt later, add `v2` and keep `v1`. Usage rows record `name@vN`.
3. In the owning module's `application` package, build the request and call it:
   `AiCompletions.Request.of(feature, caller, prompt, system, user)` (add `.asJson()` for JSON), then
   `ai.complete(request)`.
   - Send the minimum: ids, names of listings and services, dates, amounts and states. Never contact details or
     another business's data.
   - The port redacts card numbers, SINs, bank accounts, emails and phones anyway.
4. Drafts are always drafts. Return them to the person to edit, mark them as AI-assisted, and never send or publish
   them automatically.
5. Add a labelled set `server/api/src/test/resources/ai-eval/<name>.json` and an `EvalSuite`:
   - Register the suite in `EvalSuites.all()`.
   - Add a test that runs it through `SimulatedEvals.run(suite)` and expects every case to pass.
   - Run the live eval before shipping (below).

## Assistant tools

Implement `AssistantTool` as a bean in the module that owns the data, over that module's own use cases. These are the
same use cases the REST API (and the MCP server, S-127) call.

- `permission()`: the narrowest permission of the matching Studio screen. The platform offers the tool only to roles
  that hold it, and calls `MerchantAccess.require` again before every run, so the run uses fresh membership and
  `acr=mfa`.
- Scope every read to `call.merchantId()`. Return ids, names, dates, amounts and states, with no contact details.
- `write() = true` for a change. The loop never runs a write. It returns a `PendingAction` with your `preview(call)`,
  and the Studio asks the person to confirm before anything runs.
- Throw the usual domain errors (`NotFound`, `RuleViolation`, `Conflict`). The model reads them as
  `{error, detail}`.

## Evals

| Mode | Where | What it proves |
|---|---|---|
| simulated | every build (`*EvalTest`) | plumbing, tools run as the caller, refusals, parsing, the grader (it replays each case's `mock` through the real OpenRouter adapter and a local stand-in) |
| live | `OPENROUTER_API_KEY=… ./gradlew :api:test --tests '*AiEvalLiveTest'` | the model's judgement: each suite's pass rate against its gate (0.8), precision/recall for classifiers, tokens and cost |

Reports are written to `server/api/build/ai-eval/<suite>-<mode>.{md,json}`.

## Test kit

`ca.northline.ai.AiTestKit` builds the platform without Spring: any adapter, in-memory budgets, a recording usage
log and a permissive `MerchantAccess` mock. `ca.northline.ai.MockOpenRouter` is the local stand-in for
`/api/v1/chat/completions`, including SSE streaming with tool-call fragments. `LlmClientContract` is what every
adapter must pass.
