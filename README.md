# Claude Code Studio Plugin

An Anypoint Studio plugin that embeds Claude Code as a native chat panel. Type messages directly in Studio and get responses from Claude — with full conversation history, project context awareness, and monospace-formatted code blocks.

---

## Prerequisites

1. **Anypoint Studio 7.x** (tested on 7.21)
2. **Claude Code CLI** installed and authenticated via your Claude.ai account (org standard). Verify by running `claude --version` in a terminal — if it returns a version number, you're good. If you haven't authenticated yet, run `claude` once and follow the login prompt, or contact your team lead for the Claude.ai onboarding steps before proceeding.
3. **Java 17 and Maven** to build the plugin jar. The jar is not distributed prebuilt — you build it from source (see [Building from Source](#building-from-source)) before installing.

---

## Installation — Windows

1. **Clone this repo** (or download it as a zip):
   ```
   git clone https://github.com/Surescripts/claude-code-anypoint-studio-plugin.git
   ```

2. **Close** Anypoint Studio completely.

3. **Create the `dropins` folder** if it doesn't already exist:
   ```
   C:\AnypointStudio\dropins\
   ```
   > Adjust the path if Studio is installed elsewhere — the `dropins` folder goes in the same directory as `AnypointStudio.exe`. It will not exist by default; just create it.

4. **Build** the plugin jar (see [Building from Source](#building-from-source)), then **copy** it into that folder:
   ```
   org.claudecodestudio.plugin\target\org.claudecodestudio.plugin-1.0.0-SNAPSHOT.jar  →  C:\AnypointStudio\dropins\
   ```

5. **Start** Anypoint Studio.

6. **Open the view:** `Window → Show View → Other → General → Claude Studio`

---

## Installation — macOS

1. **Clone this repo** (or download it as a zip):
   ```bash
   git clone https://github.com/Surescripts/claude-code-anypoint-studio-plugin.git
   ```

2. **Close** Anypoint Studio completely.

3. **Build** the plugin jar (see [Building from Source](#building-from-source)), then **copy** it into the Studio dropins folder:
   ```bash
   cp org.claudecodestudio.plugin/target/org.claudecodestudio.plugin-1.0.0-SNAPSHOT.jar \
      /Applications/AnypointStudio.app/Contents/Eclipse/dropins/
   ```
   Or navigate there in Finder: right-click `AnypointStudio.app` → **Show Package Contents** → `Contents` → `Eclipse` → `dropins`

4. **Start** Anypoint Studio.

5. **Open the view:** `Window → Show View → Other → General → Claude Studio`

> **Tip:** The plugin auto-detects Claude Code at `/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin`, and `~/.npm-global/bin`. If your install is somewhere else, set the path under `Window → Preferences → Claude Studio`.

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

Claude remembers the full conversation history within a session. Switching project context starts a new session in that project's working directory.

---

## Building from Source

Requires Java 17 and Maven.

```bash
git clone https://github.com/Surescripts/claude-code-anypoint-studio-plugin.git
cd claude-code-anypoint-studio-plugin
mvn clean package -DskipTests -pl org.claudecodestudio.plugin,org.claudecodestudio.feature
```

Output jar:
```
org.claudecodestudio.plugin/target/org.claudecodestudio.plugin-1.0.0-SNAPSHOT.jar
```
