# Third-party components

The APK includes the following exact-version libraries. Full license notices are included in its assets under `legal/`.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| Java-WebSocket | 1.6.0 | MIT | https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0 |
| SLF4J API and NOP | 2.0.17 | MIT | https://github.com/qos-ch/slf4j |
| Google Play services Auth (with its Play services base, basement and tasks libraries) | 21.2.0 | Android Software Development Kit License | https://developers.google.com/android/guides/setup |
| AndroidX Media3 Transformer, Effect and Common (with their Media3 and Guava dependencies) | 1.5.1 | Apache License 2.0 | https://github.com/androidx/media/tree/1.5.1 |
| AndroidSVG (SVG profile pictures drawn on the phone) | 1.4 | Apache License 2.0 | https://github.com/BigBadaboom/androidsvg/tree/androidsvg-1.4 |
| open-bot-avatar (engine ported to Java as `core/BotAvatar*`; gallery shapes and design prompt in `assets/avatar/`) | 0.2.0 (b59a73a) | MIT | https://github.com/DominikScholz/open-bot-avatar |
| Hermes Agent (xAI OAuth protocol adaptation) | db1fa153fe24a051af44ef8ee862b93e27998060 | MIT | https://github.com/NousResearch/hermes-agent |
| ima2-gen (Codex OAuth protocol adaptation) | bc58b964cc7f40223d7ebda8e9f6446da688973b | MIT | https://github.com/lidge-ai/ima2-gen |

JSON-java 20250107 is downloaded only for host-side verification. The APK uses Android's built-in `org.json` implementation; JSON-java is not packaged.

`CodexImages` adapts the OAuth constants, login flow, account routing, and JSON image/edit request protocol from ima2-gen's `lib/chatgptLogin.ts`, `lib/chatgptAuth.ts`, and `lib/codexBackend/`. Its MIT copyright and license are included in `app/src/main/assets/legal/IMA2-GEN-LICENSE.txt`. No Node.js runtime, proxy, CLI, external credential file, or ima2-gen UI is bundled.

Android SDK/Build Tools are build dependencies downloaded from Google's official repository. They are not distributed in the source archive.

Protocol documentation used for the independent implementation:

- https://ai.google.dev/gemini-api/docs/live-api/tools
- https://ai.google.dev/gemini-api/docs/live-api/live-transcribe
- https://developer.android.com/develop/background-work/services/fgs/service-types

OpenAI "Sign in with ChatGPT" and Responses API, implemented independently from OpenAI's public documentation and the protocol used by OpenAI's Sign-in with ChatGPT DevKit. No DevKit code (Noncommercial License) is included.

No Mobile Codex (GPL-3.0) or Buddy application code is included. Mobile Codex was read only as a design reference.

No Nuri (https://github.com/lsszz2100/Nuri, MIT) code is included. Its scheduled tasks, which hand the last result to the next run, were read only as a design reference for the independent Java implementation in `core/WorkSchedules`.


`GrokOAuth` and `GrokAccount` adapt the public OAuth client constants and device authorization/refresh flow from Hermes Agent’s `hermes_cli/auth_constants.py` and `hermes_cli/auth_xai.py`. The MIT license is included in `app/src/main/assets/legal/HERMES-LICENSE.txt`. No Hermes runtime, saved account credentials, API key, browser cookies, or Python code is bundled. The image-to-video implementation follows https://docs.x.ai/developers/model-capabilities/video/image-to-video.

## GeckoView and browser dependencies

GeckoView 158.0.20261021001945 is distributed unmodified under MPL 2.0. Its corresponding source is [Mozilla release e4ce606692cd81e6898547e2edd7c552fefcbb04](https://hg.mozilla.org/releases/mozilla-release/rev/e4ce606692cd81e6898547e2edd7c552fefcbb04). The app bundles the source link and notices in `assets/legal/GECKOVIEW-NOTICE.txt`. AndroidX and Kotlin dependencies use Apache License 2.0. Engine-contained notices are retained.
