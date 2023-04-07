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
import okhttp3.internal.http.RealResponseBody
import okio.GzipSource
import okio.buffer
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

    var currentUrl: String = ""
        private set
    private var webViewNetworkHandler: WebViewNetworkHandler? = null

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentUrl = url
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
    ): WebResourceResponse? {
        if (currentUrl == "" && request.isForMainFrame) {
            currentUrl = request.url.toString();
        }

        val response = super.shouldInterceptRequest(view, request)
        return try {
            if (webViewNetworkHandler == null) {
                webViewNetworkHandler = WebViewNetworkHandler(inAppWebView, this)
            }
            webViewNetworkHandler!!.handleRequest(request, response)
        } catch (e: Throwable) {
            Timber.e(e)
            response
        }
    }

    class WebViewNetworkHandler(
            val inAppWebView: InAppWebView,
            val webViewClient: DSInAppWebViewClient,
    ) {
        private var client: OkHttpClient? = null
        private var currentProxyHost: String = ""

        fun handleRequest(req: WebResourceRequest?, interceptedResponse: WebResourceResponse?): WebResourceResponse? {
            if (req == null) return null

            if (interceptedResponse != null) return interceptedResponse;

            val url = req.url.toString()

            if (url.contains(".mp4") || url.contains(".m3u8")) {
                Handler(Looper.getMainLooper()).post {
                    val obj: MutableMap<String, Any> = HashMap()
                    obj["currentUrl"] = webViewClient.currentUrl
                    obj["url"] = url
                    webViewClient.channel.invokeMethod("androidOnVideoRequest", obj)
                }
            }

            if (inAppWebView.options.proxyHost.isEmpty()) {
                return null
            }

            val headers = req.requestHeaders?.toMutableMap() ?: mutableMapOf()
            headers["accept-encoding"] = "gzip, deflate"

            val newRequest = headers.toHeaders().let {
                Request.Builder()
                        .url(url)
                        .headers(it)
                        .build()
            }

            val response = try {
                newRequest.let { getClient().newCall(it).execute() }
            } catch (e: IOException) {
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
                    .addInterceptor(UnzippingInterceptor())
                    .build()
            } else {
                return builder.build()
            }
        }
    }

    private class UnzippingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response: Response = chain.proceed(chain.request())
            return unzip(response)
        }

        // copied from okhttp3.internal.http.HttpEngine
        private fun unzip(response: Response): Response {
            if (response.body == null) {
                return response
            }

            val contentEncoding = response.headers["Content-Encoding"]

            return if (contentEncoding != null && contentEncoding == "gzip") {
                val contentLength = response.body!!.contentLength()
                val responseBody = GzipSource(response.body!!.source())
                val strippedHeaders = response.headers.newBuilder().build()
                response.newBuilder().headers(strippedHeaders)
                    .body(
                        RealResponseBody(
                            response.body!!.contentType().toString(),
                            contentLength,
                            responseBody.buffer()
                        )
                    )
                    .build()
            } else {
                response
            }
        }
    }

}