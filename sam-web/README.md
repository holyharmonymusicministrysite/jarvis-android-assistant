# SAM Web Operator

Installable browser workspace for Android and Windows. It includes approval-first local tools, notes, business records, file preview and voice input where supported.

## Real local AI brain (no API key)
SAM connects to Ollama running on a Windows/Linux computer through Ollama's local HTTP API. Install Ollama from https://ollama.com, then run `ollama pull qwen2.5:3b` (or another model your computer can handle). On the computer, use `http://localhost:11434`. A phone can connect over the same Wi-Fi using the computer's LAN IP, provided Ollama is configured to listen on the LAN and allow the SAM web origin. Never expose Ollama directly to the public internet.

This adapter calls Ollama `/api/tags` and `/api/chat`. It requires no API key or subscription. The model runs on your computer, so speed and capability depend on its CPU/RAM/GPU and the model downloaded. If no model is connected, SAM clearly says so instead of inventing AI answers.

## Run
Serve this folder over HTTP/HTTPS. For installation and microphone access, use HTTPS. On Android Chrome choose **Install app** / **Add to Home screen**.

## Included
- Responsive Android/desktop PWA workspace.
- Local Ollama model connection and conversational chat.
- Approval queue for supported local actions.
- Local notes, business records and JSON export.
- User-selected text/Markdown/JSON/CSV preview.
- Browser voice input where supported.
- Search handoff and Wikipedia summary lookup.
- Offline shell cache.

## Limits
A browser app cannot silently control all Android/Windows functions or bypass OS permissions. Chatting with a model does not itself execute tools. External actions remain explicit and platform-dependent. Free hosted inference cannot be guaranteed unlimited.