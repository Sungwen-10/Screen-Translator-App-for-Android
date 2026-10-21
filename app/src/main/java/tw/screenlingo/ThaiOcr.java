package tw.screenlingo;

import android.graphics.*;
import com.googlecode.tesseract.android.*;
import java.util.*;

/** Used only on TranslationEngine's worker thread. */
final class ThaiOcr implements AutoCloseable {
    record Page(String text, List<TranslationEngine.Block> blocks, float confidence) { }
    private TessBaseAPI api;
    Page recognize(Bitmap bitmap, OcrModels models) {
        if (!models.state().ready()) throw new IllegalStateException("請先到「選擇及下載語言模型」下載泰文文字辨識模型。");
        if (api == null) {
            api = new TessBaseAPI();
            if (!api.init(models.root().getAbsolutePath(), "tha", TessBaseAPI.OEM_LSTM_ONLY)) {
                api.recycle(); api = null;
                throw new IllegalStateException("泰文辨識模型無法開啟，請刪除後重新下載。");
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
        }
        // Improve small screenshot glyphs while bounding memory use on large photos.
        float scale = Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 2600 ? 1.5f : 1;
        Bitmap enlarged = scale == 1 ? bitmap : Bitmap.createScaledBitmap(bitmap,
            Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
        List<TranslationEngine.Block> blocks = new ArrayList<>(); String text; float confidence;
        try {
            api.setImage(enlarged);
            text = api.getUTF8Text(); confidence = api.meanConfidence();
            ResultIterator iterator = api.getResultIterator();
            if (iterator != null) {
                try {
                    iterator.begin();
                    do {
                        String paragraph = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_PARA);
                        if (paragraph == null || paragraph.isBlank()) continue;
                        Rect r = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_PARA);
                        Rect box = new Rect(Math.round(r.left / scale), Math.round(r.top / scale),
                            Math.round(r.right / scale), Math.round(r.bottom / scale));
                        if (!box.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight()) || box.isEmpty()) continue;
                        paragraph = paragraph.trim();
                        blocks.add(new TranslationEngine.Block(box, paragraph, TranslationEngine.sampleBackground(bitmap, box),
                            box.height() / (float) Math.max(1, paragraph.split("\n").length)));
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_PARA));
                } finally { iterator.delete(); }
            }
        } finally { api.clear(); if (enlarged != bitmap) enlarged.recycle(); }
        return new Page(text == null ? "" : text, blocks, confidence);
    }
    @Override public void close() { if (api != null) { api.recycle(); api = null; } }
}
