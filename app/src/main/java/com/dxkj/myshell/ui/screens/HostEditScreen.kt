package com.dxkj.myshell.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dxkj.myshell.ui.theme.Dimens
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.crypto.CryptoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.dxkj.myshell.ssh.SshTestClient
import com.dxkj.myshell.ssh.SshTestInput
import com.dxkj.myshell.ssh.SshTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    contentPadding: PaddingValues,
    hostId: Long?,
    onDone: () -> Unit,
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val context = LocalContext.current
    val vm: HostEditViewModel = viewModel(
        factory = HostEditViewModel.factory(context.applicationContext as Application),
    )

    LaunchedEffect(hostId) {
        vm.load(hostId)
    }

    val ui by vm.ui.collectAsState()
    val keys by vm.keys.collectAsState()
    val scrollState = rememberScrollState()

    val formTextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val compactFieldMinHeight = 44.dp
    val labelWidth = 86.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(scrollState)
            .padding(
                horizontal = Dimens.ScreenPaddingH,
                vertical = if (isLandscape) 12.dp else Dimens.ScreenPaddingV,
            ),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) Dimens.SpacingSm else Dimens.SpacingXs),
    ) {
        Text(
            text = if (hostId == null) "新增主机" else "编辑主机",
            style = MaterialTheme.typography.titleLarge,
        )

        // 行式输入：标签: 输入框
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
        ) {
            Text("名称:", style = formTextStyle, modifier = Modifier.width(labelWidth))
            OutlinedTextField(
                value = ui.name,
                onValueChange = { vm.update { copy(name = it) } },
                modifier = Modifier.weight(1f).heightIn(min = compactFieldMinHeight),
                singleLine = true,
                textStyle = formTextStyle,
            )
        }
        Spacer(Modifier.height(Dimens.Spacing2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
        ) {
            Text("主机地址:", style = formTextStyle, modifier = Modifier.width(labelWidth))
            OutlinedTextField(
                value = ui.host,
                onValueChange = { vm.update { copy(host = it) } },
                modifier = Modifier.weight(1f).heightIn(min = compactFieldMinHeight),
                singleLine = true,
                textStyle = formTextStyle,
            )
        }
        Spacer(Modifier.height(Dimens.Spacing2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
        ) {
            Text("端口:", style = formTextStyle, modifier = Modifier.width(labelWidth))
            OutlinedTextField(
                value = ui.port,
                onValueChange = { vm.update { copy(port = it) } },
                modifier = Modifier.width(140.dp).heightIn(min = compactFieldMinHeight),
                singleLine = true,
                textStyle = formTextStyle,
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(Dimens.Spacing2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
        ) {
            Text("用户名:", style = formTextStyle, modifier = Modifier.width(labelWidth))
            OutlinedTextField(
                value = ui.username,
                onValueChange = { vm.update { copy(username = it) } },
                modifier = Modifier.weight(1f).heightIn(min = compactFieldMinHeight),
                singleLine = true,
                textStyle = formTextStyle,
            )
        }

        Text(text = "认证方式", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = ui.authType == "password",
                onClick = { vm.update { copy(authType = "password") } },
            )
            Text("密码", style = formTextStyle)
            RadioButton(
                selected = ui.authType == "key",
                onClick = { vm.update { copy(authType = "key") } },
                modifier = Modifier.padding(start = Dimens.SpacingLg),
            )
            Text("密钥", style = formTextStyle)
        }

        if (ui.authType == "password") {
        Spacer(Modifier.height(Dimens.Spacing2))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
            ) {
                Text("密码:", style = formTextStyle, modifier = Modifier.width(labelWidth))
                OutlinedTextField(
                    value = ui.password,
                    onValueChange = { vm.update { copy(password = it) } },
                    modifier = Modifier.weight(1f).heightIn(min = compactFieldMinHeight),
                    singleLine = true,
                    textStyle = formTextStyle,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            val selectedKey = keys.firstOrNull { it.id == ui.privateKeyId }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
            ) {
            Spacer(Modifier.height(Dimens.Spacing2))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
                ) {
                    Text("密钥:", style = formTextStyle, modifier = Modifier.width(labelWidth))
                    OutlinedTextField(
                        value = selectedKey?.name ?: if (keys.isEmpty()) "（暂无密钥，请先去「密钥」页导入）" else "请选择密钥",
                        onValueChange = {},
                        readOnly = true,
                        enabled = keys.isNotEmpty() && !ui.saving && !ui.testing,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = compactFieldMinHeight)
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        textStyle = formTextStyle,
                        supportingText = {
                            if (ui.privateKeyId == null) {
                                Text("密钥认证需要选择一个已导入的私钥", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    keys.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k.name) },
                            onClick = {
                                vm.update { copy(privateKeyId = k.id) }
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.testing) {
                CircularProgressIndicator()
            }
            Button(
                onClick = { vm.testConnection(hostId) },
                enabled = !ui.saving && !ui.testing,
            ) {
                Text(if (ui.testing) "测试中…" else "测试连接")
            }
        }

        if (ui.error != null) {
            Text(
                text = ui.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ui.testResult != null) {
            Text(
                text = ui.testResult ?: "",
                color = if (ui.testResultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm, Alignment.End),
        ) {
            Button(
                onClick = { onDone() },
                enabled = !ui.saving,
            ) {
                Text("取消")
            }
            Button(
                onClick = { vm.save(hostId, onDone) },
                enabled = !ui.saving,
            ) {
                Text(if (ui.saving) "保存中…" else "保存")
            }
        }
    }
}

data class HostEditUi(
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: String = "password",
    val password: String = "",
    val privateKeyId: Long? = null,
    val error: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testResultOk: Boolean = false,
    val saving: Boolean = false,
)

class HostEditViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val repo = HostRepository(DbProvider.get(app).hostDao())
    private val keyRepo = KeyRepository(DbProvider.get(app).keyDao())
    val keys = keyRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _ui = MutableStateFlow(HostEditUi())
    val ui: StateFlow<HostEditUi> = _ui.asStateFlow()

    fun update(block: HostEditUi.() -> HostEditUi) {
        _ui.update { it.block().copy(error = null, testResult = null) }
    }

    fun load(hostId: Long?) {
        if (hostId == null) {
            _ui.value = HostEditUi()
            return
        }
        viewModelScope.launch {
            val entity = repo.getById(hostId)
            if (entity == null) {
                _ui.update { it.copy(error = "主机不存在") }
                return@launch
            }
            _ui.value = HostEditUi(
                name = entity.name,
                host = entity.host,
                port = entity.port.toString(),
                username = entity.username,
                authType = entity.authType,
                password = CryptoManager.decryptFromBase64(entity.passwordEnc).orEmpty(),
                privateKeyId = entity.privateKeyId,
            )
        }
    }

    fun save(hostId: Long?, onDone: () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            try {
                val now = System.currentTimeMillis()
                repo.upsert(
                    id = hostId,
                    name = ui.value.name,
                    host = ui.value.host,
                    port = ui.value.port.trim().toInt(),
                    username = ui.value.username,
                    authType = ui.value.authType,
                    password = ui.value.password,
                    privateKeyId = ui.value.privateKeyId,
                    nowEpochMs = now,
                )
                onDone()
            } catch (t: Throwable) {
                _ui.update { it.copy(error = t.message ?: "保存失败", saving = false) }
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    fun testConnection(hostId: Long?) {
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, error = null, testResult = null) }
            val snapshot = ui.value

            val keyMaterial = if (snapshot.authType == "key") {
                val keyId = snapshot.privateKeyId
                if (keyId == null) {
                    _ui.update { it.copy(testing = false, testResult = "请选择密钥", testResultOk = false) }
                    return@launch
                }
                keyRepo.getDecryptedById(keyId)
            } else {
                null
            }

            val result = withContext(Dispatchers.IO) {
                SshTestClient.testConnection(
                    SshTestInput(
                        host = snapshot.host,
                        port = snapshot.port.trim().toIntOrNull() ?: -1,
                        username = snapshot.username,
                        authType = snapshot.authType,
                        password = snapshot.password,
                        privateKeyPem = keyMaterial?.privateKeyPem,
                        passphrase = keyMaterial?.passphrase,
                    ),
                )
            }
            when (result) {
                is SshTestResult.Success -> {
                    // Keep HostEdit and Terminal/SFTP in sync: if editing an existing host, persist fields.
                    var savedOk = false
                    var savedErr: String? = null
                    if (hostId != null) {
                        try {
                            repo.upsert(
                                id = hostId,
                                name = snapshot.name,
                                host = snapshot.host,
                                port = snapshot.port.trim().toIntOrNull() ?: -1,
                                username = snapshot.username,
                                authType = snapshot.authType,
                                password = snapshot.password,
                                privateKeyId = snapshot.privateKeyId,
                                nowEpochMs = System.currentTimeMillis(),
                            )
                            savedOk = true
                        } catch (t: Throwable) {
                            savedOk = false
                            savedErr = t.message ?: "保存失败"
                        }
                    }
                    _ui.update {
                        it.copy(
                            testResult = when {
                                hostId == null -> "连接成功（未保存）"
                                savedOk -> "连接成功（已同步保存）"
                                else -> "连接成功（但同步保存失败：${savedErr ?: "未知原因"}）"
                            },
                            testResultOk = true,
                            testing = false,
                        )
                    }
                }
                is SshTestResult.Failure -> _ui.update {
                    it.copy(
                        testResult = "连接失败：${result.message}",
                        testResultOk = false,
                        testing = false,
                    )
                }
            }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HostEditViewModel(app) as T
                }
            }
    }
}

