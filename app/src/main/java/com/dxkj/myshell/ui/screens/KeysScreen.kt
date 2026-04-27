package com.dxkj.myshell.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.db.KeyEntity
import com.dxkj.myshell.data.repo.KeyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import android.content.Intent

@Composable
fun KeysScreen(contentPadding: PaddingValues) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val context = LocalContext.current
    val vm: KeysViewModel = viewModel(factory = KeysViewModel.factory(context.applicationContext as Application))
    val keys by vm.keys.collectAsState()

    var pendingDelete by remember { mutableStateOf<KeyEntity?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importedPem by remember { mutableStateOf<String?>(null) }
    var importedName by remember { mutableStateOf("") }
    var importedPassphrase by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取文件")
            val text = bytes.toString(Charset.forName("UTF-8"))
            importedPem = text
            importedName = uri.lastPathSegment?.take(40) ?: "导入的密钥"
            importedPassphrase = ""
            importError = null
            showImportDialog = true
        } catch (t: Throwable) {
            importError = t.message ?: "导入失败"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (keys.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "还没有密钥", style = MaterialTheme.typography.titleLarge)
                Text(text = "点击右下角 + 导入私钥（OpenSSH/PEM）")
                if (importError != null) {
                    Text(text = importError ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(keys, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = if (isLandscape) 8.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "id=${item.id}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { pendingDelete = item }) {
                            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "delete")
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(imageVector = Icons.Outlined.Add, contentDescription = "import")
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除密钥？") },
            text = { Text(pendingDelete?.name ?: "") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = pendingDelete?.id
                        pendingDelete = null
                        if (id != null) vm.delete(id)
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入私钥") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importedName,
                        onValueChange = { importedName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = importedPassphrase,
                        onValueChange = { importedPassphrase = it },
                        label = { Text("Passphrase（如果有）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importError != null) {
                        Text(text = importError ?: "", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pem = importedPem
                        if (pem == null) {
                            importError = "未读取到私钥内容"
                        } else {
                            vm.importKey(
                                name = importedName,
                                pem = pem,
                                passphrase = importedPassphrase,
                                onOk = {
                                    showImportDialog = false
                                    importError = null
                                },
                                onErr = { msg -> importError = msg },
                            )
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } },
        )
    }
}

class KeysViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = KeyRepository(DbProvider.get(app).keyDao())

    val keys: StateFlow<List<KeyEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteById(id) }
    }

    fun importKey(
        name: String,
        pem: String,
        passphrase: String?,
        onOk: () -> Unit,
        onErr: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                repo.insert(
                    name = name,
                    privateKeyPem = pem,
                    passphrase = passphrase,
                    nowEpochMs = System.currentTimeMillis(),
                )
                onOk()
            } catch (t: Throwable) {
                onErr(t.message ?: "保存失败")
            }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return KeysViewModel(app) as T
                }
            }
    }
}

