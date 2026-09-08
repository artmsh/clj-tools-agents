---
name: wrap-up
description: Wrap up the session - consolidate learnings into memory, log the session's work to the Brain journal, output a brief recap. Use when the user types /wrap-up, asks to wrap up, or conclude the session.
tags: [skill, agents-setup]
---

Steps:
1. Scan the session: 
  - gather filesystem modifications
  - identify solved problems
  - analyze decisions made
  - look for new user preferences, tools, or patterns observed
  - identify if project state has changed.
  
2. Update auto-memory at `~/Brain/20-Projects/<project-slug>/`:
  - for **each meaningful learning** (ask advisor if those facts are worth recalling in a future conversations):
    - check MEMORY.md first — update an existing entry if relevant, create a new file only if no match exists
    - keep the MEMORY.md index in sync
  - **skip if:**: 
    - derivable from code or git history
    - purely ephemeral
    - a session narrative ("what we did today")
    - contains secrets or sensitive information.
  
3. Update Brain daily journal

  Daily notes live **flat** in `~/Brain/journals/YYYY-MM-DD.md` (`date +%F`).
  
  1. **Create the note if missing**, using the vault's template:
  ```markdown
  ---
  title: <TODAY>
  tags: [journal, year/<YEAR>]
  date: <TODAY>
  source: direct input
  ---
  
  # <TODAY>
  
  ```
  2. **Append-only.** Read the existing file first; add bullets under the existing list. **Never overwrite** the day's note or re-add an entry already there (other tools and the user also write to it).
  3. **One terse bullet per meaningful thing done** — match the day's voice (Ukrainian, fallback English; identifiers as-is), `<project>: <thing>` prefix style. Example: `- mcp: #66 — split out the conformance suite + research doc`.
  4. **Same filter as memory** — only what's worth recalling later; no secrets, no "what we chatted about".
  
  Synced vault — edit markdown directly; Obsidian and the `brain` qmd index pick up changes on their own.

4. Output session recap:
  ```
  **Session recap**
  - <what was built/fixed/configured>
  - <key decisions or findings>
  - <what's next / WIP left>
  - Memory: <N new / N updated entries>
  ```
