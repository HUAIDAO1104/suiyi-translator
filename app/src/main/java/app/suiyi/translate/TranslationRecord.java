package app.suiyi.translate;

import org.json.JSONObject;

public final class TranslationRecord {
    public final String id;
    public final long time;
    public final Language source;
    public final Language target;
    public final String original;
    public final String translated;
    public boolean favorite;
    public TranslationRecord(Language source, Language target, String original, String translated) {
        this(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), source, target, original, translated, false);
    }
    private TranslationRecord(String id, long time, Language source, Language target, String original, String translated, boolean favorite) {
        this.id = id; this.time = time; this.source = source; this.target = target;
        this.original = original; this.translated = translated; this.favorite = favorite;
    }
    public JSONObject json() throws Exception {
        return new JSONObject().put("id", id).put("time", time).put("source", source.code)
            .put("target", target.code).put("original", original).put("translated", translated).put("favorite", favorite);
    }
    public static TranslationRecord fromJson(JSONObject item) throws Exception {
        return new TranslationRecord(item.getString("id"), item.getLong("time"), Language.fromCode(item.getString("source")),
            Language.fromCode(item.getString("target")), item.getString("original"), item.getString("translated"), item.optBoolean("favorite"));
    }
}
