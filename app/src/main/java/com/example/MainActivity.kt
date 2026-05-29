package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PrintDropApp()
                }
            }
        }
    }
}

data class ReceivedFile(val file: File, val timeReceived: Long)

class MainViewModel : ViewModel() {
    private val _ipAddress = MutableStateFlow<String?>(null)
    val ipAddress = _ipAddress.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val receivedFiles = _receivedFiles.asStateFlow()

    private var serverContentDir: File? = null

    fun init(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _ipAddress.value = getLocalIpAddress()
            
            val dir = File(context.cacheDir, "received_files")
            if (!dir.exists()) dir.mkdirs()
            serverContentDir = dir
            
            cleanOldFiles()
            loadFiles()
            startServer(context)
        }
    }

    private fun loadFiles() {
        val files = serverContentDir?.listFiles()?.toList() ?: emptyList()
        _receivedFiles.value = files.map { ReceivedFile(it, it.lastModified()) }.sortedByDescending { it.timeReceived }
    }

    private fun cleanOldFiles() {
        val sixHoursAgo = System.currentTimeMillis() - 6 * 60 * 60 * 1000
        serverContentDir?.listFiles()?.forEach { file ->
            if (file.lastModified() < sixHoursAgo) {
                file.delete()
            }
        }
    }

    fun deleteFile(file: File) {
        if (file.exists()) {
            file.delete()
            loadFiles()
        }
    }

    private fun startServer(context: Context) {
        try {
            embeddedServer(CIO, port = 8080) {
                routing {
                    get("/") {
                        call.respondText(
                            contentType = ContentType.Text.Html,
                            text = """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                    <style>
                                        body { font-family: sans-serif; text-align: center; padding: 20px; background: #1C1B1F; color: #E6E1E5; }
                                        .container { background: #2B2930; padding: 30px; border-radius: 24px; box-shadow: 0 8px 24px rgba(0,0,0,0.5); max-width: 400px; margin: auto; border: 1px solid rgba(73, 69, 79, 0.3); }
                                        h1 { color: #E6E1E5; margin-bottom: 8px; font-weight: 400; }
                                        p { color: #CAC4D0; margin-bottom: 24px; font-size: 14px; }
                                        input[type="file"] { margin: 20px 0; display: block; width: 100%; box-sizing: border-box; border: 1px solid rgba(73, 69, 79, 0.5); background: #211F26; color: #E6E1E5; padding: 12px; border-radius: 12px; }
                                        button { background: #D0BCFF; color: #381E72; border: none; padding: 14px 24px; font-size: 16px; font-weight: 500; border-radius: 24px; cursor: pointer; width: 100%; margin-top: 8px; }
                                        button:hover { opacity: 0.9; }
                                    </style>
                                </head>
                                <body>
                                    <div class="container">
                                        <h1>PrintDrop.</h1>
                                        <p>Select a document or image to send to the shopkeeper securely.</p>
                                        <form action="/upload" method="post" enctype="multipart/form-data">
                                            <input type="file" name="file" required />
                                            <button type="submit">Send File</button>
                                        </form>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()
                        )
                    }
                    post("/upload") {
                        try {
                            val multipart = call.receiveMultipart()
                            multipart.forEachPart { part ->
                                if (part is PartData.FileItem) {
                                    val fileName = part.originalFileName ?: "unknown_file_${System.currentTimeMillis()}"
                                    val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9.\\-]"), "_")
                                    val file = File(serverContentDir, "${System.currentTimeMillis()}_$safeFileName")
                                    val bytes = part.streamProvider().readBytes()
                                    file.writeBytes(bytes)
                                }
                                part.dispose()
                            }
                            
                            viewModelScope.launch(Dispatchers.IO) {
                                cleanOldFiles()
                                loadFiles()
                            }
                            
                            call.respondText(
                                contentType = ContentType.Text.Html,
                                text = """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                    <style>
                                        body { font-family: sans-serif; text-align: center; padding: 50px 20px; background: #1C1B1F; color: #E6E1E5; }
                                        .container { background: #2B2930; padding: 30px; border-radius: 24px; box-shadow: 0 8px 24px rgba(0,0,0,0.5); max-width: 400px; margin: auto; border: 1px solid rgba(73, 69, 79, 0.3); }
                                        h1 { color: #4ADE80; font-weight: 400; margin-bottom: 8px; }
                                        p { color: #CAC4D0; font-size: 14px; }
                                    </style>
                                </head>
                                <body>
                                    <div class="container">
                                        <h1>Success! ✓</h1>
                                        <p>File sent securely. You may close this page.</p>
                                    </div>
                                </body>
                                </html>
                                """.trimIndent()
                            )
                        } catch (e: Exception) {
                            call.respondText("Failed to upload: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    fun refreshIp() {
        _ipAddress.value = getLocalIpAddress()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintDropApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val ipAddress by viewModel.ipAddress.collectAsState()
    val files by viewModel.receivedFiles.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            // Simulated Bottom Navigation for design match
            Box(modifier = Modifier.fillMaxWidth().height(80.dp).background(DarkSurface)) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.background(IconBackgroundActive, CircleShape).padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = PrimaryPurple)
                        }
                        Text("Receiver", fontSize = MaterialTheme.typography.labelSmall.fontSize, color = PrimaryPurple, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary)
                        Text("Archive", fontSize = MaterialTheme.typography.labelSmall.fontSize, color = TextSecondary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary)
                        Text("Manage QR", fontSize = MaterialTheme.typography.labelSmall.fontSize, color = TextSecondary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header Section
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("PrintQuick", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(IconBackgroundActive, CircleShape)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = PrimaryPurple)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(PulseGreen.copy(alpha = alpha), CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (ipAddress != null) "Live: Ready to Receive" else "Connecting...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Permanent QR Card
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(DarkSurface, RoundedCornerShape(24.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("CUSTOMER SCAN POINT", style = MaterialTheme.typography.labelSmall, color = PrimaryPurple, fontWeight = FontWeight.Medium, letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = Modifier.padding(bottom = 16.dp))
                    
                    if (ipAddress != null) {
                        val url = "http://$ipAddress:8080"
                        val qrBitmap = remember(url) { generateQrCode(url) }
                        if (qrBitmap != null) {
                            Box(modifier = Modifier.background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(16.dp)) {
                                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(160.dp))
                            }
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))) {
                            Button(onClick = { viewModel.refreshIp() }) { Text("Retry") }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scan to send files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text("Works with PDF, JPG, PNG & more", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(top = 4.dp))
                    if (ipAddress != null) {
                        Text(text = "http://$ipAddress:8080", style = MaterialTheme.typography.labelSmall, color = TextTertiary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // Live Queue Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(DarkSurfaceVariant, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Incoming Files", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Auto-delete: 6h", style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.background(BadgeBackground, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp))
                }

                if (files.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = TextTertiary.copy(alpha = 0.5f), modifier = Modifier.size(48.dp).padding(bottom = 8.dp))
                        Text("Waiting for customer...", color = TextTertiary.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { fileRec ->
                            FileItemRow(
                                fileRec = fileRec,
                                onOpen = { openFile(context, fileRec.file) },
                                onShare = { shareFile(context, fileRec.file) },
                                onDelete = { viewModel.deleteFile(fileRec.file) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileItemRow(
    fileRec: ReceivedFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val timeDiffMs = System.currentTimeMillis() - fileRec.timeReceived
    val mins = (timeDiffMs / (1000 * 60)).coerceAtLeast(0)
    val timeStr = if (mins < 1) "Just now" else if (mins < 60) "${mins}m ago" else "${mins / 60}h ago"
    val sizeKb = fileRec.file.length() / 1024
    val sizeStr = if (sizeKb > 1024) String.format("%.1f MB", sizeKb / 1024f) else "${sizeKb} KB"
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(DarkSurface, RoundedCornerShape(16.dp))
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(IconBackground, RoundedCornerShape(12.dp))) {
            val ext = fileRec.file.name.substringAfterLast('.', "").lowercase()
            val isImage = ext in listOf("png", "jpg", "jpeg", "gif")
            Icon(if (isImage) Icons.Default.Info else Icons.AutoMirrored.Filled.List, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(32.dp))
        }
        
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(
                text = fileRec.file.name.substringAfter("_", fileRec.file.name),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Received $timeStr • $sizeStr",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        
        Row {
            Box(modifier = Modifier.size(48.dp).background(PrimaryPurple, CircleShape).clickable { onShare() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = OnPrimaryPurple)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.size(48.dp).background(BadgeBackground, CircleShape).clickable { onDelete() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
            }
        }
    }
}

fun generateQrCode(text: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun getUriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

fun getMimeType(url: String): String {
    val ext = url.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "*/*"
    }
}

fun openFile(context: Context, file: File) {
    try {
        val uri = getUriForFile(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open File"))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun shareFile(context: Context, file: File) {
    try {
        val uri = getUriForFile(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = getMimeType(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share/Print File"))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
