package com.nupmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NupManagerPackScreen()
                }
            }
        }
    }
}

@Composable
fun NupManagerPackScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFolderUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFolderName by remember { mutableStateOf<String?>(null) }
    var isPacking by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var progressFraction by remember { mutableStateOf(0f) }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            selectedFolderUri = uri
            val docDir = DocumentFile.fromTreeUri(context, uri)
            selectedFolderName = docDir?.name ?: uri.lastPathSegment ?: "Selected Folder"
            statusMessage = "Folder selected: $selectedFolderName"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "NUP Manager",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Convert Unpacked Dump to .nup Container",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedCard(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFF1E1E2E))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Supported layouts:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Bundle: base / updates / dlc\n• Direct: exefs / romfs",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Select Folder Button
                Button(
                    onClick = {
                        if (!isPacking) {
                            openDocumentTreeLauncher.launch(null)
                        }
                    },
                    enabled = !isPacking,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (selectedFolderName != null) "Folder: $selectedFolderName" else "Select Folder",
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Convert to NUP Button
                Button(
                    onClick = {
                        val uri = selectedFolderUri ?: return@Button
                        val docDir = DocumentFile.fromTreeUri(context, uri) ?: return@Button
                        isPacking = true
                        progressFraction = 0f
                        progressText = "Preparing files..."
                        statusMessage = "Packing started..."

                        scope.launch(Dispatchers.IO) {
                            try {
                                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS
                                )
                                val safeName = (docDir.name ?: "game_dump").replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                                val outputFile = File(downloadsDir, "$safeName.nup")

                                NupPacker().pack(
                                    context = context,
                                    inputDir = docDir,
                                    outputFile = outputFile
                                ) { cur, total, fName ->
                                    val frac = if (total > 0) cur.toFloat() / total else 0f
                                    scope.launch(Dispatchers.Main) {
                                        progressFraction = frac
                                        progressText = "Packing ($cur / $total): $fName"
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    isPacking = false
                                    statusMessage = "Saved to Downloads: ${outputFile.name}"
                                    Toast.makeText(context, "Pack successful: ${outputFile.name}", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Throwable) {
                                Log.e("NupPacker", "Error during pack", e)
                                withContext(Dispatchers.Main) {
                                    isPacking = false
                                    statusMessage = "Error: ${e.localizedMessage ?: e.message}"
                                    Toast.makeText(context, "Pack failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = selectedFolderUri != null && !isPacking,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isPacking) "Packaging in progress..." else "Pack to .nup",
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                if (isPacking) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progressText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                if (statusMessage.isNotEmpty() && !isPacking) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = statusMessage,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
