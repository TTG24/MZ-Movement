# Task execution

- Keep explanations, thinking, reasoning, and progress messages concise.
- Complete the requested implementation and relevant verification before ending.
- After a successful tool call, continue to the next necessary step.
- If an attempt fails twice, use a different approach.
- If blocked, state the exact error and what information is needed.
- End with a short report of changes, verification, and any remaining work.
- If you find yourself thinking for a long time, around 8192 tokens, then stop and try something simpler.

# General

- Language only in English.
- Do not get confused and go to another language, including Chinese.
- When using Powershell 7 for terminal, use the correct syntax.
- Use Powershell scripts if syntax is causing issues, then delete unneeded leftover scripts. Only when needed!
- When editing, changing, fixing, etc. make sure you are doing it one line at a time.
- If chat gets compacted manually or auto compact, continue the task!

# Project

- This is a Minecraft Fabric mod using Java.
- Target Minecraft Version: 26.3
- Use Mojang mappings unless Yarn is strictly required by the context.
- Always verify Mixin syntax and target annotations carefully to avoid crash loops.
- Use Gradle (Fabric Loom) for building tasks.
- Building/compiling this project, use needed Java 25 in C:\Users\austin\AppData\Local\Programs\Eclipse Adoptium

# Animation and image debugging

- Extract GIFs into numbered PNG frames before inspecting them.
- Start with 2–4 relevant frames and preserve their order.
- Confirm that image content is available before describing it.
- Describe visible observations separately from code hypotheses.
- Keep analysis brief, then inspect code or test a hypothesis.
- If reasoning repeats, stop rephrasing and take a concrete tool action.
- After two unsuccessful attempts, change approach.
- Continue through implementation and verification.
- If blocked, explain the specific blocker and evidence needed.

# MCP Tools/Servers

- Always check available tools only when necessary.

# Compiling

- When you are going to build the mod with gradlew, make sure to verify if it fails or succeeds. For either one, do not get stuck and continue with verification.