package app.suiyi.translate;

import org.json.JSONObject;

public final class UpdateManifest {
    public final int versionCode;
    public final String versionName, sha256, notes;
    public final long size;
    public UpdateManifest(String json, String expectedApplicationId) throws Exception {
        JSONObject item = new JSONObject(json);
        if (!expectedApplicationId.equals(item.getString("applicationId"))) throw new IllegalStateException("更新信息与当前应用不匹配。");
        versionCode = item.getInt("versionCode"); versionName = item.getString("versionName");
        sha256 = item.getString("sha256").toLowerCase(java.util.Locale.ROOT);
        size = item.getLong("size"); notes = item.optString("notes", "修复与体验改进");
        if (versionCode <= 0 || versionName.isEmpty() || !sha256.matches("[0-9a-f]{64}") || size <= 0 || size > 100L * 1024 * 1024) {
            throw new IllegalStateException("更新信息格式不正确。");
        }
    }
    public boolean newerThan(int installedCode) { return versionCode > installedCode; }
}
