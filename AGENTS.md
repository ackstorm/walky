# walky

Android app (Kotlin, Jetpack Compose): hold a sphere to record voice, lift to POST the audio
to the user's own HTTPS URL. The spec and plans live in `./docs/`, which is not tracked.

## Build and test

Only inside the container; nothing is installed on the host.

```sh
docker compose run --rm build ./gradlew testDebugUnitTest > /tmp/mwt.log 2>&1; echo "EXIT=$?"
docker compose run --rm build ./gradlew assembleDebug > /tmp/mwt.log 2>&1; echo "EXIT=$?"
```

The APK lands in `./app/build/outputs/apk/debug/app-debug.apk`.

- `docker-compose.yml` caps the Gradle heap for a 3 GB machine. Exit 137 means the build was
  killed for memory: run it on a bigger machine instead of raising the cap.
- No emulator here. Behaviour lives in `Controller` and is proven by JVM tests with fakes;
  anything that needs Android is checked by hand on a Pixel.

## Release

Pushing a `v*` tag runs `./.github/workflows/release.yml`: tests, debug APK, GitHub release
with the APK attached. The APK is signed with the permanent key held only in the repository
secrets `WALKY_KEYSTORE` (base64 PKCS12, alias `walky`) and `WALKY_KEYSTORE_PASSWORD`, so
releases install over each other. Losing those secrets means every user uninstalls once.
