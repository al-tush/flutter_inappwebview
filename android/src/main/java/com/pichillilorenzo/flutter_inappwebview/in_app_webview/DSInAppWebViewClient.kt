package com.pichillilorenzo.flutter_inappwebview.in_app_webview

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.pichillilorenzo.flutter_inappwebview.in_app_browser.InAppBrowserDelegate
import io.flutter.plugin.common.MethodChannel
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.*
import java.net.Authenticator
import java.nio.charset.Charset

open class DSInAppWebViewClient(
        val inAppWebView: InAppWebView,
        val channel: MethodChannel,
        inAppBrowserDelegate: InAppBrowserDelegate?,
)
    : InAppWebViewClient(channel, inAppBrowserDelegate) {

    private var currentUrl: String = ""
    private var webViewNetworkHandler: WebViewNetworkHandler? = null

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentUrl = url
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
    ): WebResourceResponse? {
        //val pageUrl = currentUrl
        return try {
            if (webViewNetworkHandler == null) {
                webViewNetworkHandler = WebViewNetworkHandler(inAppWebView, this)
            }
            webViewNetworkHandler?.handleRequest(request)
        } catch (e: Throwable) {
            Timber.e(e)
            super.shouldInterceptRequest(view, request)
        }
    }

    class WebViewNetworkHandler(
            val inAppWebView: InAppWebView,
            val webViewClient: DSInAppWebViewClient,
    ) {
        private var client: OkHttpClient? = null
        private var currentProxyHost: String = ""

        fun handleRequest(req: WebResourceRequest?): WebResourceResponse? {
            if (req == null) return null
            // https://groups.google.com/a/chromium.org/g/android-webview-dev/c/xnZnmZJMLh0
            if (req.method == "POST") return null

            val url = req.url.toString()
            val headers = (req.requestHeaders ?: emptyMap()).toMutableMap()

            // https://gitlab.com/video-downloader-new-flutter/video-downloade-new-flutter/-/issues/116
            //headers["sec-fetch-dest"] = "document"
            headers["sec-fetch-mode"] = "navigate"
            headers["sec-fetch-site"] = "none"
            headers["sec-fetch-user"] = "?1"

            val newRequest = Request.Builder()
                        .url(url)
                        .headers(headers.toHeaders())
                        .build()

            val response = try {
                getClient().newCall(newRequest).execute()
            } catch (e: IOException) {
                Timber.e(e)
                Handler(Looper.getMainLooper()).post {
                    val obj: MutableMap<String, Any> = HashMap()
                    obj["errorText"] = e.message ?: ""
                    obj["currentUrl"] = webViewClient.currentUrl
                    obj["url"] = url
                    webViewClient.channel.invokeMethod("androidOnIOException", obj)
                }
                null
            }

            var respStream: BufferedInputStream? = null
            response?.body?.let {
                respStream = object : BufferedInputStream(it.byteStream()) {
                    override fun close() {
                        super.close()
                        it.close()
                    }
                }
            }

            return response?.let { resp ->
                WebResourceResponse(
                        resp.body?.contentType()?.let { "${it.type}/${it.subtype}" },
                        resp.body?.contentType()?.charset(Charset.defaultCharset())?.name(),
                        resp.code,
                        "OK",
                        resp.headers.toMap(),
                        respStream,
                )
            }
        }

        private fun getClient(): OkHttpClient {
            if (client == null) {
                currentProxyHost = inAppWebView.options.proxyHost
                client = makeClient()
                return client!!
            }
            if (currentProxyHost != inAppWebView.options.proxyHost) {
                currentProxyHost = inAppWebView.options.proxyHost
                client = makeClient()
                return client!!
            }
            return client!!
        }

        internal class VideoInterceptor(
                private val webViewClient: DSInAppWebViewClient,
        ) : Interceptor {
            @Throws(IOException::class)
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()
                // Timber.d("interceptor: $url")
                if (url.contains(".mp4") || url.contains(".m3u8")) {
                    Handler(Looper.getMainLooper()).post {
                        val obj: MutableMap<String, Any> = HashMap()
                        obj["currentUrl"] = webViewClient.currentUrl
                        obj["url"] = url
                        webViewClient.channel.invokeMethod("androidOnVideoRequest", obj)
                    }
                }
                return chain.proceed(request)
            }
        }

        private fun makeClient(): OkHttpClient {
            val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)

            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    // Timber.d("getPasswordAuthentication ${inAppWebView.options.proxyLogin}")
                    return PasswordAuthentication(
                            inAppWebView.options.proxyLogin,
                            inAppWebView.options.proxyPass.toCharArray()
                    )
                }
            })

            val builder = OkHttpClient.Builder()
                .addNetworkInterceptor(VideoInterceptor(webViewClient))
                    .cache(appCache)
            if (inAppWebView.options.proxyHost.isNotEmpty()) {
                builder.proxy(
                        Proxy(
                                Proxy.Type.SOCKS,
                                InetSocketAddress(inAppWebView.options.proxyHost, inAppWebView.options.proxyPort)
                        )
                )
                val client = builder.build()
                val dnsOverHttps = DnsOverHttps.Builder().client(client)
                        .url("https://dns.google/dns-query".toHttpUrl())
                        .bootstrapDnsHosts(
                                InetAddress.getByName("8.8.4.4"),
                                InetAddress.getByName("8.8.8.8")
                        )
                        .build()
                return client.newBuilder()
                        .dns(dnsOverHttps)
                        .build()
            } else {
                return builder.build()
            }
        }
    }


}