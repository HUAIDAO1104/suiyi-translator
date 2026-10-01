package app.suiyi.translate;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GatewayClient {
    public static final class Config {
        public final String base, key, model;
        public Config(String base, String key, String model) { this.base = UrlPolicy.apiBase(base); this.key = key; this.model = model; }
    }
    public static final class Result {
        public final String original, translated;
        public Result(String original, String translated) { this.original = original; this.translated = translated; }
    }
    private volatile HttpURLConnection active;
    public void cancel() { HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    public List<String> models(Config config) throws Exception {
        JSONObject response = request(config, "/models", null);
        JSONArray items = response.optJSONArray("data");
        if (items == null) throw new IllegalStateException("模型列表格式不兼容：需要 data 数组。");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            String id = items.getJSONObject(i).optString("id");
            if (!id.isEmpty() && !names.contains(id)) names.add(id);
        }
        Collections.sort(names); return names;
    }
    public Result translate(Config config, String text, Language source, Language target, String scene, List<TranslationRecord> context) throws Exception {
        JSONObject body = payload(config.model, source, target, scene, context, text, null);
        String translated = responseText(request(config, "/chat/completions", body));
        if (translated.trim().isEmpty()) throw new IllegalStateException("模型没有返回译文，请重试或切换模型。");
        return new Result(text, translated.trim());
    }
    public Result translateAudio(Config config, String wavBase64, Language source, Language target, String scene, List<TranslationRecord> context) throws Exception {
        JSONObject body = payload(config.model, source, target, scene, context, null, wavBase64);
        return parseAudioResult(responseText(request(config, "/chat/completions", body)));
    }
    static JSONObject payload(String model, Language source, Language target, String scene, List<TranslationRecord> context, String text, String wavBase64) throws Exception {
        boolean audio = wavBase64 != null;
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system")
            .put("content", TranslationPrompts.system(source, target, scene, audio, context)));
        Object content = text;
        if (audio) content = new JSONArray()
            .put(new JSONObject().put("type", "text").put("text", "Transcribe and translate this recording according to the system instruction."))
            .put(new JSONObject().put("type", "input_audio")
                .put("input_audio", new JSONObject().put("data", wavBase64).put("format", "wav")));
        messages.put(new JSONObject().put("role", "user").put("content", content));
        return new JSONObject().put("model", model).put("messages", messages).put("max_tokens", 1200).put("stream", false);
    }
    static Result parseAudioResult(String content) throws Exception {
        String clean = content.trim();
        if (clean.startsWith("```")) {
            int firstLine = clean.indexOf('\n'), lastFence = clean.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) clean = clean.substring(firstLine + 1, lastFence).trim();
        }
        JSONObject object;
        try { object = new JSONObject(clean); }
        catch (Exception e) { throw new IllegalStateException("音频结果格式不兼容，请切换模型或使用手机听写。"); }
        Object original = object.opt("source_text"), translated = object.opt("translation");
        if (!(original instanceof String) || !(translated instanceof String)) throw new IllegalStateException("音频结果缺少原文或译文，请切换模型或使用手机听写。");
        if (((String) original).trim().isEmpty() || ((String) translated).trim().isEmpty()) throw new IllegalStateException("没有听清这段录音，请靠近手机重新说一次。");
        return new Result(((String) original).trim(), ((String) translated).trim());
    }
    static String responseText(JSONObject response) throws Exception {
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("接口响应不兼容：没有 choices。");
        JSONObject choice = choices.getJSONObject(0);
        if ("length".equals(choice.optString("finish_reason"))) throw new IllegalStateException("译文被长度限制截断，请缩短句子后重试。");
        JSONObject message = choice.optJSONObject("message");
        if (message == null) throw new IllegalStateException("接口响应不兼容：没有 message。");
        Object content = message.opt("content");
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder out = new StringBuilder(); JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) out.append(part.optString("text"));
            }
            if (out.length() > 0) return out.toString();
        }
        String refusal = message.optString("refusal");
        throw new IllegalStateException(refusal.isEmpty() ? "模型没有返回文字。" : "模型拒绝了这次翻译，请调整表达或切换模型。");
    }
    private JSONObject request(Config config, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(config.base + path).openConnection();
        active = connection;
        try {
            connection.setConnectTimeout(15000); connection.setReadTimeout(90000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + config.key);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = input == null ? "" : new String(readBounded(input, 2 * 1024 * 1024), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                String detail = "";
                try { Object error = new JSONObject(text).opt("error");
                    detail = error instanceof JSONObject ? ((JSONObject) error).optString("message") : String.valueOf(error); }
                catch (Exception ignored) {}
                detail = redact(detail, config.key);
                if (status == 401 || status == 403) throw new IllegalStateException("API 认证或模型权限不足，请检查密钥、分组和模型名称。");
                if (status == 429) throw new IllegalStateException("接口限流或额度不足，请稍后重试并检查平台额度。");
                if (status >= 300 && status < 400) throw new IllegalStateException("接口发生跳转。为保护密钥，请填写最终的 HTTPS 接口地址。");
                throw new IllegalStateException("请求失败（HTTP " + status + "）" + (detail.isEmpty() || detail.equals("null") ? "" : "：" + detail));
            }
            return new JSONObject(text);
        } finally {
            connection.disconnect(); if (active == connection) active = null;
        }
    }
    static byte[] readBounded(InputStream input, int limit) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IllegalStateException("接口返回的数据过大。");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    static String redact(String value, String secret) {
        String clean = value == null ? "" : value;
        if (secret != null && !secret.isEmpty()) clean = clean.replace(secret, "[密钥已隐藏]");
        clean = clean.replaceAll("sk-[A-Za-z0-9_-]{8,}", "[密钥已隐藏]");
        return clean.length() > 250 ? clean.substring(0, 250) : clean;
    }
}
