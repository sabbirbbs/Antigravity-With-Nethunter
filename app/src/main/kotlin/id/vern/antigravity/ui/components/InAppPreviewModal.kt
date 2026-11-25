package id.vern.antigravity.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import id.vern.antigravity.root.RootFileEntry
import id.vern.antigravity.root.RootNetHunterManager
import id.vern.antigravity.viewmodel.AntigravityViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed class PreviewTarget {
    data class WebUrl(val url: String, val lanUrl: String? = null) : PreviewTarget()
    data class LocalFile(val file: File, val targetLine: Int? = null, val isDirectory: Boolean = false) : PreviewTarget()
}

object InAppPreviewController {
    var activeTarget by mutableStateOf<PreviewTarget?>(null)
        private set

    fun dismiss() {
        activeTarget = null
    }

    fun openWebUrl(context: Context, rawUrl: String) {
        val normalized = normalizeLocalUrl(rawUrl)
        val lan = buildLanEquivalent(context, normalized)
        activeTarget = PreviewTarget.WebUrl(normalized, lan)
    }

    fun openFile(context: Context, file: File, targetLine: Int? = null) {
        val isDir = RootNetHunterManager.isDirectoryWithRootFallback(file)
        activeTarget = PreviewTarget.LocalFile(file, targetLine, isDir)
    }

    fun handleClickedLink(context: Context, rawUrl: String, workspacePath: String? = null) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return

        if (trimmed.startsWith("file://", ignoreCase = true) || trimmed.startsWith("/")) {
            val anchor = trimmed.substringAfter("#L", "").substringBefore("-").toIntOrNull()
            val resolved = RootNetHunterManager.resolveReadableTarget(context, trimmed, workspacePath)
            if (resolved != null && RootNetHunterManager.pathExistsWithRootFallback(resolved)) {
                openFile(context, resolved, anchor)
            } else {
                val clean = if (trimmed.startsWith("file://")) {
                    Uri.parse(trimmed).path ?: trimmed.removePrefix("file://")
                } else trimmed
                val fileObj = File(clean.substringBefore("#"))
                val readable = RootNetHunterManager.readFileTextWithRootFallback(fileObj)
                if (readable != null) {
                    openFile(context, fileObj, anchor)
                } else {
                    Toast.makeText(context, "File not found: ${fileObj.absolutePath}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        if (isLocalOrPrivateHttpUrl(trimmed)) {
            openWebUrl(context, trimmed)
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openWebUrl(context, trimmed)
        }
    }

    private fun isLocalOrPrivateHttpUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return false
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host == "0.0.0.0" ||
            host == "::1" ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.matches(Regex("""^172\.(1[6-9]|2\d|3[01])\..*"""))
    }

    fun normalizeLocalUrl(rawUrl: String): String {
        return rawUrl
            .replace("://0.0.0.0", "://127.0.0.1", ignoreCase = true)
            .replace("://localhost", "://127.0.0.1", ignoreCase = true)
    }

    private fun buildLanEquivalent(context: Context, normalizedUrl: String): String? {
        val wifiIp = RootNetHunterManager.getLocalWifiIpAddress(context) ?: return null
        val uri = Uri.parse(normalizedUrl)
        val host = uri.host ?: return null
        return if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
            normalizedUrl.replaceFirst("://$host", "://$wifiIp")
        } else {
            null
        }
    }
}

