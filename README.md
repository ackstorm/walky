# Walky

An Android app: hold a sphere to record your voice, lift your thumb to send
the audio to your own HTTPS endpoint. Slide down before lifting to discard the take. No
account, and the audio is the only content it sends.

## Install

Download the APK from the latest [release](https://github.com/ackstorm/walky/releases) and
open it on the phone (Android 12 or later). Each release is signed with a different debug
key: uninstall the previous version before installing a newer one.

## How it works

- **Hold** the sphere to record. **Lift** to send. **Slide down** 64 dp and lift to discard.
- Locking the phone or leaving the app while recording discards the take.
- If the delivery fails, the take is kept and the app offers to retry or discard it, also
  after the app is closed and reopened. Retries are always manual.

## Settings

- **Destination URL**: must be `https`.
- **Header** (optional): one name and value, for example `X-API-Key`. The value is stored
  encrypted with an Android Keystore key.

It works on any phone with Android 12 or later. On a Pixel with Quick Tap you can also open
it with two taps on the back: go to **Settings → System → Gestures → Quick Tap**, turn it on,
pick **Open app** and choose Walky.

## What your endpoint receives

One `POST` per take. The body is the audio file: mono AAC-LC in an MPEG-4 container.

```sh
curl --request POST 'https://example.com/audio' \
  --header 'Content-Type: audio/mp4' \
  --header 'X-API-Key: TEST_KEY' \
  --data-binary '@note.m4a'
```

Any `2xx` status confirms delivery, including `204` with an empty body. Redirects are not
followed: the app shows the status code and never resends the audio or the header elsewhere.

## Build

Docker is the only requirement; the toolchain lives in the image.

```sh
docker compose run --rm build ./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT. See [LICENSE](./LICENSE).
