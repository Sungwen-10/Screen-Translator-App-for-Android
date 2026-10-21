package tw.screenlingo;

import android.graphics.*;
import android.os.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.ibm.icu.text.Transliterator;
import java.util.*;
import java.util.concurrent.*;

final class TranslationEngine implements AutoCloseable {
    static final class Block {
        final Rect bounds;
        final String original;
        final int background, foreground;
        final float sourceLineHeight;
        String translated, language, note = "";
        float confidence = .6f;
        Block(Rect bounds, String text, int color) {
            this(bounds, text, color, bounds.height() / (float) Math.max(1, text.split("\n").length));
        }
        Block(Rect bounds, String text, int color, float lineHeight) {
            this.bounds = new Rect(bounds); original = text; background = color;
            sourceLineHeight = lineHeight;
            double brightness = .2126 * Color.red(color) + .7152 * Color.green(color) + .0722 * Color.blue(color);
            foreground = brightness > 145 ? Color.rgb(20, 35, 32) : Color.WHITE;
        }
        boolean changed() { return translated != null && !translated.equals(original); }
    }
    record Result(List<Block> blocks, int width, int height, String target) { }
    interface Callback { void progress(String text); void success(Result result); void failure(String message); }
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, TextRecognizer> recognizers = new HashMap<>();
    private final LinkedHashMap<String, Translator> translators = new LinkedHashMap<>(4, .75f, true);
    private final Set<String> preparedPairs = new HashSet<>();
    private final Map<String, Transliterator> chineseForms = new HashMap<>();
    private record TranslationKey(String source, String target, String text) { }
    private final LinkedHashMap<TranslationKey, String> translations = new LinkedHashMap<>(256, .75f, true);
    private final LanguageIdentifier identifier = LanguageIdentification.getClient();
    private volatile boolean closed;
    private final OcrModels ocrModels;
    private final ThaiOcr thai = new ThaiOcr();
    TranslationEngine(android.content.Context context) { ocrModels = new OcrModels(context); }

