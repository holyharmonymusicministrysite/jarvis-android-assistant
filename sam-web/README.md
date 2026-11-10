# SAM Web Operator

A lightweight, installable, browser-based SAM workspace. No Termux, API key, subscription, server, or build step is required to run the static app.

## Run it
Open `index.html` from a local web server or host the `sam-web` folder as a static website. HTTPS is required for service-worker installation and microphone permissions on most browsers. On Android Chrome, open the hosted site menu and choose **Install app** / **Add to Home screen**.

## Included
- Responsive command-centre UI for Android and desktop.
- Plan-first task queue: every supported action waits for explicit approval.
- Local notes and business records stored in browser localStorage.
- Exportable JSON backup and text-note download.
- User-selected local text/Markdown/JSON/CSV file preview; files are not uploaded.
- Browser voice input where SpeechRecognition is supported.
- Approved web-search handoff and Wikipedia summary lookup.
- PWA manifest and offline shell cache.

## Honest capability boundary
This is a working local operator workspace, not a general-purpose LLM. It does not contain GPT-class model weights, silently control Android/Windows, crawl the whole web, or perform payments/messages. Search and Wikipedia lookup require internet. Browser storage is device/browser-specific and can be cleared by the browser; export backups. A future model adapter can be added when a genuinely free inference source or local model runtime is available. Free public inference services cannot be guaranteed unlimited.

## Privacy
Data stays in this browser profile unless the owner exports it or approves opening an external site. No analytics, tracking SDK, API key collection, or third-party backend is included.