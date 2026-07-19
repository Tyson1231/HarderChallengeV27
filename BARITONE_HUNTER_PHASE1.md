# Baritone Hunter Integration — Phase 1

This build adds the server-authoritative control channel for a second client named `HunterBot`.

## Commands

- `/hunterbot status`
- `/hunterbot target <player>`
- `/hunterbot clear`
- `/hunterbot name <second-account-username>`

## Current behavior

The server identifies the second client, verifies line of sight, and sends one of four modes only to that client:

- `IDLE`
- `VISIBLE_PURSUIT` — exact live target position is sent
- `LAST_SEEN_SEARCH` — only the final confirmed position is sent
- `CANCEL` — live pursuit must stop

This phase intentionally contains no replacement pathfinder. The next phase connects these packets to the ported Baritone process and goal/execution stack.
