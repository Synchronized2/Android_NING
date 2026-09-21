# NING Android

Lightweight native Android client for OpenAI-compatible chat and image generation endpoints.

## Features

- Shared or separate HTTPS base URLs and API keys for chat and image generation
- Fetch and cache model IDs from each node's OpenAI-compatible `GET /models` endpoint
- Dropdown-only chat and image model selection
- API key encrypted with Android Keystore and stored in app-private preferences; revealing it
  requires the user-created local password
- Streaming `text/event-stream` responses with regular JSON fallback
- One continuous input area with `+`, `对话`, and `生图` actions
- `+` attaches a JPEG, PNG, or WebP image for vision analysis or as an image-generation reference
- `对话` always calls the selected chat model; compatible chat models can still call the `generate_image` tool automatically
- `生图` bypasses intent detection and directly calls the selected image model
- Image generation uses `POST /images/generations`; reference-image generation uses multipart `POST /images/edits`
- Base64 and HTTPS URL image responses are supported
- Generated-image full-screen preview and user-selected download location
- Generated-image gallery with previous/next swipe navigation, pinch zoom, double-tap zoom,
  and panning while zoomed
- Per-message retry after failed or stopped chat and image requests
- Edge TTS playback for assistant messages with stop control, optional automatic playback,
  the same 27 curated Chinese/dialect voices and 11 style presets as the NING mini program,
  full voice-list refresh, voice preview, rate/volume/pitch controls, and up to three
  synthesis attempts before reporting failure
- Local Live2D voice conversation mode with blinking, breathing, gaze, touch response,
  request-state animation and TTS mouth movement
- 50 bundled Cubism 3 characters with a single-page library that combines live preview,
  search, family filtering, half/full-body framing, and selection; model files and textures
  are read from the APK and are never downloaded at runtime
- Android system speech recognition with partial transcripts, automatic chat submission,
  and forced playback of the voice-initiated reply
- Keeps the screen awake only while a chat or image request is running
- Automatically persisted chat and image history, per-message deletion, stop generation,
  multi-conversation history with rename/delete, new conversations, and rotation recovery
- Controlled local actions: open installed launcher apps, media volume, playback keys, and common system settings pages
- OpenAI-compatible function tools with a deterministic local intent fallback for common Chinese commands
- Device actions use a fixed allowlist and validated parameters; arbitrary package names, URLs, and intents are rejected
- No API credential embedded in the APK
- Edge TTS uses Microsoft's online speech service without the model API key; spoken text is
  sent to that service. OkHttp is used for its HTTPS and WebSocket transport.

## Device actions

Examples include `打开微信`, `把音量调到 40%`, `下一首`, `播放音乐`, and `打开蓝牙设置`.
Explicit short commands are handled locally and work without a configured model node. More varied wording can be recognized through Chat Completions function tools when the configured node supports `tools`. If a compatible node rejects the tool fields, the client retries as a normal chat request.

Modern Android versions do not let ordinary apps silently toggle Wi-Fi or Bluetooth. The assistant opens the relevant system settings page instead. App launching only matches visible launcher labels or a small built-in alias list; the model cannot supply an arbitrary package, URI, or shell command.

## Endpoint rules

- `https://example.com/v1` becomes `https://example.com/v1/chat/completions`
- `https://example.com` becomes `https://example.com/v1/chat/completions`
- A full `.../chat/completions` URL is used unchanged
- Chat and image generation can use the same API root or two independently configured roots

Only HTTPS endpoints are accepted. The model field must use the exact model ID exposed by the node.

## Media capability notes

- Image understanding requires the selected Chat Completions model to accept `image_url` content.
- Image generation requires the node to expose an OpenAI-compatible `/images/generations` endpoint.
- Reference-image generation requires the node to expose an OpenAI-compatible `/images/edits` endpoint.
- General document attachments are not sent through Chat Completions. OpenAI documents use `input_file` through the Responses API, which a compatible node must expose separately.
- Video generation does not have a universal Chat Completions protocol. It needs the node's specific API documentation and task polling contract.
- Voice input uses the Android system speech-recognition service and may require network
  access depending on the service installed on the device. It is turn-based rather than a
  full-duplex Realtime API connection.
- Bundled character artwork and Cubism Core have separate restrictive licenses. See
  `THIRD_PARTY_NOTICES.md`; this build is intended for local non-commercial use.

## Build

Open the directory in Android Studio, or run:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The personal release build is optimized, non-debuggable, and signed with the same local
Android debug certificate as development builds so it can upgrade an existing installation
without clearing encrypted settings. This signing setup is not intended for app-store release.
