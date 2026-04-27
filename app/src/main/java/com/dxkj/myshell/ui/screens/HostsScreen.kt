package com.dxkj.myshell.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
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
import com.dxkj.myshell.ui.theme.Dimens
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.HostRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
fun HostsScreen(
    contentPadding: PaddingValues,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onOpenSession: (Long) -> Unit,
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val context = LocalContext.current
    val vm: HostsViewModel = viewModel(
        factory = HostsViewModel.factory(context.applicationContext as Application),
    )
    val hosts by vm.hosts.collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(hosts, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) hosts else hosts.filter {
            it.name.lowercase().contains(q) ||
                it.host.lowercase().contains(q) ||
                it.username.lowercase().contains(q)
        }
    }

    var pendingDelete by remember { mutableStateOf<HostEntity?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dimens.ScreenPaddingH,
                        vertical = if (isLandscape) Dimens.SpacingSm else Dimens.SpacingMd,
                    ),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "search") },
                label = { Text("搜索主机") },
            )

            if (hosts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Dimens.ScreenPaddingH),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpacingMd, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "还没有主机", style = MaterialTheme.typography.titleLarge)
                Text(text = "点击右下角 + 新增一个 SSH 主机")
            }
            } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.SpacingMd, vertical = Dimens.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
            ) {
                items(filtered, key = { it.id }) { item ->
                    HostRow(
                        host = item,
                        onClick = { onOpenSession(item.id) },
                        onEdit = { onEditHost(item.id) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
            }
        }

        FloatingActionButton(
            onClick = onAddHost,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Dimens.ScreenPaddingH),
        ) {
            Icon(imageVector = Icons.Outlined.Add, contentDescription = "add")
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除主机？") },
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
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HostRow(
    host: HostEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = host.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${host.username}@${host.host}:${host.port}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(imageVector = Icons.Outlined.Edit, contentDescription = "edit")
        }
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "delete")
        }
    }
}

class HostsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HostRepository(DbProvider.get(app).hostDao())

    val hosts: StateFlow<List<HostEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch {
            repo.deleteById(id)
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HostsViewModel(app) as T
                }
            }
    }
}

