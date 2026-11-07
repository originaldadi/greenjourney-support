# SoundCast (Android → Sonos)

Plays whatever music is playing on an Android phone through Sonos speakers over WiFi,
similar to AirPlay on an iPhone. Built for music: expect a delay of a few seconds, so it
isn't suitable for video.

## How it works

1. **Find speakers.** An SSDP search finds Sonos speakers on the WiFi network. One speaker
   is then asked for the household's group layout (`ZoneGroupTopology`), so the list shows
   rooms and groups the way the Sonos app does. Bonded subs and surrounds are hidden.
2. **Capture the music.** Android 10+ `AudioPlaybackCapture`, started from a foreground
   service of type `mediaProjection`, records what other apps are playing (media and game
   audio). Android asks the user's permission each time.
3. **Stream it.** The phone runs a small HTTP server that serves the audio as an endless
   16-bit 44.1 kHz stereo WAV stream at `http://<phone-ip>:8090/stream.wav` (about
   1.4 Mbit/s). If a speaker falls behind, the oldest audio is dropped so the delay doesn't
   keep growing.
4. **Tell Sonos to play it.** UPnP `SetAVTransportURI` + `Play` go to the group
   coordinator on port 1400. Volume uses `GroupRenderingControl`, so it moves the whole group.

## Code layout

| Path | What |
|---|---|
| `app/src/main/java/.../core/` | Plain Kotlin, no Android: discovery, Sonos control, WAV stream server |
| `app/src/main/java/.../android/CastService.kt` | Foreground service: capture, streaming, notification with Stop |
| `app/src/main/java/.../android/MainActivity.kt` | Compose screen: speaker list, volume, start/stop |
| `app/src/test/` | Unit tests for the core, using a fake speaker and fake SSDP responder |

## Building

- Android Studio: open this folder and run the `app` configuration.
- Command line: `./gradlew assembleDebug`. The APK lands in `app/build/outputs/apk/debug/`.
- GitHub Actions: `.github/workflows/soundcast-android.yml` runs the tests and uploads the
  debug APK as an artifact on every push that touches this folder.

Requires Android 10 (API 29) or newer.

## Known limits

- **Some apps block capture.** Apps can opt out of being recorded (often DRM-protected or
  some streaming apps). Those apps stay silent on Sonos; use their own Sonos support instead.
- **Delay.** Sonos buffers HTTP streams, so sound arrives a few seconds late.
- **The phone keeps playing too.** Android doesn't let normal apps mute the local speaker
  while capturing. Turn the phone's volume down. This hasn't been confirmed on a device
  yet: it may turn the captured audio down as well.
- **Same network.** The phone and speakers must be on the same WiFi, and the network
  mustn't block devices from talking to each other (some guest and public networks do).
- **Long sessions.** The WAV header promises the largest length the format allows, about
  6.7 hours. Longer sessions may need Stop then Start again.

## Status

The core (discovery, Sonos commands, stream server) is unit tested, and the stream was
checked with ffmpeg. It hasn't been run against real Sonos hardware yet. The first things
to check on a device are whether Sonos accepts the endless WAV stream and how well
discovery works on different WiFi routers.
