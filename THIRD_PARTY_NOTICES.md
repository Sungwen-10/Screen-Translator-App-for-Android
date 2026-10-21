# Third-party components

Tesseract4Android 4.9.0 by Adaptech s.r.o., Robert Pösel and contributors is licensed under Apache-2.0. It includes Tesseract 5.5.1 (Apache-2.0), Leptonica 1.85.0 (BSD-style), IJG libjpeg v9f and libpng 1.6.48 (libpng license). Upstream license texts are retained in `app/src/main/assets/licenses/`, inside both the APK and source archive. See [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0).

The optional Thai recognition model is the unmodified `tha.traineddata` from [tesseract-ocr/tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd), commit `87416418657359cb625c412a48b6e1d6d41c29bd`, under Apache-2.0. It is downloaded only when selected, rather than bundled in the APK. Size: 1,072,600 bytes; SHA-256: `294227cc2d1292b0acb28d61d4115c88252b96d466ca90b417cf4cf0c67bf07c`. The model's Apache license is included in the app's license assets.

Google ML Kit (OCR, language identification, translation) and its transitive dependencies are provided under their respective licenses and terms. See the Maven artifacts and [ML Kit terms](https://developers.google.com/ml-kit/terms), [translation usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms).

`app/src/main/res/drawable-xxhdpi/google_translate.png` is the unmodified `color-regular@3x.png` from Google's [official attribution assets](https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip). Google and Google Translate are trademarks of Google LLC.

ICU4J 76.1 is Copyright © Unicode, Inc. and others, distributed under the [Unicode License](https://www.unicode.org/license.txt). The ICU4J JAR's license resources are retained in the app package where the Android packaging process permits.

This service may contain translations powered by Google. Google disclaims all warranties related to the translations, express or implied, including any warranties of accuracy, reliability, and any implied warranties of merchantability, fitness for a particular purpose and noninfringement.

This application is an independent project; Google and Samsung do not endorse it.
