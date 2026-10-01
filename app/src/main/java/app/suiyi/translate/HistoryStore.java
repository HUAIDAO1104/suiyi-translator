package app.suiyi.translate;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public final class HistoryStore {
    private final SharedPreferences prefs;
    public HistoryStore(Context context) { prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE); }
    public List<TranslationRecord> load() {
        List<TranslationRecord> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("records", "[]"));
            for (int i = 0; i < array.length(); i++) records.add(TranslationRecord.fromJson(array.getJSONObject(i)));
        } catch (Exception ignored) { /* Never expose history contents in logs. */ }
        return records;
    }
    public void save(List<TranslationRecord> records) {
        JSONArray array = new JSONArray();
        try { for (TranslationRecord record : records) array.put(record.json()); }
        catch (Exception e) { throw new IllegalStateException("无法保存本机记录。", e); }
        prefs.edit().putString("records", array.toString()).apply();
    }
    public void add(TranslationRecord record) {
        List<TranslationRecord> records = load(); records.add(0, record);
        // Keep at most 200 ordinary records; favorites are never evicted by this limit.
        int ordinary = 0;
        java.util.Iterator<TranslationRecord> iterator = records.iterator();
        while (iterator.hasNext()) if (!iterator.next().favorite && ++ordinary > 200) iterator.remove();
        save(records);
    }
    public void toggleFavorite(String id) {
        List<TranslationRecord> records = load();
        for (TranslationRecord record : records) if (record.id.equals(id)) { record.favorite = !record.favorite; break; }
        save(records);
    }
    public void clear(boolean includeFavorites) {
        List<TranslationRecord> records = load();
        records.removeIf(record -> includeFavorites || !record.favorite); save(records);
    }
}
