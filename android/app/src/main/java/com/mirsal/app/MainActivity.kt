package com.mirsal.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePickerCallback: ValueCallback<Array<Uri>>? = null

    // منتقي ملفات عبر Storage Access Framework — لا يحتاج أي صلاحية تخزين
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePickerCallback
            filePickerCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            val uri = data.data
            if (uri != null) {
                callback.onReceiveValue(arrayOf(uri))
            } else {
                callback.onReceiveValue(null)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // منع لقطات الشاشة ومعظم أشكال تسجيل الشاشة
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        webView = WebView(this)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // جسر JS <-> Kotlin لحفظ الملفات المشفّرة/المفكوكة فعليًا على الجهاز
        webView.addJavascriptInterface(FileSaveBridge(this), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            // بدون هذا، حقل <input type="file"> في الصفحة لا يفتح أي منتقي إطلاقًا
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePickerCallback?.onReceiveValue(null)
                filePickerCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePickerCallback = null
                    false
                }
            }
        }

        webView.webViewClient = object : androidx.webkit.WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                url: String
            ): android.webkit.WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(android.net.Uri.parse(url))
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        setContentView(webView)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    /**
     * يستقبل بايتات مُرمّزة Base64 من JavaScript ويحفظها كملف حقيقي في
     * مجلد التنزيلات عبر MediaStore — بدون طلب أي صلاحية تخزين (Scoped Storage).
     */
    private class FileSaveBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun saveFile(base64Data: String, filename: String): Boolean {
            return try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val resolver = activity.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "تم الحفظ في مجلد التنزيلات: $filename",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
