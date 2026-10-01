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
    private static final int INK = Color.rgb(26, 48, 52), TEAL = Color.rgb(8, 127, 114);
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
    private Button translateButton, voiceA, voiceB, stopButton, favoriteButton;
    private SpeechRecognizer recognizer;
    private WavRecorder recorder;
    private TextToSpeech tts;
    private boolean ttsReady, busy, recording, listening, foreground;
    private int operation;
    private Language pendingSource, pendingTarget;
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
        tts = new TextToSpeech(this, result -> runOnUiThread(() -> ttsReady = result == TextToSpeech.SUCCESS));
    }
    private void createScreen() {
        LinearLayout root = column(); root.setBackgroundColor(Color.rgb(245, 247, 248));
        root.setPadding(dp(18), dp(8), dp(18), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            view.setPadding(dp(18), dp(8) + top, dp(8) + dp(10), dp(8) + bottom);
            return insets;
        });
        LinearLayout heading = row();
        TextView title = text("随译", 29); title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(55), 1));
        heading.addView(button("记录", v -> showHistory(false)));
        heading.addView(button("设置", v -> { if (!busy) showSettings(); else toast("请先结束当前输入或翻译。"); }));
        root.addView(heading); root.addView(text("中文 · English · ไทย", 13));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(0, dp(18), 0, dp(12)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout languages = row();
        String[] labels = {"中文", "English", "ไทย"};
        from = spinner(labels); to = spinner(labels); to.setSelection(2);
        languages.addView(from, new LinearLayout.LayoutParams(0, dp(54), 1));
        languages.addView(button("⇄", v -> {
            if (busy) return;
            int old = from.getSelectedItemPosition(); from.setSelection(to.getSelectedItemPosition()); to.setSelection(old);
            refreshVoiceLabels();
        }));
        languages.addView(to, new LinearLayout.LayoutParams(0, dp(54), 1)); body.addView(languages);
        android.widget.AdapterView.OnItemSelectedListener selected = new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { refreshVoiceLabels(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };
        from.setOnItemSelectedListener(selected); to.setOnItemSelectedListener(selected);
        scene = spinner(new String[]{"日常交流", "餐厅点餐", "交通问路", "酒店住宿", "购物议价"});
        for (int i = 0; i < scene.getCount(); i++) if (scene.getItemAtPosition(i).equals(settings.scene())) scene.setSelection(i);
        body.addView(scene);

        LinearLayout originalCard = card(); originalCard.addView(text("原文", 13));
        input = new EditText(this); input.setTextSize(21); input.setTextColor(INK);
        input.setHint("输入文字，或点下方按钮说话"); input.setGravity(Gravity.TOP);
        input.setMinLines(3); input.setMaxLines(7);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        originalCard.addView(input); body.addView(originalCard);
        LinearLayout voices = row();
        voiceA = button("说中文", v -> beginVoice(source(), target()));
        voiceB = button("说ไทย", v -> beginVoice(target(), source()));
        voices.addView(voiceA, new LinearLayout.LayoutParams(0, dp(58), 1));
        voices.addView(voiceB, new LinearLayout.LayoutParams(0, dp(58), 1)); body.addView(voices);
        translateButton = button("翻译文字", v -> translateText(input.getText().toString(), source(), target()));
        translateButton.setTextColor(Color.WHITE); translateButton.setBackground(tinted(TEAL)); body.addView(translateButton);
        stopButton = button("取消", v -> { if (recording) recorder.stop(); else cancelOperation(); });
        stopButton.setVisibility(View.GONE); body.addView(stopButton);
        status = text("准备就绪 · 首次使用请配置 API", 13); status.setPadding(0, dp(14), 0, dp(14)); body.addView(status);

        LinearLayout translatedCard = card(); direction = text("译文", 13); translatedCard.addView(direction);
        translation = text("译文会显示在这里", 25); translation.setTextIsSelectable(true);
        translation.setPadding(0, dp(15), 0, dp(22)); translatedCard.addView(translation);
        LinearLayout actions = row();
        actions.addView(button("朗读", v -> { if (latest != null) speak(latest.translated, latest.target); }));
        actions.addView(button("复制", v -> { if (latest != null) copy(latest.translated); }));
        favoriteButton = button("收藏", v -> favoriteLatest()); actions.addView(favoriteButton);
        actions.addView(button("大字", v -> showLargeTranslation())); translatedCard.addView(actions);
        body.addView(translatedCard);
        body.addView(button("开启新对话", v -> {
            if (busy) cancelOperation(); conversation.clear(); input.setText(""); latest = null;
            translation.setText("译文会显示在这里"); direction.setText("译文"); favoriteButton.setText("收藏");
            status.setText("已开启新对话"); if (tts != null) tts.stop();
        }));
        body.addView(text("短句交流更清楚。原文、译文和最近 6 次对话会发送给你配置的模型接口。", 12));
        setContentView(root);
    }
    private Language source() { return Language.values()[from.getSelectedItemPosition()]; }
    private Language target() { return Language.values()[to.getSelectedItemPosition()]; }
    private void refreshVoiceLabels() {
        if (voiceA != null) { voiceA.setText("说" + source().label); voiceB.setText("说" + target().label); }
    }
    private void setBusy(boolean value, String message) {
        busy = value; input.setEnabled(!value); from.setEnabled(!value); to.setEnabled(!value); scene.setEnabled(!value);
        translateButton.setEnabled(!value); voiceA.setEnabled(!value); voiceB.setEnabled(!value);
        stopButton.setVisibility(value ? View.VISIBLE : View.GONE);
        stopButton.setText(recording ? "结束录音并翻译" : "取消"); status.setText(message);
    }
    private GatewayClient.Config checkedConfig() {
        try { return settings.config(); }
        catch (Exception e) { error(e); return null; }
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
        conversation.add(latest); while (conversation.size() > 6) conversation.remove(0);
        if (settings.saveHistory()) history.add(latest);
        translation.setText(result.translated); direction.setText(source.label + " → " + target.label);
        favoriteButton.setText("收藏");
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
        if (request == MICROPHONE && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) beginVoice(pendingSource, pendingTarget);
        else if (request == MICROPHONE) toast("未授予麦克风权限，可以继续使用文字翻译。");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SPEECH_FALLBACK) {
            setBusy(false, "准备就绪");
            if (result == RESULT_OK && data != null) {
                ArrayList<String> texts = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (texts != null && !texts.isEmpty()) { input.setText(texts.get(0)); translateText(texts.get(0), pendingSource, pendingTarget); }
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
        if (latest == null) return;
        TextView display = text(latest.translated, 38); display.setPadding(dp(24), dp(30), dp(24), dp(30)); display.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(display);
        new AlertDialog.Builder(this).setTitle(latest.target.label).setView(scroll).setPositiveButton("关闭", null).show();
    }
    private void showHistory(boolean favoritesOnly) {
        LinearLayout list = column(); list.setPadding(dp(16), 0, dp(16), dp(12));
        list.addView(button(favoritesOnly ? "查看全部记录" : "只看收藏", v -> showHistory(!favoritesOnly)));
        int count = 0;
        for (TranslationRecord record : history.load()) {
            if (favoritesOnly && !record.favorite) continue;
            count++; LinearLayout card = card();
            card.addView(text(record.source.label + " → " + record.target.label + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new java.util.Date(record.time)), 12));
            TextView original = text(record.original, 17); original.setTextIsSelectable(true); card.addView(original);
            TextView translated = text(record.translated, 21); translated.setTextIsSelectable(true); card.addView(translated);
            LinearLayout actions = row(); actions.addView(button("朗读", v -> speak(record.translated, record.target)));
            actions.addView(button("复制", v -> copy(record.translated)));
            Button star = button(record.favorite ? "已收藏" : "收藏", null);
            star.setOnClickListener(v -> { history.toggleFavorite(record.id); record.favorite = !record.favorite; star.setText(record.favorite ? "已收藏" : "收藏"); if (latest != null && latest.id.equals(record.id)) { latest.favorite = record.favorite; favoriteButton.setText(record.favorite ? "已收藏" : "收藏"); } });
            actions.addView(star); card.addView(actions); list.addView(card);
        }
        if (count == 0) list.addView(text("还没有记录。", 18));
        ScrollView scroll = new ScrollView(this); scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(favoritesOnly ? "收藏" : "本机记录").setView(scroll)
            .setPositiveButton("关闭", null).setNeutralButton("清除普通记录", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
            .setMessage("清除本机普通记录？收藏会保留。")
            .setPositiveButton("清除", (x, w) -> { history.clear(false); dialog.dismiss(); })
            .setNegativeButton("取消", null).show())); dialog.show();
    }
    private void showSettings() {
        LinearLayout form = column(); form.setPadding(dp(20), 0, dp(20), dp(16));
        form.addView(text("模型接口", 20));
        EditText base = field(form, "HTTPS API 地址（含 /v1）", settings.base(), false);
        EditText key = field(form, "API 密钥：留空保留已存密钥", "", true);
        EditText model = field(form, "翻译模型名称", settings.model(), false);
        form.addView(button("读取模型列表", v -> {
            try {
                String token = key.getText().toString().trim(); if (token.isEmpty()) token = settings.secrets.get("api_key");
                if (token.isEmpty()) throw new IllegalStateException("请填写 API 密钥。");
                GatewayClient.Config config = new GatewayClient.Config(base.getText().toString(), token, model.getText().toString());
                toast("正在读取模型列表…");
                executor.submit(() -> { try { List<String> names = modelGateway.models(config);
                    runOnUiThread(() -> { if (!isDestroyed()) chooseModel(names, model); }); }
                    catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) error(e); }); } });
            } catch (Exception e) { error(e); }
        }));
        form.addView(text("语音输入", 20));
        Spinner mode = spinner(new String[]{"手机听写（默认）", "音频直传（实验）"}); mode.setSelection("audio".equals(settings.inputMode()) ? 1 : 0); form.addView(mode);
        form.addView(text("音频直传使用 input_audio / WAV 请求，图片多模态不代表支持语音。手机听写和朗读由系统语音服务提供，语言支持取决于手机。", 12));
        CheckBox autoplay = check(form, "翻译后自动朗读", settings.autoSpeak());
        CheckBox save = check(form, "保存本机翻译记录", settings.saveHistory());
        form.addView(text("手机更新", 20));
        EditText repository = field(form, "GitHub 仓库：用户名/仓库名", settings.repository(), false);
        EditText githubToken = field(form, "私有仓库读取令牌：留空保留", "", true);
        form.addView(text("公开仓库无需令牌。私有仓库仅需该仓库 Contents 读取权限。", 12));
        CheckBox autoUpdate = check(form, "每天首次打开时检查更新", settings.autoUpdate());
        form.addView(button("检查更新（使用已保存设置）", v -> checkUpdate(true)));
        form.addView(text("版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）", 13));
        form.addView(button("清除已存密钥及 GitHub 令牌", v -> new AlertDialog.Builder(this).setMessage("清除手机保存的 API 密钥和 GitHub 令牌？")
            .setPositiveButton("清除", (d, w) -> { try { settings.secrets.put("api_key", ""); settings.secrets.put("github_token", ""); key.setText(""); githubToken.setText(""); toast("已清除"); } catch (Exception e) { error(e); } })
            .setNegativeButton("取消", null).show()));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("设置").setView(scroll).setPositiveButton("保存", null).setNegativeButton("关闭", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String endpoint = base.getText().toString().trim(); if (!endpoint.isEmpty()) endpoint = UrlPolicy.apiBase(endpoint);
                String repo = repository.getText().toString().trim(); if (!repo.isEmpty()) repo = UrlPolicy.repository(repo);
                String chosenModel = model.getText().toString().trim(); if (chosenModel.isEmpty()) throw new IllegalStateException("请填写模型名称。");
                String apiKey = key.getText().toString().trim(), gitKey = githubToken.getText().toString().trim();
                if (!apiKey.isEmpty()) settings.secrets.put("api_key", apiKey);
                if (!gitKey.isEmpty()) settings.secrets.put("github_token", gitKey);
                settings.prefs.edit().putString("api_base", endpoint).putString("model", chosenModel).putString("repository", repo)
                    .putString("input_mode", mode.getSelectedItemPosition() == 1 ? "audio" : "system")
                    .putBoolean("auto_speak", autoplay.isChecked()).putBoolean("save_history", save.isChecked()).putBoolean("auto_update", autoUpdate.isChecked()).apply();
                dialog.dismiss(); status.setText("设置已保存");
            } catch (Exception e) { error(e); }
        })); dialog.show();
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
    private LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(16), dp(16), dp(16), dp(12)); view.setBackground(tinted(Color.WHITE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(10), 0, dp(12)); view.setLayoutParams(params); return view;
    }
    private TextView text(String value, float size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(INK); return view; }
    private Button button(String value, View.OnClickListener action) { Button view = new Button(this); view.setText(value); view.setTextColor(TEAL); view.setAllCaps(false); view.setMinWidth(0); view.setPadding(dp(12), 0, dp(12), 0); if (action != null) view.setOnClickListener(action); return view; }
    private Spinner spinner(String[] items) {
        Spinner view = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); view.setAdapter(adapter); return view;
    }
    private EditText field(LinearLayout form, String hint, String value, boolean secret) {
        form.addView(text(hint, 13)); EditText edit = new EditText(this); edit.setText(value); edit.setSingleLine(true); edit.setTextSize(16);
        edit.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); form.addView(edit); return edit;
    }
    private CheckBox check(LinearLayout form, String label, boolean checked) { CheckBox box = new CheckBox(this); box.setText(label); box.setChecked(checked); form.addView(box); return box; }
    private GradientDrawable tinted(int color) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(17)); return drawable; }
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
