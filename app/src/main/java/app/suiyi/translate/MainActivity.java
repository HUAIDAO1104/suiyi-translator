package app.suiyi.translate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int INK = Ui.INK, TEAL = Ui.ACCENT;
    private static final int MICROPHONE = 10, SPEECH_FALLBACK = 11, INSTALL_PERMISSION = 12;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final GatewayClient gateway = new GatewayClient();
    private final GatewayClient modelGateway = new GatewayClient();
    private final UpdateClient updater = new UpdateClient();
    private final List<TranslationRecord> conversation = new ArrayList<>();
    private AppSettings settings;
    private HistoryStore history;
    private Spinner from, to, scene;
    private EditText input;
    private TextView status, translation, direction;
    private Button translateButton, stopButton, favoriteButton, voiceMode, textMode;
    private LinearLayout voiceA, voiceB, voiceDock, resultActions, setupBanner;
    private LinearLayout screenRoot, languageBar;
    private TextView voiceLabelA, voiceLabelB, languageA, languageB, originalLabel, sceneLabel, translatedHint, dockHint;
    private boolean typing;
    private SpeechRecognizer recognizer;
    private WavRecorder recorder;
    private TextToSpeech tts;
    private boolean ttsReady, busy, recording, listening, foreground;
    private int operation;
    private Language pendingSource, pendingTarget;
    private Language inputLanguage;
    private Future<?> translationTask;
    private TranslationRecord latest;
    private File pendingInstall;
    private boolean updateBusy;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = new AppSettings(this); history = new HistoryStore(this);
        createScreen();
        if (saved != null) {
            from.setSelection(saved.getInt("from", 0)); to.setSelection(saved.getInt("to", 2));
            input.setText(saved.getString("input", ""));
            inputLanguage=Language.values()[Math.max(0,Math.min(2,saved.getInt("input_language",from.getSelectedItemPosition())))];
            if(saved.containsKey("pending_source")){pendingSource=Language.values()[saved.getInt("pending_source")];pendingTarget=Language.values()[saved.getInt("pending_target")];}
            try {
                String restored = saved.getString("latest", "");
                if (!restored.isEmpty()) {
                    latest = TranslationRecord.fromJson(new org.json.JSONObject(restored));
                    translation.setText(latest.translated); direction.setText(latest.source.label + " → " + latest.target.label);
                    favoriteButton.setText(latest.favorite ? "已收藏" : "收藏");
                }
                org.json.JSONArray turns = new org.json.JSONArray(saved.getString("conversation", "[]"));
                for (int i = Math.max(0, turns.length() - 6); i < turns.length(); i++) conversation.add(TranslationRecord.fromJson(turns.getJSONObject(i)));
            } catch (Exception ignored) {}
            String downloaded = saved.getString("pending_install", "");
            if (!downloaded.isEmpty()) pendingInstall = new File(downloaded);
        }
        refreshVoiceLabels(); showResultState();
        if (saved != null) setTextMode(saved.getBoolean("typing", false), false);
        tts = new TextToSpeech(this, result -> runOnUiThread(() -> ttsReady = result == TextToSpeech.SUCCESS));
    }
    private void createScreen() {
        LinearLayout root = column();screenRoot=root; root.setBackgroundColor(Ui.BG);
        root.setFocusableInTouchMode(true); applyInsets(root, 20);
        LinearLayout heading = row(); heading.setPadding(0, dp(12), 0, dp(20));
        LinearLayout mark = row(); mark.setGravity(Gravity.CENTER); mark.setBackground(Ui.shape(this, Ui.ACCENT, 13, false));
        mark.addView(new Ui.Glyph(this, "logo", Color.WHITE), new LinearLayout.LayoutParams(dp(23), dp(23)));
        heading.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout brand = column(); TextView title = text("随译", 26); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.addView(title); TextView tagline = muted("让交流，自然发生", 10); tagline.setPadding(0, dp(4), 0, 0); brand.addView(tagline);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1); brandParams.leftMargin = dp(12); heading.addView(brand, brandParams);
        View records = Ui.iconButton(this, "history", "", Ui.INK, false, v -> showHistory(false)); records.setContentDescription("翻译记录");
        View config = Ui.iconButton(this, "settings", "", Ui.INK, false, v -> { if (!busy) showSettings(); else toast("请先结束当前输入或翻译。"); }); config.setContentDescription("设置");
        heading.addView(records, new LinearLayout.LayoutParams(dp(44), dp(44))); heading.addView(config, new LinearLayout.LayoutParams(dp(44), dp(44))); root.addView(heading);
        String[] labels = {"中文", "English", "ไทย"}; from = spinner(labels); to = spinner(labels);
        from.setSelection(Math.max(0,Math.min(2,settings.prefs.getInt("language_from",0))));to.setSelection(Math.max(0,Math.min(2,settings.prefs.getInt("language_to",2))));
        scene = spinner(new String[]{"日常交流", "餐厅点餐", "交通问路", "酒店住宿", "购物议价"});
        for (int i = 0; i < scene.getCount(); i++) if (scene.getItemAtPosition(i).equals(settings.scene())) scene.setSelection(i);
        LinearLayout languages = row(); languages.setBackground(Ui.shape(this, Color.WHITE, 24, true)); languages.setPadding(dp(18), dp(14), dp(18), dp(14));
        languageBar=languages;
        View mine = languageView(true); View theirs = languageView(false);
        languages.addView(mine, new LinearLayout.LayoutParams(0, dp(48), 1));
        View swap = Ui.iconButton(this, "swap", "", Ui.ACCENT, false, v -> {
            if (busy) return; int old = from.getSelectedItemPosition(); from.setSelection(to.getSelectedItemPosition()); to.setSelection(old); refreshVoiceLabels();
        }); swap.setContentDescription("交换双方语言"); swap.setBackground(Ui.ripple(this, Ui.SOFT, 14, false));
        languages.addView(swap, new LinearLayout.LayoutParams(dp(42), dp(42))); languages.addView(theirs, new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(languages);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = column(); body.setPadding(0, dp(16), 0, dp(10)); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout tools = row();
        LinearLayout mode = row(); mode.setPadding(dp(3), dp(3), dp(3), dp(3)); mode.setBackground(Ui.shape(this, Ui.LINE, 13, false));
        voiceMode = button("语音", v -> setTextMode(false, false)); textMode = button("文字", v -> setTextMode(true, true));
        voiceMode.setPadding(dp(8),0,dp(8),0); textMode.setPadding(dp(8),0,dp(8),0); voiceMode.setTextSize(13); textMode.setTextSize(13);
        mode.addView(voiceMode, new LinearLayout.LayoutParams(dp(60), dp(34))); mode.addView(textMode, new LinearLayout.LayoutParams(dp(60), dp(34)));
        tools.addView(mode); View stretch = new View(this); tools.addView(stretch, new LinearLayout.LayoutParams(0, 1, 1));
        sceneLabel = Ui.text(this, settings.scene() + "  ˅", 12, Ui.MUTED, false); sceneLabel.setGravity(Gravity.CENTER); sceneLabel.setPadding(dp(10), 0, dp(6), 0); sceneLabel.setContentDescription("选择交流场景");
        sceneLabel.setOnClickListener(v -> { if (!busy) showChoices("选择交流场景", new String[]{"日常交流", "餐厅点餐", "交通问路", "酒店住宿", "购物议价"}, scene.getSelectedItemPosition(), choice -> {scene.setSelection(choice); sceneLabel.setText(scene.getItemAtPosition(choice) + "  ˅");}); });
        tools.addView(sceneLabel, new LinearLayout.LayoutParams(-2, dp(44))); body.addView(tools); Ui.gap(body, 14);
        setupBanner = Ui.card(this, Ui.SOFT); setupBanner.setOrientation(LinearLayout.HORIZONTAL); setupBanner.setGravity(Gravity.CENTER_VERTICAL); setupBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        setupBanner.addView(new Ui.Glyph(this, "key", Ui.ACCENT), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout setupCopy = column(); setupCopy.addView(Ui.text(this, "添加 API Key", 14, Ui.ACCENT, true)); TextView setupHint = muted("接口已预置，填入密钥即可开始", 11); setupHint.setPadding(0, dp(5), 0, 0); setupCopy.addView(setupHint);
        LinearLayout.LayoutParams setupCopyParams = new LinearLayout.LayoutParams(0, -2, 1); setupCopyParams.leftMargin = dp(12); setupBanner.addView(setupCopy, setupCopyParams);
        setupBanner.addView(new Ui.Glyph(this, "chevron", Ui.ACCENT), new LinearLayout.LayoutParams(dp(16), dp(16))); setupBanner.setOnClickListener(v -> showSettings()); setupBanner.setContentDescription("添加 API Key，接口已预置"); body.addView(setupBanner);
        LinearLayout originalCard = Ui.card(this, Color.WHITE); LinearLayout originalHeader = row();
        originalLabel = Ui.text(this, "原文 · 中文", 12, Ui.MUTED, true); originalHeader.addView(originalLabel, new LinearLayout.LayoutParams(0, -2, 1));
        originalLabel.setContentDescription("选择文字输入语言");originalLabel.setOnClickListener(v->{if(!busy)showChoices("文字输入的语言",new String[]{source().label,target().label},textSource()==source()?0:1,choice->{inputLanguage=choice==0?source():target();refreshVoiceLabels();});});
        View clear = Ui.iconButton(this, "close", "", Ui.MUTED, false, v -> { if (!busy) input.setText(""); }); clear.setContentDescription("清空输入"); originalHeader.addView(clear, new LinearLayout.LayoutParams(dp(32), dp(28))); originalCard.addView(originalHeader);
        input = new EditText(this); input.setTextSize(22); input.setTextColor(Ui.INK); input.setHintTextColor(Color.rgb(174,179,195)); input.setHint("轻触输入想说的话"); input.setBackgroundColor(Color.TRANSPARENT); input.setPadding(0, dp(14), 0, dp(8)); input.setGravity(Gravity.TOP); input.setMinLines(3); input.setMaxLines(6); input.setIncludeFontPadding(false); input.setLineSpacing(dp(5), 1);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); input.setOnFocusChangeListener((v, focused) -> { if (focused) setTextMode(true, false); });
        input.setContentDescription("翻译原文");
        originalCard.addView(input, new LinearLayout.LayoutParams(-1, -2)); body.addView(originalCard);
        LinearLayout translatedCard = Ui.card(this, Ui.SOFT); direction = Ui.text(this, "译文 · ไทย", 12, Ui.ACCENT, true); translatedCard.addView(direction);
        translation = Ui.text(this, "译文将出现在这里", 23, Color.rgb(155,149,183), false); translation.setTextIsSelectable(true); translation.setLineSpacing(dp(7), 1); translation.setPadding(0, dp(18), 0, dp(14)); translatedCard.addView(translation);
        translatedHint = muted("双方轮流点击下方按钮说话", 11); translatedCard.addView(translatedHint);
        resultActions = row(); Button read = Ui.action(this, "volume", "朗读", v -> { if (latest != null) speak(latest.translated, latest.target); });
        Button copy = Ui.action(this, "copy", "复制", v -> { if (latest != null) copy(latest.translated); }); favoriteButton = Ui.action(this, "heart", "收藏", v -> favoriteLatest()); Button full = Ui.action(this, "expand", "大字", v -> showLargeTranslation());
        for (Button action : new Button[]{read, copy, favoriteButton, full}) resultActions.addView(action, new LinearLayout.LayoutParams(0, dp(58), 1)); resultActions.setVisibility(View.GONE); translatedCard.addView(resultActions); body.addView(translatedCard);
        LinearLayout more = row(); View fresh = Ui.iconButton(this, "new", "新对话", Ui.MUTED, false, v -> resetConversation()); more.addView(fresh, new LinearLayout.LayoutParams(-2, dp(44))); more.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        status = muted("准备就绪", 11); status.setGravity(Gravity.END); more.addView(status, new LinearLayout.LayoutParams(0, -2, 1.8f)); body.addView(more);
        LinearLayout dock = column(); dock.setPadding(0, dp(8), 0, dp(6)); root.addView(dock);
        voiceDock = row(); voiceA = voiceButton(true); voiceB = voiceButton(false);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(86), 1); left.rightMargin = dp(6); voiceDock.addView(voiceA, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(86), 1); right.leftMargin = dp(6); voiceDock.addView(voiceB, right); dock.addView(voiceDock);
        translateButton = Ui.button(this, "翻译文字  →", true, v -> translateText(input.getText().toString(), textSource(), textTarget())); translateButton.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(56))); translateButton.setVisibility(View.GONE); dock.addView(translateButton);
        stopButton = Ui.button(this, "取消", true, v -> { if (recording && recorder != null) recorder.stop(); else if (listening && recognizer != null) {recognizer.stopListening(); stopButton.setEnabled(false); status.setText("正在完成听写…");} else cancelOperation(); });
        stopButton.setVisibility(View.GONE); dock.addView(stopButton);
        dockHint = muted("点击说话，停顿后自动翻译", 11); dockHint.setGravity(Gravity.CENTER); dockHint.setPadding(0, dp(12), 0, dp(2)); dock.addView(dockHint);
        setContentView(root); root.requestFocus(); refreshVoiceLabels(); setTextMode(false, false); updateConfiguredState();
    }
    private View languageView(boolean mine) {
        LinearLayout view = column(); view.setGravity(Gravity.CENTER); view.setBackground(Ui.ripple(this, Color.TRANSPARENT, 14, false));
        TextView side = muted(mine ? "我说" : "对方说", 10); side.setGravity(Gravity.CENTER); view.addView(side); TextView label = Ui.text(this, mine ? "中文" : "ไทย", 19, Ui.INK, true); label.setGravity(Gravity.CENTER); label.setPadding(0, dp(5), 0, 0); view.addView(label);
        if (mine) languageA = label; else languageB = label;
        view.setOnClickListener(v -> { if (busy) return; Spinner selected = mine ? from : to, other = mine ? to : from;
            showChoices(mine ? "我说的语言" : "对方说的语言", new String[]{"中文", "English", "ไทย"}, selected.getSelectedItemPosition(), choice -> {
                int previous = selected.getSelectedItemPosition(); if (choice == other.getSelectedItemPosition()) other.setSelection(previous); selected.setSelection(choice); refreshVoiceLabels();
            }); }); view.setContentDescription(mine ? "选择我说的语言" : "选择对方说的语言"); return view;
    }
    private LinearLayout voiceButton(boolean mine) {
        LinearLayout view = row(); view.setGravity(Gravity.CENTER); view.setPadding(dp(12), dp(14), dp(12), dp(14)); view.setBackground(Ui.ripple(this, mine ? Ui.ACCENT : Color.WHITE, 22, !mine));
        Ui.Glyph mic = new Ui.Glyph(this, "mic", mine ? Color.WHITE : Ui.ACCENT); view.addView(mic, new LinearLayout.LayoutParams(dp(23), dp(23)));
        LinearLayout copy = column(); TextView label = Ui.text(this, "", 16, mine ? Color.WHITE : Ui.INK, true); if (mine) voiceLabelA = label; else voiceLabelB = label;
        copy.addView(label); TextView subtitle = Ui.text(this, mine ? "我来说" : "对方说", 10, mine ? Color.rgb(220,214,255) : Ui.MUTED, false); subtitle.setPadding(0, dp(6), 0, 0); copy.addView(subtitle);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.leftMargin = dp(12); view.addView(copy, p);
        view.setOnClickListener(v -> beginVoice(mine ? source() : target(), mine ? target() : source())); view.setFocusable(true); return view;
    }
    private void setTextMode(boolean text, boolean keyboard) {
        typing = text;
        if (voiceMode == null) return;
        input.setMinLines(text ? 3 : 2);
        voiceMode.setBackground(Ui.ripple(this, text ? Color.TRANSPARENT : Color.WHITE, 10, false)); voiceMode.setTextColor(text ? Ui.MUTED : Ui.INK);
        textMode.setBackground(Ui.ripple(this, text ? Color.WHITE : Color.TRANSPARENT, 10, false)); textMode.setTextColor(text ? Ui.INK : Ui.MUTED);
        voiceDock.setVisibility(!busy && !text ? View.VISIBLE : View.GONE); translateButton.setVisibility(!busy && text ? View.VISIBLE : View.GONE);
        dockHint.setText(text ? "输入完成后，点击翻译" : "点击说话，停顿后自动翻译");
        if (keyboard && text) { input.requestFocus(); input.post(()->{if(typing&&input.hasFocus())((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}); }
        if (!text) { input.clearFocus(); ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0); }
    }
    private void resetConversation() {
        if (busy) cancelOperation(); conversation.clear(); input.setText(""); latest = null; inputLanguage=source();refreshVoiceLabels();showResultState(); status.setText("已开启新对话"); if (tts != null) tts.stop();
    }
    private void showResultState() {
        resultActions.setVisibility(latest == null ? View.GONE : View.VISIBLE); translatedHint.setVisibility(latest == null ? View.VISIBLE : View.GONE);
        translation.setTextColor(latest == null ? Color.rgb(155,149,183) : Ui.INK);
        if (latest == null) {translation.setText("译文将出现在这里"); direction.setText("译文 · " + target().label); favoriteButton.setText("收藏");}
    }
    private void updateConfiguredState() { setupBanner.setVisibility(settings.hasApiKey() ? View.GONE : View.VISIBLE); }
    private void applyInsets(View root, int horizontal) {
        root.setPadding(dp(horizontal), 0, dp(horizontal), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top=bars.top; bottom=Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);}
            else {top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            if(v==screenRoot&&languageBar!=null&&Build.VERSION.SDK_INT>=30)languageBar.setVisibility(insets.getInsets(WindowInsets.Type.ime()).bottom>0?View.GONE:View.VISIBLE);
            v.setPadding(dp(horizontal), top, dp(horizontal), bottom+dp(8)); return insets;
        });
    }
    private void showChoices(String title, String[] values, int current, java.util.function.IntConsumer chosen) {
        LinearLayout sheet = column(); sheet.setPadding(dp(24), dp(24), dp(24), dp(24)); sheet.setBackground(Ui.shape(this, Ui.BG, 28, false));
        sheet.addView(Ui.text(this, title, 21, Ui.INK, true)); Ui.gap(sheet, 18);
        android.app.Dialog dialog = new android.app.Dialog(this);
        for (int i=0;i<values.length;i++) {final int index=i; LinearLayout option=row(); option.setPadding(dp(18),0,dp(16),0); option.setBackground(Ui.ripple(this,i==current?Ui.SOFT:Color.WHITE,16,false));
            option.addView(Ui.text(this,values[i],17,i==current?Ui.ACCENT:Ui.INK,true),new LinearLayout.LayoutParams(0,-2,1));
            if(i==current)option.addView(new Ui.Glyph(this,"check",Ui.ACCENT),new LinearLayout.LayoutParams(dp(20),dp(20)));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(58));p.bottomMargin=dp(8);sheet.addView(option,p);option.setOnClickListener(v->{chosen.accept(index);dialog.dismiss();});}
        dialog.setContentView(sheet); android.view.Window window=dialog.getWindow(); if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2);window.setDimAmount(.28f);} dialog.show(); if(window!=null)window.setLayout(-1,-2);
    }
    private Language source() { return Language.values()[from.getSelectedItemPosition()]; }
    private Language target() { return Language.values()[to.getSelectedItemPosition()]; }
    private Language textSource(){return inputLanguage==null?source():inputLanguage;}
    private Language textTarget(){return textSource()==source()?target():source();}
    private void refreshVoiceLabels() {
        if (voiceLabelA == null) return;
        voiceLabelA.setText("说" + source().label); voiceLabelB.setText("说" + target().label);
        languageA.setText(source().label); languageB.setText(target().label);
        if(inputLanguage==null||(inputLanguage!=source()&&inputLanguage!=target()))inputLanguage=source();
        originalLabel.setText("原文 · " + inputLanguage.label + "  ˅");
        settings.prefs.edit().putInt("language_from",from.getSelectedItemPosition()).putInt("language_to",to.getSelectedItemPosition()).apply();
        voiceA.setContentDescription("说" + source().label); voiceB.setContentDescription("说" + target().label);
        if (latest == null) direction.setText("译文 · " + target().label);
    }
    private void setBusy(boolean value, String message) {
        busy=value; input.setEnabled(!value);from.setEnabled(!value);to.setEnabled(!value);scene.setEnabled(!value);
        voiceMode.setEnabled(!value);textMode.setEnabled(!value);translateButton.setEnabled(!value);voiceA.setEnabled(!value);voiceB.setEnabled(!value);
        stopButton.setVisibility(value?View.VISIBLE:View.GONE);stopButton.setEnabled(true);
        stopButton.setText(recording?"结束录音并翻译":listening?"结束说话":"取消翻译");status.setText(message);
        setTextMode(typing,false);
        if(value)dockHint.setText(recording?"正在录音，点击上方按钮结束":listening?"正在听你说话，停顿后自动翻译":"AI 正在翻译，请稍候");
    }
    private GatewayClient.Config checkedConfig() {
        try { return settings.config(); }
        catch (Exception e) { if (!settings.hasApiKey()) showSettings(); else error(e); return null; }
    }
    private void translateText(String text, Language source, Language target) {
        if (busy) return;
        if (source == target) { toast("请选择两种不同的语言。"); return; }
        if (text.trim().isEmpty()) { toast("请先输入文字或说话。"); return; }
        if (text.length() > 4000) { toast("单次最多 4000 个字符，请分段翻译。"); return; }
        GatewayClient.Config config = checkedConfig(); if (config == null) return;
        final int id = ++operation; final String contextScene = scene.getSelectedItem().toString();
        List<TranslationRecord> context = new ArrayList<>(conversation);
        settings.prefs.edit().putString("scene", contextScene).apply();
        if (tts != null) tts.stop(); setBusy(true, "正在翻译…");
        translationTask = executor.submit(() -> {
            try { GatewayClient.Result result = gateway.translate(config, text.trim(), source, target, contextScene, context);
                runOnUiThread(() -> finishTranslation(id, result, source, target)); }
            catch (Exception e) { runOnUiThread(() -> failOperation(id, e)); }
        });
    }
    private void finishTranslation(int id, GatewayClient.Result result, Language source, Language target) {
        if (id != operation || isDestroyed()) return;
        recording = false; setBusy(false, "翻译完成"); input.setText(result.original);
        latest = new TranslationRecord(source, target, result.original, result.translated);
        inputLanguage=source;refreshVoiceLabels();
        conversation.add(latest); while (conversation.size() > 6) conversation.remove(0);
        if (settings.saveHistory()) history.add(latest);
        translation.setText(result.translated); direction.setText(source.label + " → " + target.label);
        favoriteButton.setText("收藏"); showResultState();
        if (settings.autoSpeak() && foreground) speak(result.translated, target);
    }
    private void failOperation(int id, Throwable e) {
        if (id != operation || isDestroyed()) return;
        recording = false; setBusy(false, "未完成 · 可调整后重试"); error(e);
    }
    private void cancelOperation() {
        ++operation; recording = false; listening = false;
        if (recognizer != null) recognizer.cancel(); if (recorder != null) recorder.cancel();
        if (translationTask != null) translationTask.cancel(true); gateway.cancel();
        setBusy(false, "已取消");
    }
    private void beginVoice(Language source, Language target) {
        if (busy) return;
        if (source == target) { toast("请选择两种不同的语言。"); return; }
        if (checkedConfig() == null) return;
        inputLanguage=source;refreshVoiceLabels();
        setTextMode(false, false);
        pendingSource = source; pendingTarget = target;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE); return;
        }
        if ("audio".equals(settings.inputMode())) beginAudio(source, target); else beginRecognition(source, target);
    }
    private void beginAudio(Language source, Language target) {
        GatewayClient.Config config = checkedConfig(); if (config == null) return;
        int id = ++operation; String selectedScene = scene.getSelectedItem().toString();
        List<TranslationRecord> context = new ArrayList<>(conversation);
        if (tts != null) tts.stop(); recorder = new WavRecorder(); recording = true;
        setBusy(true, "正在录音 · " + source.label + " · 最长 30 秒");
        try {
            recorder.start().whenComplete((wav, failure) -> runOnUiThread(() -> {
                if (id != operation || isDestroyed()) return;
                recording = false;
                if (failure != null) { failOperation(id, failure); return; }
                if (wav.length < 44 + 16000 / 2) { failOperation(id, new IllegalStateException("录音太短，请再说一次。")); return; }
                setBusy(true, "正在识别并翻译…");
                translationTask = executor.submit(() -> {
                    try { GatewayClient.Result result = gateway.translateAudio(config, Base64.encodeToString(wav, Base64.NO_WRAP), source, target, selectedScene, context);
                        runOnUiThread(() -> finishTranslation(id, result, source, target)); }
                    catch (Exception e) { runOnUiThread(() -> failOperation(id, e)); }
                });
            }));
        } catch (Exception e) { failOperation(id, e); }
    }
    private void beginRecognition(Language source, Language target) {
        if (tts != null) tts.stop(); int id = ++operation;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, source.code);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "请说" + source.label);
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            try { setBusy(true, "等待手机听写…"); startActivityForResult(intent, SPEECH_FALLBACK); }
            catch (Exception e) { setBusy(false, "手机未提供听写服务"); showSpeechHelp(); }
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        listening = true;
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) { if (id == operation) status.setText("请说" + source.label + "…"); }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float rms) {}
            public void onBufferReceived(byte[] buffer) {}
            public void onEndOfSpeech() { if (id == operation) status.setText("正在听写…"); }
            public void onEvent(int event, Bundle params) {}
            public void onPartialResults(Bundle results) {
                if (id != operation) return;
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts != null && !texts.isEmpty()) input.setText(texts.get(0));
            }
            public void onResults(Bundle results) {
                if (id != operation) return;
                listening = false;
                setBusy(false, "听写完成");
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts == null || texts.isEmpty()) { toast("没有听清，请再说一次。"); return; }
                input.setText(texts.get(0)); translateText(texts.get(0), source, target);
            }
            public void onError(int code) {
                if (id != operation) return;
                listening = false;
                setBusy(false, "听写未完成");
                if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) toast("没有听清，请靠近手机再说一次。");
                else if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) toast("手机听写服务正忙，请稍后再试。");
                else showSpeechHelp();
            }
        });
        setBusy(true, "正在启动手机听写…");
        try { recognizer.startListening(intent); }
        catch (Exception e) { listening = false; setBusy(false, "听写未启动"); showSpeechHelp(); }
    }
    private void showSpeechHelp() {
        new AlertDialog.Builder(this).setTitle("手机听写不可用")
            .setMessage("请检查手机的语音服务、网络和语言支持。也可使用键盘输入，或在设置中选择音频直传；音频直传需要模型及网关支持。")
            .setPositiveButton("知道了", null).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) beginVoice(pendingSource == null ? source() : pendingSource, pendingTarget == null ? target() : pendingTarget);
        else if (request == MICROPHONE) toast("未授予麦克风权限，可以继续使用文字翻译。");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SPEECH_FALLBACK) {
            setBusy(false, "准备就绪");
            if (result == RESULT_OK && data != null) {
                ArrayList<String> texts = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (texts != null && !texts.isEmpty()) { input.setText(texts.get(0)); translateText(texts.get(0), pendingSource == null ? source() : pendingSource, pendingTarget == null ? target() : pendingTarget); }
            }
        } else if (request == INSTALL_PERMISSION && getPackageManager().canRequestPackageInstalls()) installDownloaded();
    }
    private void speak(String value, Language language) {
        if (!ttsReady) { toast("手机朗读服务尚未就绪。"); return; }
        int availability = tts.setLanguage(language.locale());
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            new AlertDialog.Builder(this).setTitle("需要朗读语音包")
                .setMessage("手机尚未支持" + language.label + "朗读。可在系统的文字转语音设置中安装对应语音包；显示和复制译文仍可使用。")
                .setPositiveButton("打开系统设置", (d, w) -> { try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); } catch (Exception e) { toast("请在手机设置中搜索“文字转语音”。"); } })
                .setNegativeButton("关闭", null).show(); return;
        }
        if (tts.getVoices() != null) for (Voice voice : tts.getVoices()) {
            if (!voice.isNetworkConnectionRequired() && voice.getLocale().getLanguage().equals(language.locale().getLanguage())) { tts.setVoice(voice); break; }
        }
        tts.setSpeechRate(0.93f);
        if (tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "translation") == TextToSpeech.ERROR) toast("朗读未成功，请检查手机语音包。");
    }
    private void favoriteLatest() {
        if (latest == null) return;
        boolean exists = false; for (TranslationRecord record : history.load()) if (record.id.equals(latest.id)) { exists = true; break; }
        if (!exists) history.add(latest);
        history.toggleFavorite(latest.id); latest.favorite = !latest.favorite;
        favoriteButton.setText(latest.favorite ? "已收藏" : "收藏");
    }
    private void showLargeTranslation() {
        if(latest==null)return;LinearLayout root=column();root.setBackgroundColor(Ui.SOFT);applyInsets(root,24);android.app.Dialog dialog=Ui.page(this,root);root.addView(pageHeader(latest.target.label,dialog));
        ScrollView scroll=new ScrollView(this);LinearLayout content=column();content.setPadding(0,dp(28),0,dp(24));TextView display=Ui.text(this,latest.translated,42,Ui.INK,true);display.setLineSpacing(dp(12),1);display.setTextIsSelectable(true);content.addView(display);Ui.gap(content,28);content.addView(muted(latest.original,18));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(Ui.button(this,"朗读给对方听",true,v->speak(latest.translated,latest.target)));dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    private void showHistory(boolean favoritesOnly) {
        LinearLayout root=column();root.setBackgroundColor(Ui.BG);applyInsets(root,20);android.app.Dialog dialog=Ui.page(this,root);root.addView(pageHeader("翻译记录",dialog));
        LinearLayout tabs=row();Button all=button("全部",null),saved=button("收藏",null);tabs.addView(all,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams savedParams=new LinearLayout.LayoutParams(0,dp(44),1);savedParams.leftMargin=dp(10);tabs.addView(saved,savedParams);root.addView(tabs);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout list=column();list.setPadding(0,dp(18),0,dp(20));scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        java.util.function.Consumer<Boolean> render=only->{
            list.removeAllViews();all.setBackground(Ui.ripple(this,!only?Ui.ACCENT:Ui.LINE,14,false));all.setTextColor(!only?Color.WHITE:Ui.MUTED);saved.setBackground(Ui.ripple(this,only?Ui.ACCENT:Ui.LINE,14,false));saved.setTextColor(only?Color.WHITE:Ui.MUTED);
            int count=0;for(TranslationRecord record:history.load()){
                if(only&&!record.favorite)continue;count++;LinearLayout card=Ui.card(this,Color.WHITE);card.addView(muted(record.source.label+" → "+record.target.label+"  ·  "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new java.util.Date(record.time)),11));
                TextView original=Ui.text(this,record.original,14,Ui.MUTED,false);original.setPadding(0,dp(14),0,dp(10));card.addView(original);TextView translated=Ui.text(this,record.translated,21,Ui.INK,true);translated.setTextIsSelectable(true);translated.setLineSpacing(dp(5),1);card.addView(translated);Ui.gap(card,12);
                LinearLayout actions=row();Button read=Ui.action(this,"volume","朗读",v->speak(record.translated,record.target));Button copy=Ui.action(this,"copy","复制",v->copy(record.translated));Button star=Ui.action(this,"heart",record.favorite?"已收藏":"收藏",null);
                star.setOnClickListener(v->{history.toggleFavorite(record.id);record.favorite=!record.favorite;star.setText(record.favorite?"已收藏":"收藏");if(latest!=null&&latest.id.equals(record.id)){latest.favorite=record.favorite;favoriteButton.setText(record.favorite?"已收藏":"收藏");}});
                for(Button item:new Button[]{read,copy,star})actions.addView(item,new LinearLayout.LayoutParams(0,dp(54),1));card.addView(actions);list.addView(card);
            }
            if(count==0){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(72),dp(20),dp(30));LinearLayout icon=row();icon.setGravity(Gravity.CENTER);icon.setBackground(Ui.shape(this,Ui.SOFT,26,false));icon.addView(new Ui.Glyph(this,only?"heart":"history",Ui.ACCENT),new LinearLayout.LayoutParams(dp(32),dp(32)));empty.addView(icon,new LinearLayout.LayoutParams(dp(76),dp(76)));Ui.gap(empty,24);empty.addView(Ui.text(this,only?"收藏常用的表达":"还没有翻译记录",20,Ui.INK,true));Ui.gap(empty,10);empty.addView(muted(only?"点译文下的收藏，下次随时取用":"开始一次交流，好好保存每一句",12));list.addView(empty);}
        };
        all.setOnClickListener(v->render.accept(false));saved.setOnClickListener(v->render.accept(true));render.accept(favoritesOnly);
        root.addView(button("清除普通记录",v->new AlertDialog.Builder(this).setTitle("清除翻译记录？").setMessage("普通记录会清除，收藏继续保留。")
            .setPositiveButton("清除",(d,w)->{history.clear(false);render.accept(false);}).setNegativeButton("保留",null).show()));dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    private void showSettings() {
        LinearLayout root=column(); root.setBackgroundColor(Ui.BG); applyInsets(root,20);
        android.app.Dialog dialog=Ui.page(this,root); root.addView(pageHeader("设置",dialog));
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout form=column();form.setPadding(0,dp(14),0,dp(12));scroll.addView(form);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout connection=Ui.card(this,Color.WHITE); connection.addView(Ui.text(this,"连接你的 AI",23,Ui.INK,true));
        TextView hint=muted("接口和默认模型已准备好，只需填写密钥。",12);hint.setPadding(0,dp(8),0,dp(20));connection.addView(hint);
        EditText key=field(connection,"API Key","",true);key.setHint(settings.hasApiKey()?"已保存 · 留空即可保留":"粘贴你的 API Key");
        TextView privateHint=muted("密钥加密保存在这台手机上",11);privateHint.setPadding(0,dp(10),0,0);connection.addView(privateHint);form.addView(connection);
        LinearLayout preferences=Ui.card(this,Color.WHITE); preferences.addView(Ui.text(this,"交流偏好",16,Ui.INK,true)); Ui.gap(preferences,10);
        android.widget.Switch autoplay=toggle(preferences,"自动朗读译文","翻译完成后，直接播放给对方听",settings.autoSpeak());
        preferences.addView(Ui.line(this)); android.widget.Switch save=toggle(preferences,"保存翻译记录","仅保存在手机，可随时清除",settings.saveHistory());form.addView(preferences);
        LinearLayout version=Ui.card(this,Color.WHITE); LinearLayout versionRow=row(); LinearLayout versionText=column(); versionText.addView(Ui.text(this,"应用更新",16,Ui.INK,true)); TextView versionLabel=muted("当前版本 "+BuildConfig.VERSION_NAME,12);versionLabel.setPadding(0,dp(8),0,0);versionText.addView(versionLabel);versionRow.addView(versionText,new LinearLayout.LayoutParams(0,-2,1));
        Button update=button("检查更新",v->checkUpdate(true));versionRow.addView(update,new LinearLayout.LayoutParams(-2,dp(42)));version.addView(versionRow);android.widget.Switch autoUpdate=toggle(version,"自动检查更新","每天首次打开时检查一次",settings.autoUpdate());form.addView(version);
        LinearLayout advanced=Ui.card(this,Color.WHITE); LinearLayout advancedHeader=row(); advancedHeader.addView(Ui.text(this,"高级设置",16,Ui.INK,true),new LinearLayout.LayoutParams(0,-2,1)); Ui.Glyph chevron=new Ui.Glyph(this,"down",Ui.MUTED);advancedHeader.addView(chevron,new LinearLayout.LayoutParams(dp(18),dp(18)));advanced.addView(advancedHeader);
        LinearLayout details=column();details.setVisibility(View.GONE);Ui.gap(details,20);
        EditText base=field(details,"API 地址",settings.base(),false);base.setHint("接口地址");
        EditText model=field(details,"翻译模型",settings.model(),false);
        Button models=button("选择其他模型",v->{
            try {String token=key.getText().toString().trim();if(token.isEmpty())token=settings.secrets.get("api_key");if(token.isEmpty())throw new IllegalStateException("请先填写 API Key。");
                GatewayClient.Config config=new GatewayClient.Config(base.getText().toString(),token,model.getText().toString());toast("正在读取可用模型…");
                executor.submit(()->{try{List<String> names=modelGateway.models(config);runOnUiThread(()->{if(!isDestroyed()&&dialog.isShowing())chooseModel(names,model);});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())error(e);});}});
            }catch(Exception e){error(e);}
        });details.addView(models);Ui.gap(details,16);details.addView(Ui.text(this,"语音输入方式",13,Ui.MUTED,true));Ui.gap(details,8);
        Spinner mode=spinner(new String[]{"手机听写", "AI 音频识别（实验）"});mode.setSelection("audio".equals(settings.inputMode())?1:0);details.addView(mode,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView audioHint=muted("AI 音频识别需要模型及网关支持音频输入。",11);audioHint.setPadding(0,dp(8),0,dp(18));details.addView(audioHint);
        EditText repository=field(details,"更新仓库",settings.repository(),false);EditText githubToken=field(details,"私有仓库令牌（公开仓库无需填写）","",true);
        details.addView(button("清除保存的密钥",v->new AlertDialog.Builder(this).setTitle("清除密钥？").setMessage("清除这台手机保存的 API Key 和 GitHub 令牌。")
            .setPositiveButton("清除",(d,w)->{try{settings.secrets.put("api_key","");settings.secrets.put("github_token","");key.setText("");githubToken.setText("");key.setHint("粘贴你的 API Key");updateConfiguredState();toast("已清除密钥");}catch(Exception e){error(e);}}).setNegativeButton("保留",null).show()));
        advanced.addView(details);advancedHeader.setPadding(0,dp(4),0,dp(4));advancedHeader.setOnClickListener(v->{boolean expanded=details.getVisibility()==View.VISIBLE;details.setVisibility(expanded?View.GONE:View.VISIBLE);chevron.setRotation(expanded?0:180);});advancedHeader.setContentDescription("展开或收起高级设置");form.addView(advanced);
        LinearLayout footer=column();footer.setPadding(0,dp(8),0,dp(6));Button commit=Ui.button(this,"保存并开始翻译",true,v->{
            try {String endpoint=UrlPolicy.apiBase(base.getText().toString().trim());String repo=repository.getText().toString().trim();if(!repo.isEmpty())repo=UrlPolicy.repository(repo);
                String chosenModel=model.getText().toString().trim();if(chosenModel.isEmpty())throw new IllegalStateException("请填写模型名称。");
                String apiKey=key.getText().toString().trim(),gitKey=githubToken.getText().toString().trim();if(apiKey.isEmpty()&&!settings.hasApiKey()){key.requestFocus();throw new IllegalStateException("填入 API Key 后即可开始。");}
                if(!apiKey.isEmpty())settings.secrets.put("api_key",apiKey);if(!gitKey.isEmpty())settings.secrets.put("github_token",gitKey);
                settings.prefs.edit().putString("api_base",endpoint).putString("model",chosenModel).putString("repository",repo).putString("input_mode",mode.getSelectedItemPosition()==1?"audio":"system")
                    .putBoolean("auto_speak",autoplay.isChecked()).putBoolean("save_history",save.isChecked()).putBoolean("auto_update",autoUpdate.isChecked()).apply();
                dialog.dismiss();updateConfiguredState();status.setText("已配置 · "+chosenModel);
            }catch(Exception e){error(e);}
        });footer.addView(commit);root.addView(footer);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    private View pageHeader(String title,android.app.Dialog dialog){
        LinearLayout header=row();header.setPadding(0,dp(10),0,dp(12));View close=Ui.iconButton(this,"back","",Ui.INK,false,v->dialog.dismiss());close.setContentDescription("返回");header.addView(close,new LinearLayout.LayoutParams(dp(40),dp(48)));TextView name=Ui.text(this,title,23,Ui.INK,true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(6);header.addView(name,p);return header;
    }
    private android.widget.Switch toggle(LinearLayout parent,String title,String description,boolean selected){
        LinearLayout line=row();line.setPadding(0,dp(16),0,dp(16));LinearLayout copy=column();copy.addView(Ui.text(this,title,14,Ui.INK,true));TextView help=muted(description,11);help.setPadding(0,dp(6),dp(8),0);copy.addView(help);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        android.widget.Switch control=new android.widget.Switch(this);control.setChecked(selected);control.setShowText(false);control.setContentDescription(title);control.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        android.content.res.ColorStateList thumb=new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.ACCENT,Color.rgb(164,170,190)});control.setThumbTintList(thumb);control.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Color.rgb(208,200,253),Ui.LINE}));line.addView(control);parent.addView(line);return control;
    }
    private void chooseModel(List<String> names, EditText target) {
        if (names.isEmpty()) { toast("这个密钥没有可用模型。"); return; }
        LinearLayout content = column(); content.setPadding(dp(16), 0, dp(16), 0);
        EditText filter = new EditText(this); filter.setHint("搜索模型名称"); content.addView(filter);
        android.widget.ListView list = new android.widget.ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names); list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(330)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择模型（文字翻译）").setView(content).setNegativeButton("关闭", null).create();
        list.setOnItemClickListener((p, v, position, id) -> { target.setText(adapter.getItem(position)); dialog.dismiss(); });
        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.getFilter().filter(s); }
            public void afterTextChanged(android.text.Editable value) {}
        }); dialog.show();
    }
    private void checkUpdate(boolean manual) {
        if (updateBusy) { if (manual) toast("正在检查或下载更新。"); return; }
        String repo = settings.repository();
        if (repo.isEmpty()) { if (manual) toast("请先保存 GitHub 仓库地址。"); return; }
        final String token;
        try { token = settings.secrets.get("github_token"); }
        catch (Exception e) { if (manual) error(e); return; }
        updateBusy = true;
        executor.submit(() -> {
            try {
                UpdateClient.Release release = updater.check(repo, token);
                runOnUiThread(() -> {
                    updateBusy = false; if (isDestroyed()) return;
                    if (release == null || !release.manifest.newerThan(BuildConfig.VERSION_CODE)) { if (manual) toast(release == null ? "暂时没有正式发布。" : "已是最新版本。"); return; }
                    if (!foreground) return;
                    new AlertDialog.Builder(this).setTitle("发现新版 " + release.manifest.versionName)
                        .setMessage(release.manifest.notes + "\n\n下载约 " + Math.max(1, release.manifest.size / 1024 / 1024) + " MB。安装后保留设置和记录。")
                        .setPositiveButton("下载更新", (d, w) -> downloadUpdate(release, token)).setNegativeButton("稍后", null).show();
                });
            } catch (Exception e) { runOnUiThread(() -> { updateBusy = false; if (manual && !isDestroyed()) error(e); }); }
        });
    }
    private void downloadUpdate(UpdateClient.Release release, String token) {
        if (updateBusy) return; updateBusy = true;
        TextView progress = text("正在下载…", 18); progress.setPadding(dp(22), dp(22), dp(22), dp(22));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("下载更新").setView(progress).setCancelable(false).setNegativeButton("取消", (d, w) -> updater.cancel()).show();
        executor.submit(() -> {
            try {
                File apk = updater.download(this, release, token, percentage -> runOnUiThread(() -> progress.setText("下载 " + percentage + "% · 完成后验证签名")));
                runOnUiThread(() -> { updateBusy = false; dialog.dismiss(); if (!isDestroyed()) { pendingInstall = apk; installDownloaded(); } });
            } catch (Exception e) { runOnUiThread(() -> { updateBusy = false; dialog.dismiss(); if (!isDestroyed()) error(e); }); }
        });
    }
    private void installDownloaded() {
        if (pendingInstall == null || !pendingInstall.isFile()) { toast("安装文件已失效，请重新下载。"); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("允许安装应用更新")
                .setMessage("Android 需要你在系统页面允许随译安装应用。返回后将打开系统安装确认。")
                .setPositiveButton("打开系统设置", (d, w) -> startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION))
                .setNegativeButton("稍后", null).show(); return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", pendingInstall);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("APK", uri)); startActivity(intent);
        } catch (Exception e) { error(new IllegalStateException("无法打开系统安装器，请检查手机安装限制。")); }
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true;
        long last = settings.prefs.getLong("last_update_check", 0), now = System.currentTimeMillis();
        if (settings.autoUpdate() && !settings.repository().isEmpty() && now - last > 24L * 60 * 60 * 1000) {
            settings.prefs.edit().putLong("last_update_check", now).apply(); checkUpdate(false);
        }
    }
    @Override protected void onPause() {
        foreground = false; if (tts != null) tts.stop();
        // Keep network translations running briefly; microphone use ends when the app leaves the foreground.
        if (recording || listening) cancelOperation();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("typing", typing);
        state.putInt("input_language",textSource().ordinal());
        if(pendingSource!=null&&pendingTarget!=null){state.putInt("pending_source",pendingSource.ordinal());state.putInt("pending_target",pendingTarget.ordinal());}
        state.putInt("from", from.getSelectedItemPosition()); state.putInt("to", to.getSelectedItemPosition()); state.putString("input", input.getText().toString());
        try {
            if (latest != null) state.putString("latest", latest.json().toString());
            org.json.JSONArray turns = new org.json.JSONArray(); for (TranslationRecord record : conversation) turns.put(record.json());
            state.putString("conversation", turns.toString());
        } catch (Exception ignored) {}
        if (pendingInstall != null) state.putString("pending_install", pendingInstall.getAbsolutePath());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        ++operation; if (recognizer != null) recognizer.destroy(); if (recorder != null) recorder.cancel();
        gateway.cancel(); modelGateway.cancel(); updater.cancel(); executor.shutdownNow();
        if (tts != null) { tts.stop(); tts.shutdown(); } super.onDestroy();
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() { return Ui.card(this, Color.WHITE); }
    private TextView text(String value, float size) { return Ui.text(this, value, size, INK, false); }
    private TextView muted(String value, float size) { return Ui.text(this, value, size, Ui.MUTED, false); }
    private Button button(String value, View.OnClickListener action) { return Ui.button(this, value, false, action); }
    private Spinner spinner(String[] items) {
        Spinner view = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); view.setAdapter(adapter);view.setBackground(Ui.shape(this,Ui.BG,14,true));view.setPadding(dp(10),0,dp(10),0);return view;
    }
    private EditText field(LinearLayout form, String hint, String value, boolean secret) {
        TextView label=Ui.text(this,hint,12,Ui.MUTED,true);label.setPadding(0,dp(10),0,dp(10));form.addView(label);
        EditText edit=new EditText(this);edit.setText(value);edit.setSingleLine(true);edit.setTextSize(15);edit.setTextColor(Ui.INK);edit.setHintTextColor(Ui.MUTED);
        edit.setPadding(dp(14),0,dp(14),0);edit.setBackground(Ui.shape(this,Ui.BG,14,true));
        edit.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        edit.setContentDescription(hint);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);form.addView(edit,new LinearLayout.LayoutParams(-1,dp(54)));return edit;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void copy(String value) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("译文", value)); toast("已复制译文"); }
    private void toast(String value) { if (!isDestroyed()) Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void error(Throwable failure) {
        if (isDestroyed()) return;
        Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if (cause instanceof java.net.UnknownHostException) message = "无法连接服务器，请检查网络和 API 地址。";
        else if (cause instanceof java.net.SocketTimeoutException) message = "请求超时，请检查网络或换用响应更快的模型。";
        if (message == null || message.isEmpty()) message = "操作未成功，请检查设置后重试。";
        new AlertDialog.Builder(this).setTitle("未完成").setMessage(GatewayClient.redact(message, null)).setPositiveButton("关闭", null).show();
    }
}