@Composable
fun InAppPreviewHost(
    viewModel: AntigravityViewModel,
    onSwitchToWorkspaceEditor: (() -> Unit)? = null
) {
    val target = InAppPreviewController.activeTarget ?: return
    val context = LocalContext.current

    Dialog(
        onDismissRequest = { InAppPreviewController.dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (target) {
                is PreviewTarget.WebUrl -> {
                    InAppWebBrowserContent(
                        initialUrl = target.url,
                        lanUrl = target.lanUrl,
                        onDismiss = { InAppPreviewController.dismiss() }
                    )
                }
                is PreviewTarget.LocalFile -> {
                    InAppLocalFilePreviewContent(
                        initialFile = target.file,
                        targetLine = target.targetLine,
                        initialIsDir = target.isDirectory,
                        viewModel = viewModel,
                        onOpenInEditor = { file ->
                            viewModel.openFileInEditor(file)
                            InAppPreviewController.dismiss()
                            onSwitchToWorkspaceEditor?.invoke()
                            Toast.makeText(context, "Opened ${file.name} in Code Editor", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { InAppPreviewController.dismiss() }
                    )
                }
            }
        }
    }
}

@Composable
private fun InAppWebBrowserContent(
    initialUrl: String,
    lanUrl: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var urlInput by remember(initialUrl) { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(true) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var serverDiag by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { webViewRef?.goBack() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { webViewRef?.goForward() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        lastError = null
                        webViewRef?.reload()
                    }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(18.dp))
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    ) {
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val next = InAppPreviewController.normalizeLocalUrl(
                                    if (urlInput.startsWith("http://") || urlInput.startsWith("https://")) urlInput
                                    else "http://$urlInput"
                                )
                                currentUrl = next
                                urlInput = next
                                lastError = null
                                webViewRef?.loadUrl(next)
                            }),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "External browser error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "External Browser", modifier = Modifier.size(18.dp))
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.clickable {
                            currentUrl = initialUrl
                            urlInput = initialUrl
                            lastError = null
                            webViewRef?.loadUrl(initialUrl)
                        }
                    ) {
                        Text(
                            text = "Localhost",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (!lanUrl.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.clickable {
                                currentUrl = lanUrl
                                urlInput = lanUrl
                                lastError = null
                                webViewRef?.loadUrl(lanUrl)
                            }
                        ) {
                            Text(
                                text = "Wi-Fi: $lanUrl",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("url", lanUrl ?: currentUrl))
                            Toast.makeText(context, "Copied: ${lanUrl ?: currentUrl}", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(
                            text = "Copy LAN URL",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable {
                            val (_, out) = RootNetHunterManager.runSuQuick("ss -tlnp 2>/dev/null || netstat -tln 2>/dev/null || cat /proc/net/tcp /proc/net/tcp6", 3000L)
                            serverDiag = out.ifBlank { "No listening sockets detected." }
                        }
                    ) {
                        Text(
                            text = "Check Ports",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        if (lastError != null || serverDiag != null) {
            Surface(
                color = if (lastError != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (lastError != null) {
                        Text(
                            text = "Connection Issue: $lastError\nTip: Server must bind to 0.0.0.0 and stay running (use 'agy-srv start web \"python3 -m http.server 8000 --bind 0.0.0.0\"').",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    if (serverDiag != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = serverDiag!!,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                            lastError = null
                            if (!url.isNullOrBlank()) {
                                currentUrl = url
                                urlInput = url
                            }
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                                lastError = error?.description?.toString() ?: "Connection failed"
                            }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val reqUrl = request?.url?.toString() ?: return false
                            if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                                return false
                            }
                            InAppPreviewController.handleClickedLink(ctx, reqUrl)
                            return true
                        }
                    }
                    loadUrl(initialUrl)
                    webViewRef = this
                }
            },
            update = { view ->
                webViewRef = view
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun InAppLocalFilePreviewContent(
    initialFile: File,
    targetLine: Int?,
    initialIsDir: Boolean,
    viewModel: AntigravityViewModel,
    onOpenInEditor: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentFile by remember(initialFile) { mutableStateOf(initialFile) }
    var isDir by remember(initialFile) { mutableStateOf(initialIsDir) }
    var dirEntries by remember { mutableStateOf<List<RootFileEntry>>(emptyList()) }
    var textContent by remember { mutableStateOf<String?>(null) }
    var imageTempFile by remember { mutableStateOf<File?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val ext = currentFile.extension.lowercase(Locale.ROOT)
    val isHtml = ext == "html" || ext == "htm" || ext == "svg"
    val isMarkdown = ext == "md" || ext == "markdown"
    val isImage = ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    LaunchedEffect(currentFile) {
        isLoading = true
        textContent = null
        imageTempFile = null
        dirEntries = emptyList()
        withContext(Dispatchers.IO) {
            val dirCheck = RootNetHunterManager.isDirectoryWithRootFallback(currentFile)
            isDir = dirCheck
            if (dirCheck) {
                dirEntries = RootNetHunterManager.listDirectoryWithRootFallback(currentFile)
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            } else if (isImage) {
                imageTempFile = RootNetHunterManager.copyFileToAccessibleTemp(context, currentFile)
            } else {
                textContent = RootNetHunterManager.readFileTextWithRootFallback(currentFile) ?: "[Unable to read file]"
            }
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentFile.parentFile != null) {
                        IconButton(
                            onClick = {
                                currentFile.parentFile?.let { currentFile = it }
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up", modifier = Modifier.size(18.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            text = currentFile.name.ifBlank { currentFile.absolutePath },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentFile.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                if (!isDir) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onOpenInEditor(currentFile) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                val copied = RootNetHunterManager.copyFileToDownloads(currentFile)
                                Toast.makeText(
                                    context,
                                    if (copied != null) "Saved to ${copied.absolutePath}" else "Copy failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Downloads", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("path", currentFile.absolutePath))
                                Toast.makeText(context, "Copied path", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Path", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    val temp = RootNetHunterManager.copyFileToAccessibleTemp(context, currentFile) ?: currentFile
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
                                    val mime = context.contentResolver.getType(uri) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No external app for .$ext", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open With", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        when {
            isDir -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(dirEntries, key = { it.absolutePath }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentFile = File(entry.absolutePath) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (entry.isDirectory) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (!entry.isDirectory) {
                                Text(
                                    text = "${entry.sizeBytes} B",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
            isImage && imageTempFile != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imageTempFile,
                        contentDescription = currentFile.name,
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    )
                }
            }
            isHtml && textContent != null -> {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            setOnTouchListener { v, event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                false
                            }
                            webViewClient = WebViewClient()
                        }
                    },
                    update = { webView ->
                        val base = currentFile.parentFile?.let { "file://${it.absolutePath}/" }
                        val mime = if (ext == "svg") "image/svg+xml" else "text/html"
                        webView.loadDataWithBaseURL(base, textContent!!, mime, "UTF-8", null)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            isMarkdown && textContent != null -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        MarkdownText(markdown = textContent!!)
                    }
                }
            }
            textContent != null -> {
                val lines = remember(textContent) { textContent!!.lines() }
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D1117))
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        itemsIndexed(lines) { index, lineText ->
                            val lineNum = index + 1
                            val isHighlight = targetLine != null && lineNum == targetLine
                            Row(
                                modifier = Modifier
                                    .background(if (isHighlight) Color(0xFF1E3A5F) else Color.Transparent)
                                    .padding(vertical = 1.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%4d ", lineNum),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    text = lineText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
