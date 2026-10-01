package app.suiyi.translate;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class UpdateClient {
    public interface Progress { void report(int percentage); }
    public static final class Release {
        public final UpdateManifest manifest;
        public final String apkUrl;
        public Release(UpdateManifest manifest, String apkUrl) { this.manifest = manifest; this.apkUrl = apkUrl; }
    }
    private volatile HttpURLConnection active;
    public void cancel() { HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    public Release check(String repository, String token) throws Exception {
        String repo = UrlPolicy.repository(repository);
        JSONObject latest;
        try { latest = new JSONObject(read("https://api.github.com/repos/" + repo + "/releases/latest", token, false)); }
        catch (NoReleaseException e) { return null; }
        if (latest.optBoolean("draft") || latest.optBoolean("prerelease")) return null;
        JSONArray assets = latest.optJSONArray("assets");
        if (assets == null) throw new IllegalStateException("这个发布没有安装文件。");
        String manifestUrl = null, apkUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if ("update.json".equals(asset.optString("name"))) manifestUrl = asset.getString("url");
            if ("suiyi.apk".equals(asset.optString("name"))) apkUrl = asset.getString("url");
        }
        if (manifestUrl == null || apkUrl == null) throw new IllegalStateException("最新发布缺少 suiyi.apk 或 update.json，请检查构建结果。");
        return new Release(new UpdateManifest(read(manifestUrl, token, true), BuildConfig.APPLICATION_ID), apkUrl);
    }
    public File download(Context context, Release release, String token, Progress progress) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建更新目录。");
        File partial = new File(directory, "download.part");
        File apk = new File(directory, "suiyi-" + release.manifest.versionCode + ".apk");
        HttpURLConnection connection = open(release.apkUrl, token, true);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            long total = 0; int lastProgress = -1;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("更新下载已取消。");
                    total += count;
                    if (total > release.manifest.size || total > 100L * 1024 * 1024) throw new IllegalStateException("安装文件大小与发布信息不一致。");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                    int percent = (int) (total * 100 / release.manifest.size);
                    if (percent != lastProgress) { progress.report(percent); lastProgress = percent; }
                }
            }
            if (total != release.manifest.size || !hex(digest.digest()).equals(release.manifest.sha256)) {
                throw new IllegalStateException("安装文件校验失败，请重新下载。");
            }
            verifyPackage(context, partial, release.manifest.versionCode);
            if (apk.exists() && !apk.delete()) throw new IllegalStateException("无法替换旧下载文件。");
            if (!partial.renameTo(apk)) throw new IllegalStateException("无法保存安装文件。");
            return apk;
        } finally {
            connection.disconnect(); active = null;
            if (partial.exists()) partial.delete();
        }
    }
    @SuppressWarnings("deprecation")
    private void verifyPackage(Context context, File file, int expectedCode) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo current = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo incoming = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (incoming == null || !context.getPackageName().equals(incoming.packageName)) throw new IllegalStateException("下载的文件不是此应用的安装包。");
        long code = Build.VERSION.SDK_INT >= 28 ? incoming.getLongVersionCode() : incoming.versionCode;
        long installed = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
        if (code != expectedCode || code <= installed) throw new IllegalStateException("安装包版本不符合更新信息。");
        Signature[] oldSignatures = Build.VERSION.SDK_INT >= 28 ? current.signingInfo.getApkContentsSigners() : current.signatures;
        Signature[] newSignatures = Build.VERSION.SDK_INT >= 28 ? incoming.signingInfo.getApkContentsSigners() : incoming.signatures;
        if (!certificateSet(oldSignatures).equals(certificateSet(newSignatures))) {
            throw new IllegalStateException("新版签名与已安装应用不同，不能覆盖更新。请使用同一签名密钥构建。");
        }
    }
    private static Set<String> certificateSet(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) throw new IllegalStateException("无法验证安装包签名。");
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }
    private String read(String url, String token, boolean asset) throws Exception {
        HttpURLConnection connection = open(url, token, asset);
        try { return new String(GatewayClient.readBounded(connection.getInputStream(), 1024 * 1024), StandardCharsets.UTF_8); }
        finally { connection.disconnect(); active = null; }
    }
    private HttpURLConnection open(String value, String token, boolean asset) throws Exception {
        String url = value;
        for (int redirects = 0; redirects < 6; redirects++) {
            if (!UrlPolicy.githubDownload(url)) throw new IllegalStateException("更新下载地址不是受支持的 GitHub HTTPS 地址。");
            URL parsed = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) parsed.openConnection(); active = connection;
            connection.setConnectTimeout(15000); connection.setReadTimeout(45000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "SuiYi-Android/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", asset ? "application/octet-stream" : "application/vnd.github+json");
            if (parsed.getHost().equalsIgnoreCase("api.github.com")) {
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int status;
            try { status = connection.getResponseCode(); }
            catch (Exception e) { connection.disconnect(); active = null; throw e; }
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IllegalStateException("更新下载跳转地址缺失。");
                url = new URL(parsed, location).toString(); continue;
            }
            if (status == 404 && !asset) {
                connection.disconnect(); active = null;
                if (token != null && !token.isEmpty()) throw new IllegalStateException("未找到发布或仓库不可访问，请检查仓库地址及 GitHub 令牌权限。");
                throw new NoReleaseException();
            }
            if (status == 401 || status == 403 || (status == 404 && asset)) {
                connection.disconnect(); active = null;
                throw new IllegalStateException("GitHub 仓库或下载不可访问。私有仓库需要仅有 Contents 读取权限的令牌；也可能是请求次数达到限制。");
            }
            if (status < 200 || status >= 300) { connection.disconnect(); active = null; throw new IllegalStateException("GitHub 更新请求失败（HTTP " + status + "）。"); }
            return connection;
        }
        throw new IllegalStateException("更新下载跳转次数过多。");
    }
    public static String hex(byte[] data) {
        StringBuilder output = new StringBuilder();
        for (byte value : data) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }
    private static class NoReleaseException extends Exception {}
}
