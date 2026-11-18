package cat.smx.inventari

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraMediaUri: Uri? = null

    private val domainHost = "inventarismx.duckdns.org"
    private val baseUrl = "https://$domainHost"

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null

        if (result.resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val uris = mutableListOf<Uri>()

        if (data?.clipData != null) {
            val count = data.clipData!!.itemCount
            for (i in 0 until count) {
                uris.add(data.clipData!!.getItemAt(i).uri)
            }
        } else if (data?.data != null) {
            uris.add(data.data!!)
        } else if (cameraMediaUri != null) {
            uris.add(cameraMediaUri!!)
        }

        callback.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Permís de càmera denegat (pots triar fitxers de la galeria).", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Contenedor raíz con WebView + botón flotante nativo de Escáner QR
        val rootLayout = FrameLayout(this)

        swipeRefresh = SwipeRefreshLayout(this)
        webView = WebView(this)
        swipeRefresh.addView(webView)
        rootLayout.addView(swipeRefresh)

        val qrButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_qr)
            contentDescription = "Escanejar QR d'equip"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0284C7"))
            }
            elevation = 14f
            setOnClickListener { abrirEscanerQR() }
        }

        val sizePx = (60 * resources.displayMetrics.density).toInt()
        val marginPx = (20 * resources.displayMetrics.density).toInt()
        val btnParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        rootLayout.addView(qrButton, btnParams)

        setContentView(rootLayout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "$userAgentString InventariTIC-AndroidApp/1.1"
        }

        swipeRefresh.setColorSchemeColors(0xFF0284C7.toInt())
        swipeRefresh.setOnRefreshListener { webView.reload() }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://$domainHost") || url.startsWith("http://$domainHost")) {
                    return false
                }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                cookieManager.flush()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intents = mutableListOf<Intent>()
                try {
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val photoFile = File.createTempFile("INCIDENCIA_${timeStamp}_", ".jpg", cacheDir)
                    cameraMediaUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraMediaUri)
                    }
                    intents.add(captureIntent)
                    intents.add(Intent(MediaStore.ACTION_VIDEO_CAPTURE))
                } catch (_: Exception) {
                    cameraMediaUri = null
                }

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Selecciona o captura foto/vídeo")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                }

                fileChooserLauncher.launch(chooser)
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("data:")) {
                Toast.makeText(this, "Per descarregar el QR mantén premut sobre la imatge o usa Imprimir.", Toast.LENGTH_LONG).show()
                return@setDownloadListener
            }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Descarregant des d'Inventari TIC...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Descarregant $fileName a Descàrregues...", Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // Si venimos de escanear un QR fuera de la app (Deep Link), abrimos directamente esa ficha
        val initialUrl = resolverUrlDeIntent(intent) ?: baseUrl
        webView.loadUrl(initialUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolverUrlDeIntent(intent)?.let { targetUrl ->
            webView.loadUrl(targetUrl)
        }
    }

    private fun resolverUrlDeIntent(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        if (data.host.equals(domainHost, ignoreCase = true)) {
            return data.toString().replace("http://", "https://")
        }
        return null
    }

    private fun abrirEscanerQR() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue?.trim() ?: return@addOnSuccessListener
                if (raw.contains(domainHost)) {
                    val finalUrl = if (raw.startsWith("http")) {
                        raw.replace("http://", "https://")
                    } else {
                        "https://$raw"
                    }
                    webView.loadUrl(finalUrl)
                } else {
                    Toast.makeText(this, "QR no pertany a l'inventari: $raw", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error escanejant QR: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }
}
