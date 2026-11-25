package id.vern.antigravity.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.vern.antigravity.root.RootFileEntry
import id.vern.antigravity.root.RootNetHunterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class RootPickerMode {
    FILES,
    FOLDER
}

@Composable
fun RootFilePickerDialog(
    mode: RootPickerMode,
    initialDirectory: File,
    onDismiss: () -> Unit,
    onFolderSelected: ((File) -> Unit)? = null,
    onFilesSelected: ((List<File>) -> Unit)? = null,
    onOpenAndroidSystemPicker: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val chrootPath = remember {
        RootNetHunterManager.cachedStatus?.chrootPath
            ?: RootNetHunterManager.getSavedChrootPath(context)
            ?: "/data/local/nhsystem/kali-arm64"
    }
    var currentDir by remember(initialDirectory) { mutableStateOf(initialDirectory) }
    var entries by remember { mutableStateOf<List<RootFileEntry>>(emptyList()) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val quickLocations = remember(chrootPath) {
        buildList {
            val chrootFile = File(chrootPath)
            if (RootNetHunterManager.isDirectoryWithRootFallback(chrootFile)) {
                add("Kali /root" to File(chrootFile, "root"))
                add("Kali /home" to File(chrootFile, "home"))
                add("Kali /var/www" to File(chrootFile, "var/www"))
                add("Kali /" to chrootFile)
            }
            add("Antigravity" to RootNetHunterManager.getDefaultWorkspaceDir())
            add("/sdcard" to File("/storage/emulated/0"))
            add("Downloads" to File("/storage/emulated/0/Download"))
            add("App Home" to File(context.filesDir, "home"))
        }
    }

    LaunchedEffect(currentDir, refreshTrigger) {
        isLoading = true
        entries = withContext(Dispatchers.IO) {
            RootNetHunterManager.listDirectoryWithRootFallback(currentDir)
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        }
        isLoading = false
    }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentDir.parentFile != null) {
                                IconButton(
                                    onClick = { currentDir.parentFile?.let { currentDir = it } },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(18.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                Text(
                                    text = if (mode == RootPickerMode.FOLDER) "Select Kali / Storage Folder" else "Select Kali / Storage Files",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = currentDir.absolutePath,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (mode == RootPickerMode.FOLDER) {
                                IconButton(
                                    onClick = {
                                        newFolderName = ""
                                        showNewFolderDialog = true
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            quickLocations.forEach { (label, dir) ->
                                val active = currentDir.absolutePath == dir.absolutePath
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.clickable { currentDir = dir }
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter files or folders...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(filteredEntries, key = { it.absolutePath }) { entry ->
                        val checked = selectedPaths.contains(entry.absolutePath)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        currentDir = File(entry.absolutePath)
                                        searchQuery = ""
                                    } else if (mode == RootPickerMode.FILES) {
                                        selectedPaths = if (checked) {
                                            selectedPaths - entry.absolutePath
                                        } else if (selectedPaths.size < 5) {
                                            selectedPaths + entry.absolutePath
                                        } else {
                                            Toast.makeText(context, "Max 5 files", Toast.LENGTH_SHORT).show()
                                            selectedPaths
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (entry.isDirectory) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!entry.isDirectory) {
                                    Text(
                                        text = "${entry.sizeBytes} bytes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (mode == RootPickerMode.FILES && !entry.isDirectory) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedPaths = if (isChecked && selectedPaths.size < 5) {
                                            selectedPaths + entry.absolutePath
                                        } else {
                                            selectedPaths - entry.absolutePath
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onOpenAndroidSystemPicker != null) {
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onOpenAndroidSystemPicker()
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Android Picker", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (mode == RootPickerMode.FOLDER) {
                            Button(
                                onClick = {
                                    onFolderSelected?.invoke(currentDir)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Use This Folder")
                            }
                        } else {
                            Button(
                                onClick = {
                                    onFilesSelected?.invoke(selectedPaths.map { File(it) })
                                    onDismiss()
                                },
                                enabled = selectedPaths.isNotEmpty(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Attach (${selectedPaths.size})")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newFolderName.trim()
                        if (trimmed.isNotEmpty()) {
                            val newDir = File(currentDir, trimmed)
                            if (RootNetHunterManager.createDirectoryWithRootFallback(newDir)) {
                                currentDir = newDir
                                refreshTrigger++
                            } else {
                                Toast.makeText(context, "Failed to create folder", Toast.LENGTH_SHORT).show()
                            }
                            showNewFolderDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}
