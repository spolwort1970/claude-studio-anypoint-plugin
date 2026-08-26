# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Project Overview

An Eclipse plugin that embeds Claude Code CLI into Anypoint Studio (MuleSoft's Eclipse-based IDE) as a native SWT chat panel. Users type messages in Studio and get responses from Claude without leaving their IDE.

## Architecture

### Communication with Claude Code CLI
The plugin uses `claude -p --output-format stream-json --verbose --dangerously-skip-permissions` to communicate with the CLI in non-interactive print mode. Each user message spawns a new process; conversation history is maintained across messages via `--continue`. The plugin parses the `result` field from the terminal `{"type":"result"}` JSON event.

On Windows, the command is wrapped in `cmd.exe /c` to ensure PATH resolution finds the npm-installed `claude.cmd`. On macOS, common install paths (`/opt/homebrew/bin`, `/usr/local/bin`, etc.) are probed and the child process PATH is augmented.

### UI
Native SWT chat panel (`ClaudeTerminalView.java`):
- `StyledText` (read-only, scrollable) for conversation history with basic markdown rendering (fenced code blocks rendered in monospace with dark background)
- `Text` input + Send button at the bottom (Enter to send, Shift+Enter for newline)
- Toolbar: project context selector dropdown, New Conversation button, Preferences button
- Header label showing active project context

### Project Context
`ProjectContextManager` listens to the workbench selection service to track which projects are active. `ClaudeMdManager` writes a managed block into `CLAUDE.md` at the working directory so Claude has project context. The context selector toolbar action lets users manually pin one or more projects.

## Project Structure

```
org.claudecodestudio.plugin/
├── META-INF/MANIFEST.MF          — OSGi bundle manifest
├── plugin.xml                    — Extension points (view, preferences, keybinding)
├── build.properties
├── icons/claude.png              — 16x16 Claude icon
├── dist/                         — Pre-built jar for zero-build installation
└── src/org/claudecodestudio/
    ├── Activator.java
    ├── views/ClaudeTerminalView.java     — Main chat view
    ├── process/ClaudeProcessManager.java — CLI process lifecycle
    ├── context/ProjectContextManager.java
    ├── context/ClaudeMdManager.java
    ├── ui/ContextSelectorAction.java
    └── preferences/ClaudePreferencePage.java
org.claudecodestudio.feature/     — Eclipse feature
org.claudecodestudio.site/        — p2 update site
target-platform/                  — Tycho target platform definition
```

## Build

```bash
mvn clean package -DskipTests -pl org.claudecodestudio.plugin,org.claudecodestudio.feature
```

Output: `org.claudecodestudio.plugin/target/org.claudecodestudio.plugin-1.0.0-SNAPSHOT.jar`

## Installation

Copy the jar into the Studio `dropins` folder (create it if it doesn't exist) and restart Studio:
- **Windows:** `C:\AnypointStudio\dropins\`
- **macOS:** `/Applications/AnypointStudio.app/Contents/Eclipse/dropins/`

## Key Preferences

- `PREF_CLAUDE_PATH` — explicit path to claude binary (leave blank to auto-detect)
- `PREF_EXTRA_ARGS` — additional CLI arguments passed to every invocation
- `PREF_AUTO_LAUNCH` — unused (reserved)
