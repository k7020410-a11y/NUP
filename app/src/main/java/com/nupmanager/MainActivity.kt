package com.nupmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6C5CE7),
                    secondary = Color(0xFFA29BFE),
                    background = Color(0xFF1E1E2E),
                    surface = Color(0xFF2A2B3D),
                    onPrimary = Color.White,
                    onBackground = Color(0xFFE2E8F0),
                    onSurface = Color(0xFFE2E8F0)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NupManagerScreen()
                }
            }
        }
    }
}

@Composable
private fun NupManagerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputFolderUri by remember { mutableStateOf<Uri?>(null) }
    var inputFolderName by remember { mutableStateOf<String?>(null) }
    var nupFileUri by remember { mutableStateOf<Uri?>(null) }
    var nupFileName by remember { mutableStateOf<String?>(null) }
    var extractFolderUri by remember { mutableStateOf<Uri?>(null) }
    var extractFolderName by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val selectPackFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri, write = false)
            inputFolderUri = uri
            inputFolderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected folder"
            status = "Input folder selected."
        }
    }
    val selectNupFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            nupFileUri = uri
            nupFileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "Selected NUP"
            status = "NUP file selected."
        }
    }
    val selectExtractFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri, write = true)
            extractFolderUri = uri
            extractFolderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected folder"
            status = "Extraction destination selected."
        }
    }
    val createNupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val folderUri = inputFolderUri
        if (uri != null && folderUri != null) {
            scope.launch {
                isWorking = true
                progress = 0f
                progressText = "Preparing package..."
                status = "Packing started. Keep the app open."
                try {
                    val result = withContext(Dispatchers.IO) {
                        val inputDir = DocumentFile.fromTreeUri(context, folderUri)
                            ?: throw IllegalArgumentException("Cannot open the selected source folder")
                        val temp = File(context.cacheDir, "nup-pack-${UUID.randomUUID()}.nup")
                        try {
                            NupPacker().pack(context, inputDir, temp) { current, total, fileName ->
                                val fraction = if (total > 0) current.toFloat() / total else 0f
                                scope.launch(Dispatchers.Main) {
                                    progress = fraction
                                    progressText = "Packing ($current / $total): $fileName"
                                }
                            }
                            context.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                                temp.inputStream().buffered().use { input -> input.copyTo(output, 128 * 1024) }
                            } ?: throw IllegalStateException("Cannot write the selected NUP output file")
                            temp.length()
                        } finally {
                            temp.delete()
                        }
                    }
                    status = "NUP created successfully (${formatBytes(result)})."
                    Toast.makeText(context, "NUP created successfully", Toast.LENGTH_LONG).show()
                } catch (error: Throwable) {
                    Log.e("NupPacker", "Packing failed", error)
                    status = "Pack failed: ${error.localizedMessage ?: error.message ?: "Unknown error"}"
                    Toast.makeText(context, "Pack failed: ${error.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isWorking = false
                    progressText = ""
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NUP Manager", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Create and extract NUP containers",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))
                Text("Create NUP", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (!isWorking) selectPackFolder.launch(null) },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(inputFolderName?.let { "Source: $it" } ?: "Select source folder") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val folderName = inputFolderName.orEmpty().substringBeforeLast('.', inputFolderName.orEmpty())
                            .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "game_dump" }
                        createNupFile.launch("$folderName.nup")
                    },
                    enabled = inputFolderUri != null && !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894))
                ) { Text("Pack to .nup", color = Color.White) }

                Divider(Modifier.padding(vertical = 20.dp), color = Color(0xFF45465A))

                Text("Extract NUP", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (!isWorking) selectNupFile.launch(arrayOf("*/*")) },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(nupFileName?.let { "NUP file: $it" } ?: "Select .nup file") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (!isWorking) selectExtractFolder.launch(null) },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(extractFolderName?.let { "Destination: $it" } ?: "Select extraction folder") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val archiveUri = nupFileUri ?: return@Button
                        val destinationUri = extractFolderUri ?: return@Button
                        scope.launch {
                            isWorking = true
                            progress = 0f
                            progressText = "Reading NUP container..."
                            status = "Extraction started. Keep the app open."
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    NupExtractor(context).extract(archiveUri, destinationUri) { current, total, fileName ->
                                        val fraction = if (total > 0) current.toFloat() / total else 0f
                                        scope.launch(Dispatchers.Main) {
                                            progress = fraction
                                            progressText = "Extracting ($current / $total): $fileName"
                                        }
                                    }
                                }
                                status = "Extracted ${result.extractedFiles} files (${formatBytes(result.extractedBytes)}) to ${result.outputFolderName}."
                                Toast.makeText(context, "NUP extraction completed", Toast.LENGTH_LONG).show()
                            } catch (error: Throwable) {
                                Log.e("NupExtractor", "Extraction failed", error)
                                status = "Extract failed: ${error.localizedMessage ?: error.message ?: "Unknown error"}"
                                Toast.makeText(context, "Extract failed: ${error.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isWorking = false
                                progressText = ""
                            }
                        }
                    },
                    enabled = nupFileUri != null && extractFolderUri != null && !isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text(if (isWorking && progressText.startsWith("Extracting")) "Extracting..." else "Extract NUP", color = Color.White) }

                if (isWorking) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(progressText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                }
                if (status.isNotEmpty() && !isWorking) {
                    Spacer(Modifier.height(14.dp))
                    Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Files and folders are accessed through Android's system picker; no broad storage access is needed.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun persistTreePermission(context: android.content.Context, uri: Uri, write: Boolean) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
    try { context.contentResolver.takePersistableUriPermission(uri, flags) }
    catch (_: SecurityException) { }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}
