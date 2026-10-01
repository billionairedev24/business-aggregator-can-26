# Studio assistant (S-130)

The merchant assistant in the Studio: a drawer from the top bar, answers streamed over SSE through the BFF, and
"Explain this screen" insights on the dashboard, earnings and listings screens.

## How an answer is made

1. The prompt (`ai/prompts/studio-assistant.v1.md`) says only who is asking and what they may do. It names the
   business, its type, the person's role and that role's permissions, today's date and the business's time zone.
2. The model calls tools. Each tool is an `AssistantTool` bean in the module that owns the data, over that module's use
   cases, and runs **as the caller**:
   - it is offered only if the role holds the tool's permission;
   - `MerchantAccess.require` runs again before every run;
   - every read is scoped to the business.
3. Tool results go back to the model, redacted on the port. After at most `AI_MAX_TOOL_ROUNDS` rounds it must answer.
4. A write (`pack_order`, `start_travel`) is never run by the model. It comes back as `pending` with a preview. The
   person confirms in the drawer, then `POST …/assistant/actions` re-checks the permission, runs the write and audits
   it.

## Tools

| Tool | Module | Permission | Writes |
|---|---|---|---|
| `list_orders` | orders | VIEW | — |
| `pack_order` | orders | OPERATE | yes (confirm) |
| `list_jobs` | booking | VIEW (technicians: own jobs) | — |
| `start_travel` | booking | OPERATE | yes (confirm) |
| `list_listings` | catalogue | VIEW | — |
| `get_availability` | availability | VIEW | — |
| `earnings_overview`, `payouts_overview` | payments | FINANCE_READ | — |
| `list_threads` | messaging | VIEW (technicians: own threads) | — |
| `reviews_summary` | trust | VIEW | — |

Adding a tool: implement `AssistantTool` in the owning module, add it to `AssistantEval.TOOLS` (the catalogue test
checks the two match), and add eval cases to `ai-eval/assistant.json`.
