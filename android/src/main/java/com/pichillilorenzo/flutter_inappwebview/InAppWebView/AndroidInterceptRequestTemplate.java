package com.pichillilorenzo.flutter_inappwebview.InAppWebView;

import android.net.Uri;

public class AndroidInterceptRequestTemplate {
    public String scheme;
    public String host;
    public String path;

    Boolean isMatches(Uri uri) {
        if (uri == null) return false;
        if ((scheme != null) && (!scheme.equals(uri.getScheme()))) return false;
        if ((host != null) && (!host.equals(uri.getHost()))) return false;
        if ((path != null) && (!path.equals(uri.getPath()))) return false;
        return true;
    }
}