    void run(Bitmap bitmap, String script, String source, String target, boolean wifiOnly, Callback callback) {
        run(bitmap, script, source, target, wifiOnly, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), callback);
    }
    void run(Bitmap bitmap, String script, String source, String target, boolean wifiOnly, Rect contentArea, Callback callback) {
        executor.execute(() -> {
            try {
                progress(callback, "辨識文字…");
                boolean forceThai = script.equals("th") || (script.equals("auto") && source.equals("th"));
                ThaiOcr.Page thaiPage = null;
                if (forceThai || (script.equals("auto") && source.equals("auto") && ocrModels.state().ready())) {
                    progress(callback, "辨識泰文…"); thaiPage = thai.recognize(bitmap, ocrModels);
                }
                InputImage image = InputImage.fromBitmap(bitmap, 0);
                Text best = null; int bestScore = -1; String winningScript = "latin";
                String[] scripts = forceThai ? new String[]{} : script.equals("auto") ? new String[]{"latin", "zh", "ja", "ko", "hi"} : new String[]{script};
                List<Exception> failures = new ArrayList<>();
                for (String kind : scripts) {
                    if (closed) return;
                    try {
                        Text result = Tasks.await(recognizer(kind).process(image));
                        int score = TextHeuristics.score(result.getText(), kind);
                        if (score > bestScore) { best = result; bestScore = score; winningScript = kind; }
                    } catch (ExecutionException e) { failures.add(e); }
                }
                if (closed) return;
                boolean useThai = forceThai || (thaiPage != null && thaiCandidate(thaiPage, best));
                if (!useThai && best == null && !failures.isEmpty()) throw failures.get(0);
                if (useThai ? thaiPage.blocks().isEmpty() : best == null || best.getText().trim().isEmpty()) {
                    throw new IllegalStateException("沒有找到可辨識的文字。畫面可能禁止截圖，或不屬於支援的文字種類。");
                }
                List<Integer> lineHeights = new ArrayList<>();
                if (!useThai) for (Text.TextBlock paragraph : best.getTextBlocks()) for (Text.Line line : paragraph.getLines()) {
                    Rect r = line.getBoundingBox(); if (r != null) lineHeights.add(r.height());
                }
                Collections.sort(lineHeights);
                List<Block> blocks = useThai ? new ArrayList<>(thaiPage.blocks()) : new ArrayList<>();
                boolean cjk = winningScript.equals("ja") || winningScript.equals("zh") || winningScript.equals("ko");
                if (!useThai) for (Text.TextBlock item : best.getTextBlocks()) blocks.addAll(paragraphBlocks(item, bitmap, 1));
                if (!lineHeights.isEmpty() && lineHeights.get(lineHeights.size() / 2) < 28
                    && Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 2600) {
                    progress(callback, "放大小字辨識…");
                    Bitmap enlarged = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth() * 2, bitmap.getHeight() * 2, true);
                    try {
                        Text refined = Tasks.await(recognizer(winningScript).process(InputImage.fromBitmap(enlarged, 0)));
                        if (!cjk && TextHeuristics.score(refined.getText(), winningScript) >= bestScore * .8f) blocks.clear();
                        // Supplement the original reading; an enlarged pass can miss words it read before.
                        if (cjk || blocks.isEmpty()) for (Text.TextBlock item : refined.getTextBlocks()) blocks.addAll(paragraphBlocks(item, bitmap, 2));
                    } finally { enlarged.recycle(); }
                }
                // Latin/Devanagari sentence readings keep their original grouping. Image
                // enhancement may fill uncovered labels, but must not replace a sentence
                // with one high-confidence word from a different OCR pass.
                Set<Block> protectedParagraphs = cjk ? Set.of() : new HashSet<>(blocks);
                if (!useThai && !confidentReading(best) && Math.max(bitmap.getWidth(), bitmap.getHeight()) >= 720) {
                    List<OcrEnhancer.Region> regions = OcrEnhancer.jobs(OcrEnhancer.regions(bitmap), bitmap.getWidth(), bitmap.getHeight());
                    for (int i = 0; i < regions.size(); i++) {
                        if (closed) return;
                        progress(callback, "加強描邊／彩色文字 " + (i + 1) + " / " + regions.size());
                        OcrEnhancer.Region region = regions.get(i);
                        if ((region.ink() == 0 || region.ink() >= 7) && !OcrEnhancer.usefulWhiteTile(bitmap, region)) continue;
                        for (int variant = 0; variant < (region.ink() == 0 ? 5 : 1); variant++) {
                            int ink = variant == 4 ? 8 : variant >= 2 ? 7 : region.ink(); float scale = variant % 2 == 1 ? 3 : 2;
                            Bitmap enhanced = OcrEnhancer.prepare(bitmap, new OcrEnhancer.Region(region.bounds(), ink), scale);
                            try {
                                Text extra = Tasks.await(recognizer(winningScript).process(InputImage.fromBitmap(enhanced, 0)));
                                for (Text.TextBlock paragraph : extra.getTextBlocks()) for (Text.Line line : paragraph.getLines()) {
                                    if (line.getConfidence() < (winningScript.equals("latin") ? .7f : .3f)) continue;
                                    for (Text.Element element : line.getElements()) for (ElementReading reading : elementReadings(element)) {
                                        Rect box = reading.bounds; String text = reading.text.trim();
                                        long letters = text.codePoints().filter(Character::isLetter).count();
                                        if (box == null || letters < 2 || element.getConfidence() < (winningScript.equals("latin") ? .7f : .3f)) continue;
                                        Rect mapped = new Rect(region.bounds().left + Math.round((box.left - 32) / scale), region.bounds().top + Math.round((box.top - 32) / scale),
                                            region.bounds().left + Math.round((box.right - 32) / scale), region.bounds().top + Math.round((box.bottom - 32) / scale));
                                        if (!mapped.intersect(region.bounds()) || mapped.height() < 5) continue;
                                        Block candidate = new Block(mapped, text, sampleBackground(bitmap, mapped)); candidate.confidence = element.getConfidence(); blocks.add(candidate);
                                    }
                                }
                            } finally { enhanced.recycle(); }
                        }
                    }
                }
                blocks = deduplicate(blocks, protectedParagraphs);
                String recognizedText = useThai ? thaiPage.text() : blocks.stream().map(b -> b.original).reduce("", (a, b) -> a + "\n" + b);
                blocks.removeIf(b -> !contentArea.contains(b.bounds.centerX(), b.bounds.centerY()));
                blocks.sort(Comparator.comparingInt((Block b) -> b.bounds.top).thenComparingInt(b -> b.bounds.left));
                blocks = mergeOverlapping(blocks, bitmap);
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                // No screen image is needed from this point onward.
                bitmap.recycle();
                String targetModel = Languages.modelTag(target);
                Transliterator chinese = target.equals("zh-Hant") ? chineseForms.computeIfAbsent(target, k -> Transliterator.getInstance("Simplified-Traditional"))
                    : target.equals("zh-Hans") ? chineseForms.computeIfAbsent(target, k -> Transliterator.getInstance("Traditional-Simplified")) : null;
                String pageLanguage = source.equals("auto") ? detect(recognizedText) : Languages.modelTag(source);
                    for (int i = 0; i < blocks.size(); i++) {
                        if (closed) return;
                        Block block = blocks.get(i);
                        if (TextHeuristics.statusToken(block.original)) {
                            block.translated = block.original; continue;
                        }
                        String from = source.equals("auto") ? TextHeuristics.contextualLanguage(block.original,
                            nearbyLanguage(block, blocks, pageLanguage), detect(block.original)) : Languages.modelTag(source);
                        if (from == null) from = pageLanguage;
                        if (from == null && block.original.codePoints().anyMatch(c -> TextHeuristics.inScript(c, "zh"))) from = "zh";
                        block.language = from;
                        if (from == null) {
                            block.translated = block.original; block.note = "無法判定語言，保留原文；可在設定指定原文語言。"; continue;
                        }
                        if (from.equals(targetModel) || block.original.codePoints().noneMatch(Character::isLetter)) {
                            block.translated = chinese == null ? block.original : chinese.transliterate(block.original); continue;
                        }
                        progress(callback, "翻譯 " + (i + 1) + " / " + blocks.size());
                        // OCR line wraps are visual, not sentence boundaries; avoid fragment-by-fragment translation.
                        String input = block.original.replace("\n", from.equals("ja") || from.equals("zh") || from.equals("th") ? "" : " ");
                        TranslationKey key = new TranslationKey(from, targetModel, input);
                        String translated = translations.get(key);
                        if (translated == null) {
                            String pair = from + ">" + targetModel;
                            Translator translator = translator(from, targetModel, pair);
                            if (!preparedPairs.contains(pair)) {
                                progress(callback, "檢查本機語言模型…");
                                if (!modelReady(from) || !modelReady(targetModel)) {
                                    progress(callback, "下載尚未安裝的語言模型…" + (wifiOnly ? "（需 Wi-Fi）" : ""));
                                    DownloadConditions.Builder conditions = new DownloadConditions.Builder();
                                    if (wifiOnly) conditions.requireWifi();
                                    try { Tasks.await(translator.downloadModelIfNeeded(conditions.build()), 120, TimeUnit.SECONDS); }
                                    catch (Exception e) { throw new IllegalStateException("模型尚未備妥。請連接" + (wifiOnly ? " Wi-Fi" : "網路") + "後再試。", e); }
                                }
                                preparedPairs.add(pair);
                            }
                            if (closed) return;
                            progress(callback, "翻譯 " + (i + 1) + " / " + blocks.size());
                            translated = Tasks.await(translator.translate(input));
                            translations.put(key, translated);
                            if (translations.size() > 256) translations.remove(translations.keySet().iterator().next());
                        }
                        block.translated = chinese == null ? translated : chinese.transliterate(translated);
                    }
                Result result = new Result(blocks, width, height, target);
                main.post(() -> { if (!closed) callback.success(result); });
            } catch (Exception e) {
                String message = e instanceof IllegalStateException ? e.getMessage() : "辨識或翻譯失敗。請確認網路、模型及原文語言後重試。";
                main.post(() -> { if (!closed) callback.failure(message); });
            } finally { if (!bitmap.isRecycled()) bitmap.recycle(); }
        });
    }
    private void progress(Callback callback, String message) { main.post(() -> { if (!closed) callback.progress(message); }); }
    private static boolean confidentReading(Text text) {
        long letters = 0, trusted = 0;
        for (Text.TextBlock paragraph : text.getTextBlocks()) for (Text.Line line : paragraph.getLines()) {
            long count = line.getText().codePoints().filter(Character::isLetter).count(); letters += count;
            if (line.getConfidence() >= .88f) trusted += count;
        }
        return letters >= 20 && trusted >= letters * .9f;
    }
    private static boolean thaiCandidate(ThaiOcr.Page page, Text alternative) {
        long thaiLetters = page.text().codePoints().filter(c -> TextHeuristics.inScript(c, "th") && Character.isLetter(c)).count();
        long allLetters = page.text().codePoints().filter(Character::isLetter).count();
        if (page.blocks().isEmpty() || thaiLetters < 4 || thaiLetters < allLetters * .55 || page.confidence() < 85) return false;
        // A Thai-only alphabet can turn Latin/Japanese shapes into convincing Thai gibberish.
        // Keep an independently confident ML Kit reading of the same screen instead.
        long trustedLetters = 0, specificLetters = 0;
        if (alternative != null) for (Text.TextBlock block : alternative.getTextBlocks()) for (Text.Line line : block.getLines()) {
            if (line.getConfidence() >= .85f) trustedLetters += line.getText().codePoints().filter(Character::isLetter).count();
            specificLetters += line.getText().codePoints().filter(c -> TextHeuristics.inScript(c, "ja")
                || TextHeuristics.inScript(c, "ko") || TextHeuristics.inScript(c, "hi")).count();
        }
        return specificLetters < Math.max(4, thaiLetters * .15) && trustedLetters < Math.max(4, thaiLetters * .6);
    }
    private static boolean modelReady(String language) throws ExecutionException, InterruptedException {
        return language.equals("en") || Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(new TranslateRemoteModel.Builder(language).build()));
    }
    private Translator translator(String from, String target, String pair) {
        Translator current = translators.get(pair);
        if (current != null) return current;
        if (translators.size() >= 3) {
            String oldest = translators.keySet().iterator().next(); translators.remove(oldest).close(); preparedPairs.remove(oldest);
        }
        current = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(target).build());
        translators.put(pair, current); return current;
    }
    private String detect(String text) throws ExecutionException, InterruptedException {
        String script = TextHeuristics.clearScriptLanguage(text);
        if (script != null) return script;
        String identified = Tasks.await(identifier.identifyLanguage(text));
        return "und".equals(identified) ? null : TranslateLanguage.fromLanguageTag(identified);
    }
    private static String nearbyLanguage(Block block, List<Block> blocks, String page) {
        if (!"ja".equals(page) && !"ko".equals(page)) return page;
        float reach = Math.max(80, block.sourceLineHeight * 6);
        for (Block other : blocks) {
            if (!page.equals(TextHeuristics.clearScriptLanguage(other.original))) continue;
            int dx = Math.max(0, Math.max(other.bounds.left - block.bounds.right, block.bounds.left - other.bounds.right));
            int dy = Math.max(0, Math.max(other.bounds.top - block.bounds.bottom, block.bounds.top - other.bounds.bottom));
            if (Math.hypot(dx, dy) <= reach) return page;
        }
        return null;
    }
    private static List<Block> mergeOverlapping(List<Block> blocks, Bitmap bitmap) {
        List<OverlayGeometry.Box> boxes = new ArrayList<>();
        for (Block b : blocks) boxes.add(new OverlayGeometry.Box(b.bounds.left, b.bounds.top, b.bounds.right, b.bounds.bottom));
        List<Block> merged = new ArrayList<>();
        for (List<Integer> group : OverlayGeometry.overlappingGroups(boxes)) {
            if (group.size() == 1) { merged.add(blocks.get(group.get(0))); continue; }
            group.sort(Comparator.comparingInt((Integer i) -> blocks.get(i).bounds.top).thenComparingInt(i -> blocks.get(i).bounds.left));
            Rect union = new Rect(blocks.get(group.get(0)).bounds); StringBuilder text = new StringBuilder();
            List<Float> heights = new ArrayList<>();
            for (int index : group) {
                Block b = blocks.get(index); union.union(b.bounds); heights.add(b.sourceLineHeight);
                if (text.length() > 0) text.append('\n'); text.append(b.original);
            }
            Collections.sort(heights);
            merged.add(new Block(union, text.toString(), sampleBackground(bitmap, union), heights.get(heights.size() / 2)));
        }
        merged.sort(Comparator.comparingInt((Block b) -> b.bounds.top).thenComparingInt(b -> b.bounds.left));
        return merged;
    }
    private static List<Block> deduplicate(List<Block> candidates, Set<Block> protectedParagraphs) {
        Map<Block, Double> scores = new IdentityHashMap<>();
        for (Block candidate : candidates) {
            double completion = candidate.confidence >= .55f && candidates.stream().anyMatch(other -> other != candidate
                && OcrEnhancer.overlap(candidate.bounds, other.bounds) > .8f
                && candidate.bounds.width() > other.bounds.width() * 1.05f
                && candidate.confidence >= other.confidence - .2f
                && TextHeuristics.completes(candidate.original, other.original)) ? .15 : 0;
            scores.put(candidate, quality(candidate) + completion - (separateWordEvidence(candidate, candidates) ? .5 : 0)
                + (protectedParagraphs.contains(candidate) ? 2 : 0));
        }
        candidates.sort(Comparator.comparingDouble((Block b) -> -scores.get(b)));
        List<Block> result = new ArrayList<>();
        for (Block candidate : candidates) {
            if (result.stream().noneMatch(b -> OcrEnhancer.overlap(b.bounds, candidate.bounds) > .4f)) result.add(candidate);
        }
        return result;
    }
    private static boolean separateWordEvidence(Block whole, List<Block> candidates) {
        String text = whole.original.replaceAll("\\s+", "");
        if (text.length() < 4 || !text.codePoints().allMatch(c -> TextHeuristics.inScript(c, "ja")
            || TextHeuristics.inScript(c, "zh") || TextHeuristics.inScript(c, "ko"))) return false;
        List<Block> parts = new ArrayList<>();
        for (Block other : candidates) if (other != whole && other.confidence >= Math.max(.3f, whole.confidence - .2f)
            && other.original.codePoints().filter(Character::isLetter).count() >= 2
            && other.bounds.width() < whole.bounds.width() * .85f
            && whole.bounds.height() < other.bounds.height() * 1.5f
            && OcrEnhancer.overlap(whole.bounds, other.bounds) > .3f) parts.add(other);
        for (Block left : parts) for (Block right : parts) {
            float h = Math.min(left.bounds.height(), right.bounds.height());
            if (right.bounds.left - left.bounds.right > h * .25f
                && Math.min(left.bounds.bottom, right.bounds.bottom) - Math.max(left.bounds.top, right.bounds.top) > h * .7f
                && left.bounds.left <= whole.bounds.left + h && right.bounds.right >= whole.bounds.right - h) return true;
        }
        return false;
    }
    private static double quality(Block block) {
        long letters = block.original.codePoints().filter(Character::isLetter).count();
        long specific = block.original.codePoints().filter(c -> TextHeuristics.inScript(c, "ja") || TextHeuristics.inScript(c, "zh") || TextHeuristics.inScript(c, "ko")).count();
        return block.confidence + Math.min(12, letters) * .015 + (letters > 0 ? specific / (double) letters * .08 : 0);
    }
    private record Piece(Rect box, String text, float height, boolean coloredInk, float confidence) { }
    private record ElementReading(Rect bounds, String text) { }
    private static List<ElementReading> elementReadings(Text.Element element) {
        Rect box = element.getBoundingBox();
        if (box == null) return List.of();
        List<Text.Symbol> symbols = new ArrayList<>(element.getSymbols());
        // CJK words on separate buttons can be returned as one Element. A geometric
        // gap between symbols is independent of vocabulary and preserves each button.
        boolean cjk = element.getText().codePoints().filter(Character::isLetter).allMatch(c -> TextHeuristics.inScript(c, "ja")
            || TextHeuristics.inScript(c, "zh") || TextHeuristics.inScript(c, "ko"));
        if (!cjk || symbols.size() < 2 || box.width() < box.height() * 1.5f) return List.of(new ElementReading(new Rect(box), element.getText()));
        symbols.removeIf(s -> s.getBoundingBox() == null); symbols.sort(Comparator.comparingInt(s -> s.getBoundingBox().left));
        List<ElementReading> pieces = new ArrayList<>(); Rect run = null; StringBuilder text = new StringBuilder();
        for (Text.Symbol symbol : symbols) {
            Rect current = symbol.getBoundingBox();
            if (run != null && current.left - run.right > Math.max(4, box.height() * .5f)) {
                pieces.add(new ElementReading(run, text.toString())); run = null; text.setLength(0);
            }
            if (run == null) run = new Rect(current); else run.union(current);
            text.append(symbol.getText());
        }
        if (run != null) pieces.add(new ElementReading(run, text.toString()));
        return pieces.isEmpty() ? List.of(new ElementReading(new Rect(box), element.getText())) : pieces;
    }
    private static Piece piece(Rect box, String text, float height, Bitmap image, float confidence) {
        int bg = sampleBackground(image, box), ink = 0, colorful = 0;
        for (int y = Math.max(0, box.top); y < Math.min(image.getHeight(), box.bottom); y += 2) {
            for (int x = Math.max(0, box.left); x < Math.min(image.getWidth(), box.right); x += 2) {
                int c = image.getPixel(x, y), r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                if (Math.abs(r - Color.red(bg)) + Math.abs(g - Color.green(bg)) + Math.abs(b - Color.blue(bg)) < 100) continue;
                ink++;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 70) colorful++;
            }
        }
        return new Piece(box, text, height, ink > 0 && colorful > ink * .15f, confidence);
    }
    private static Rect scaled(Rect r, float scale) {
        return new Rect(Math.round(r.left / scale), Math.round(r.top / scale), Math.round(r.right / scale), Math.round(r.bottom / scale));
    }
    private static List<Block> paragraphBlocks(Text.TextBlock paragraph, Bitmap image, float scale) {
        List<Piece> pieces = new ArrayList<>();
        for (Text.Line line : paragraph.getLines()) {
            Rect lineBox = line.getBoundingBox(); if (lineBox == null) continue;
            boolean cjkLine = line.getText().codePoints().anyMatch(c -> TextHeuristics.inScript(c, "ja")
                || TextHeuristics.inScript(c, "zh") || TextHeuristics.inScript(c, "ko"));
            List<Text.Element> elements = new ArrayList<>(line.getElements());
            elements.sort(Comparator.comparingInt(e -> e.getBoundingBox() == null ? 0 : e.getBoundingBox().left));
            Rect run = null; StringBuilder text = new StringBuilder(); int previousRight = 0, runElements = 0; float runConfidence = 0;
            for (Text.Element element : elements) for (ElementReading reading : elementReadings(element)) {
                Rect box = reading.bounds;
                if (run != null && box.left - previousRight > (cjkLine ? Math.min(run.height(), box.height()) * .5f : lineBox.height() * .95f)) {
                    pieces.add(piece(scaled(run, scale), text.toString(), lineBox.height() / scale, image, runConfidence / Math.max(1, runElements))); run = null; text.setLength(0); runElements = 0; runConfidence = 0;
                }
                if (run == null) run = new Rect(box); else run.union(box);
                if (text.length() > 0 && box.left - previousRight > lineBox.height() * .15f) text.append(' ');
                text.append(reading.text); previousRight = box.right; runConfidence += element.getConfidence(); runElements++;
            }
            if (run != null) pieces.add(piece(scaled(run, scale), text.toString(), lineBox.height() / scale, image, runConfidence / Math.max(1, runElements)));
            else if (!line.getText().isBlank()) pieces.add(piece(scaled(lineBox, scale), line.getText(), lineBox.height() / scale, image, line.getConfidence()));
        }
        pieces.sort(Comparator.comparingInt((Piece p) -> p.box.top).thenComparingInt(p -> p.box.left));
        List<List<Piece>> groups = new ArrayList<>();
        for (Piece piece : pieces) {
            List<Piece> match = null;
            for (List<Piece> group : groups) {
                Piece last = group.get(group.size() - 1); float h = Math.min(last.height, piece.height);
                if (piece.box.top >= last.box.bottom - h * .2f && piece.box.top - last.box.bottom <= h * .9f
                    && Math.abs(last.box.left - piece.box.left) <= h * 1.6f
                    && Math.max(last.height, piece.height) <= h * 1.28f && last.coloredInk == piece.coloredInk) match = group;
            }
            if (match == null) { match = new ArrayList<>(); groups.add(match); } match.add(piece);
        }
        List<Block> result = new ArrayList<>();
        for (List<Piece> group : groups) {
            Rect union = new Rect(group.get(0).box); StringBuilder text = new StringBuilder(); List<Float> heights = new ArrayList<>();
            for (Piece piece : group) { union.union(piece.box); if (text.length() > 0) text.append('\n'); text.append(piece.text); heights.add(piece.height); }
            Collections.sort(heights);
            if (!union.isEmpty()) {
                Block block = new Block(union, text.toString(), sampleBackground(image, union), heights.get(heights.size() / 2));
                block.confidence = (float) group.stream().mapToDouble(Piece::confidence).average().orElse(.6);
                result.add(block);
            }
        }
        return result;
    }
    private TextRecognizer recognizer(String script) {
        return recognizers.computeIfAbsent(script, key -> switch (key) {
            case "zh" -> TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            case "ja" -> TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case "ko" -> TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            case "hi" -> TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
            default -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        });
    }
    /** Median of edge pixels is resistant to dark text on light backgrounds (and vice versa). */
    static int sampleBackground(Bitmap image, Rect rect) {
        int[] r = new int[80], g = new int[80], b = new int[80]; int n = 0;
        for (int i = 0; i < 20; i++) {
            int x = rect.left + Math.round(rect.width() * i / 19f);
            int y = rect.top + Math.round(rect.height() * i / 19f);
            int[][] points = {{x, rect.top - 2}, {x, rect.bottom + 2}, {rect.left - 2, y}, {rect.right + 2, y}};
            for (int[] p : points) {
                int color = image.getPixel(Math.max(0, Math.min(image.getWidth() - 1, p[0])), Math.max(0, Math.min(image.getHeight() - 1, p[1])));
                r[n] = Color.red(color); g[n] = Color.green(color); b[n] = Color.blue(color); n++;
            }
        }
        Arrays.sort(r); Arrays.sort(g); Arrays.sort(b); return Color.rgb(r[40], g[40], b[40]);
    }
    @Override public void close() {
        closed = true;
        executor.execute(() -> {
            recognizers.values().forEach(TextRecognizer::close); identifier.close();
            thai.close();
            translators.values().forEach(Translator::close); translators.clear(); preparedPairs.clear(); translations.clear(); chineseForms.clear();
        });
        executor.shutdown();
    }
}
