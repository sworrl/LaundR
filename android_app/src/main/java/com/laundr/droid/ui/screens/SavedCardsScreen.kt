package com.laundr.droid.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.laundr.droid.nfc.CardRepository
import com.laundr.droid.nfc.SavedCard
import com.laundr.droid.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedCardsScreen(
    cardRepository: CardRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedCards by cardRepository.savedCards.collectAsState()

    var expandedCardUid by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<SavedCard?>(null) }
    var showRenameDialog by remember { mutableStateOf<SavedCard?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
                if (content != null) {
                    scope.launch {
                        val imported = if (content.startsWith("Filetype: Flipper")) {
                            val card = cardRepository.importFromFlipperFormat(content)
                            if (card != null) 1 else 0
                        } else {
                            cardRepository.importFromJson(content)
                        }
                        Toast.makeText(context, "Imported $imported card(s)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Saved Cards")
                        Text(
                            text = "${savedCards.size} cards",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberGreen
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Import button
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Import")
                    }
                    // Export all button
                    IconButton(
                        onClick = {
                            scope.launch {
                                val file = cardRepository.exportAllCards()
                                Toast.makeText(context, "Exported to ${file.name}", Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = savedCards.isNotEmpty()
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Export All")
                    }
                }
            )
        }
    ) { padding ->
        if (savedCards.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.CreditCardOff,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Text(
                        "No saved cards",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        "Scan NFC cards to save them here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    OutlinedButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import Cards")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(savedCards, key = { it.uid }) { card ->
                    SavedCardItem(
                        card = card,
                        isExpanded = expandedCardUid == card.uid,
                        onClick = {
                            expandedCardUid = if (expandedCardUid == card.uid) null else card.uid
                        },
                        onRename = {
                            renameText = card.nickname ?: ""
                            showRenameDialog = card
                        },
                        onDelete = { showDeleteDialog = card },
                        onExportFlipper = {
                            scope.launch {
                                val file = cardRepository.exportToFlipperFormat(card)
                                Toast.makeText(context, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onExportJson = {
                            scope.launch {
                                val file = cardRepository.exportToJson(card)
                                Toast.makeText(context, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onShare = {
                            val shareText = cardRepository.getShareText(card)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "LaunDRoid Card: ${card.displayName}")
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Card"))
                        },
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val shareText = cardRepository.getShareText(card)
                            clipboard.setPrimaryClip(ClipData.newPlainText("Card", shareText))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { card ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Card?") },
            text = { Text("Delete ${card.displayName}?\n\nThis cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        cardRepository.deleteCard(card.uid)
                        showDeleteDialog = null
                        Toast.makeText(context, "Card deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = CyberRed)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename dialog
    showRenameDialog?.let { card ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Card") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        cardRepository.setNickname(card.uid, renameText.takeIf { it.isNotBlank() })
                        showRenameDialog = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SavedCardItem(
    card: SavedCard,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExportFlipper: () -> Unit,
    onExportJson: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (card.isCSC)
                CyberGreen.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            // Card header - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Card icon with status
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = if (card.isCSC)
                                        listOf(CyberGreen.copy(alpha = 0.3f), CyberBlue.copy(alpha = 0.3f))
                                    else
                                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f))
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (card.isCSC) Icons.Default.LocalLaundryService else Icons.Default.CreditCard,
                            null,
                            tint = if (card.isCSC) CyberGreen else MaterialTheme.colorScheme.primary
                        )
                    }

                    Column {
                        Text(
                            text = card.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = card.uid.chunked(2).joinToString(":"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    card.balanceFormatted?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${card.crackedSectors}/${card.totalSectors} sectors",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (card.crackedSectors == card.totalSectors)
                            CyberGreen else CyberOrange
                    )
                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Divider(modifier = Modifier.padding(bottom = 8.dp))

                    // Card details
                    DetailRow("Type", card.type)
                    card.provider?.let { DetailRow("Provider", it) }
                    DetailRow("Saved", formatTimestamp(card.timestamp))
                    DetailRow("Cracked", "${card.crackedSectors}/${card.totalSectors} sectors")

                    Spacer(Modifier.height(12.dp))

                    // Keys section
                    Text(
                        "Sector Keys",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))

                    val authenticatedSectors = card.sectors.filter { it.authenticated }
                    if (authenticatedSectors.isNotEmpty()) {
                        authenticatedSectors.take(5).forEach { sector ->
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "S${sector.index}:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                sector.keyA?.let {
                                    Text(
                                        "A:$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = CyberOrange
                                    )
                                }
                                sector.keyB?.let {
                                    Text(
                                        "B:$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = CyberBlue
                                    )
                                }
                            }
                        }
                        if (authenticatedSectors.size > 5) {
                            Text(
                                "... and ${authenticatedSectors.size - 5} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    } else {
                        Text(
                            "No keys found",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberRed
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Action buttons row 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExportFlipper,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(".nfc", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = onExportJson,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Code, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(".json", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = onShare,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Action buttons row 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCopy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = onRename,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rename", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberRed)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatTimestamp(ts: Long): String {
    return SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(ts))
}
