# Rokid OpenClaw 🕶️⚡

AR glasses app that lets you talk to OpenClaw (Mia) directly through Rokid Glasses.

## Architecture

```
[Rokid Glasses]                    [Your PC]
  Mic → STT (Android)              OpenClaw Gateway :3771
       ↓                                ↑
  Recognized text ─── HTTP POST ───→  /api/v1/sessions/send
       ↑                                ↓
  TTS ← ─────── Response text ────────┘
```

## Setup

1. Open in Android Studio
2. In `app/build.gradle.kts`, set your `OPENCLAW_GATEWAY_URL` to your PC's local IP
3. Sync Gradle
4. Connect Rokid Glasses via USB/ADB
5. Build & Run

## Configuration

### Gateway URL
Set your OpenClaw gateway address in `app/build.gradle.kts`:
```kotlin
buildConfigField("String", "OPENCLAW_GATEWAY_URL", "\"http://YOUR_PC_IP:3771\"")
```

### Key Mappings
- **Enter / DPad Center / Space** → Start voice input
- Customize in `MainActivity.onKeyDown()` for your Rokid model

## Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | UI + button handling + orchestration |
| `VoiceManager.kt` | STT (speech-to-text) + TTS (text-to-speech) |
| `OpenClawClient.kt` | HTTP client for OpenClaw Gateway API |

## Next Steps

- [ ] Test actual Rokid button keycodes and update mapping
- [ ] Add wake word detection ("Hey Mia")
- [ ] Integrate CXR-S SDK for phone relay features
- [ ] Add ElevenLabs TTS for better voice quality
- [ ] OpenClaw custom channel plugin (vs. direct API)
