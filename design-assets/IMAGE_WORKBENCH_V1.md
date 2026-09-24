# NING 2.3.34 — image workspace and conversation views

The two top tabs stay unchanged. Within Chat, Character / Conversation only
changes view visibility; it does not stop or replace SpeechController, reload
the character, clear the conversation, or start a new session. The last chat
view is retained across top-tab changes and activity state restoration. The
character balloon continues to consume the existing playback text callback.

The image workspace follows the supplied neon reference's sections: prompt,
aspect ratio, style, quality, recent images and reference / generate actions.
It uses existing NING backgrounds and scalable vector HUD surfaces. Style
previews are simple illustrations in this first version, not generated results.
The prompt and chat drafts are independent. New conversation clears drafts.

Recent images come from persisted conversations, with sampled thumbnails off
the UI thread. The full gallery loads in batches; images open in the existing
zoom / swipe / download viewer. Generation still writes the user / assistant
pair to the current conversation. The job status and retry action are available
inside the workspace, and chat remains accessible during generation.

Image options are snapshotted on the response message so retries use the
original size, quality and effective prompt. Legacy requests omit optional
fields. Explicit ratios use 1024x1024, 960x1280, 864x1536 and 1536x864;
quality maps to low / medium / high. Non-default style is appended to the
prompt. Both image generation JSON and reference-image multipart carry options.

Official OpenAI documentation consulted on 2026-09-23:

- https://developers.openai.com/api/docs/models/gpt-image-2
- https://developers.openai.com/api/reference/resources/images/methods/generate

GPT Image 2 accepts flexible sizes aligned to 16 pixels according to that
reference. The configured third-party service and other selected models may
have different limits; server errors are shown without silently changing the
requested settings. No paid live generation was submitted for this UI change.

Validation: release compile / Lint; instrumentation at native 1264x2780 and
simulated 1080x2400. Checks include switching while busy, retention of a live
streaming speech session (without synthesis), separate drafts, multiline
composer bounds, workspace visibility, aspect ratios, and options surviving
serialization into generation and edit request fields. This does not measure
real audio continuity or verify the third-party service's option support.
