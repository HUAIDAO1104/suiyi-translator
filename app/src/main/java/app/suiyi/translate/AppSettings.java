package app.suiyi.translate;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    public final SharedPreferences prefs;
    public final SecretStore secrets;
    public AppSettings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        secrets = new SecretStore(context);
    }
    public String base() { return prefs.getString("api_base", ""); }
    public String model() { return prefs.getString("model", "qwen3-max"); }
    public String repository() { return prefs.getString("repository", BuildConfig.UPDATE_REPOSITORY); }
    public String inputMode() { return prefs.getString("input_mode", "system"); }
    public String scene() { return prefs.getString("scene", "日常交流"); }
    public boolean autoSpeak() { return prefs.getBoolean("auto_speak", true); }
    public boolean saveHistory() { return prefs.getBoolean("save_history", true); }
    public boolean autoUpdate() { return prefs.getBoolean("auto_update", true); }
    public GatewayClient.Config config() throws Exception {
        String token = secrets.get("api_key");
        if (base().isEmpty() || token.isEmpty()) throw new IllegalStateException("请先在设置中填写 API 地址和密钥。");
        if (model().trim().isEmpty()) throw new IllegalStateException("请先选择翻译模型。");
        return new GatewayClient.Config(UrlPolicy.apiBase(base()), token, model().trim());
    }
}
