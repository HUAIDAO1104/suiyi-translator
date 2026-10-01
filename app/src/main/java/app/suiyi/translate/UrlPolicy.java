package app.suiyi.translate;

import java.net.URI;

public final class UrlPolicy {
    private UrlPolicy() {}
    public static String apiBase(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("/+$", "");
        try {
            URI uri = new URI(clean);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("接口地址需要是 HTTPS 地址，不能包含账号、查询参数或片段。");
            }
            return clean;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("接口地址格式不正确。");
        }
    }
    public static String repository(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.startsWith("https://github.com/")) clean = clean.substring(19);
        clean = clean.replaceAll("/+$", "");
        if (clean.endsWith(".git")) clean = clean.substring(0, clean.length() - 4);
        if (!clean.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}")
            || clean.endsWith("/.") || clean.endsWith("/..")) {
            throw new IllegalArgumentException("更新仓库请填写 用户名/仓库名，或对应的 GitHub 仓库链接。");
        }
        return clean;
    }
    public static boolean githubDownload(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost().toLowerCase(LocaleHolder.ROOT);
            return host.equals("api.github.com") || host.equals("github.com")
                || host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com")
                || host.equals("github-releases.githubusercontent.com");
        } catch (Exception e) { return false; }
    }
    private static class LocaleHolder { static final java.util.Locale ROOT = java.util.Locale.ROOT; }
}
