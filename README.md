# Claude Code Studio

An Anypoint Studio plugin that embeds Claude Code as a native chat panel. Ask questions, explore a codebase, and get help with DataWeave and Mule flows without leaving the IDE — with conversation history, project context awareness, and formatted code blocks.

---

## Prerequisites

1. **Anypoint Studio 7.x** (developed and tested against 7.21)
2. **Claude Code CLI**, installed and authenticated. Verify with `claude --version` — if it prints a version, you're set. If not, install it and run `claude` once to complete sign-in.
3. **Java 17 and Maven**, to build the plugin jar from source.

The plugin drives your own local Claude Code installation. It does not bundle credentials and does not require an API key of its own.

---

## Installation — Windows

1. **Clone the repo** (or download the zip):
   ```
   git clone https://github.com/spolwort1970/claude-code-studio.git
   ```

2. **Close** Anypoint Studio completely.

3. **Create the `dropins` folder** if it doesn't already exist:
   ```
   C:\AnypointStudio\dropins\
   ```
   > Adjust the path if Studio is installed elsewhere — `dropins` goes in the same directory as `AnypointStudio.exe`. It does not exist by default; just create it.

4. **Build** the plugin jar (see [Building from Source](#building-from-source)), then **copy** it in:
   ```
   org.claudecodestudio.plugin\target\org.claudecodestudio.plugin-1.0.0-SNAPSHOT.jar  →  C:\AnypointStudio\dropins\
   ```

5. **Start** Anypoint Studio.

6. **Open the view:** `Window → Show View → Other → General → Claude Studio`

---

## Installation — macOS

1. **Clone the repo** (or download the zip):
   ```bash
   git clone https://github.com/spolwort1970/claude-code-studio.git
   ```

2. **Close** Anypoint Studio completely.

3. **Build** the plugin jar (see [Building from Source](#building-from-source)), then **copy** it into Studio's dropins folder:
   ```bash
   cp org.claudecodestudio.plugin/target/org.claudecodestudio.plugin-1.0.0-SNAPSHOT.jar \
      /Applications/AnypointStudio.app/Contents/Eclipse/dropins/
   ```
   Or in Finder: right-click `AnypointStudio.app` → **Show Package Contents** → `Contents` → `Eclipse` → `dropins`

4. **Start** Anypoint Studio.

5. **Open the view:** `Window → Show View → Other → General → Claude Studio`

> **Tip:** The plugin auto-detects Claude Code at `/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin`, and `~/.npm-global/bin`. If yours is elsewhere, set the path under `Window → Preferences → Claude Studio`.

---

## Using the Plugin

| Action | How |
|---|---|
| Send a message | Type in the box at the bottom, press **Enter** |
| Insert a newline | **Shift+Enter** |
| Attach a file | **📎** button next to the input — inserts the file path into your message |
| Switch project context | Toolbar dropdown (top-left of the view) |
| Start a fresh conversation | Switch (or re-select) the project context in the dropdown |
| Export the conversation | **Export** button in the header — writes the full transcript to a text file |
| Archive chat history | **Archive** button in the header — moves history files aside so the next session starts fresh |
| Open the view by keyboard | **Ctrl+Shift+L** |
| Change CLI path, model, or extra args | `Window → Preferences → Claude Studio` |

Claude keeps the full conversation history within a session. Switching project context starts a new session in that project's working directory.

---

## What the plugin does with your code

Worth reading before you install this at work.

- **There is no server.** The plugin invokes the Claude Code CLI on your machine as a child process. It has no backend, sends nothing to any endpoint of its own, and collects no telemetry or analytics.
- **Your account, your terms.** Anything Claude sees goes through your own Claude Code installation, under whatever plan and data-handling terms already apply to it. Installing this plugin does not change where your code goes — it changes how conveniently you can ask about it.
- **Project context.** The plugin writes a managed block into a `CLAUDE.md` file in the active project's working directory so Claude knows what project it's looking at. That file lives in your workspace; review it before committing it.
- **Conversation history** is stored locally so sessions survive restarts. Use **Archive** to clear it.
- **Permission prompts are bypassed.** The CLI is launched with `--dangerously-skip-permissions`, because an interactive approval prompt has nowhere to render inside the Studio view. That means Claude Code will act within the working directory without asking each time. Know that before pointing it at a repository you care about, and consider whether your employer's AI usage policy permits it.

If your organization restricts which AI tools may touch source code, check that Claude Code itself is approved. This plugin inherits that decision rather than making a new one.

---

## Building from Source

Requires Java 17 and Maven.

```bash
git clone https://github.com/spolwort1970/claude-code-studio.git
cd claude-code-studio
mvn clean package -DskipTests -pl org.claudecodestudio.plugin,org.claudecodestudio.feature
```

Output jar:
```
org.claudecodestudio.plugin/target/org.claudecodestudio.plugin-1.0.0-SNAPSHOT.jar
```

---

## Compatibility

| | Tested |
|---|---|
| Anypoint Studio | 7.21 (7.x expected to work) |
| Java | 17 |
| OS | Windows, macOS |

Reports from other Studio 7.x versions are welcome — open an issue with your version and what happened.

---

## License

Released under the [MIT License](LICENSE). Copyright (c) 2026 Shane Polwort.
