Store graphics for this locale.

Expected layout (fastlane supply / F-Droid / IzzyOnDroid):
  icon.png                 512x512
  featureGraphic.png       1024x500 (optional)
  phoneScreenshots/1.png, 2.png, ...
  sevenInchScreenshots/, tenInchScreenshots/  (optional, tablets)

phoneScreenshots/ and tenInchScreenshots/ are rendered without a device, from
an invented network: ./gradlew :app:updateDebugScreenshotTest, then
python3 scripts/readme_shots.py, which writes both locales and keeps the aspect
ratio at or below 2:1. The icon can be exported from docs/logo.svg.

Stores ignore any file here that is not a known graphic, this README included.
