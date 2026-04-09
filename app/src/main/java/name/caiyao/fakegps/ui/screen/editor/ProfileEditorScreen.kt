package name.caiyao.fakegps.ui.screen.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profileId: Long,
    lat: Double,
    lon: Double,
    onBack: () -> Unit,
    vm: ProfileEditorViewModel = viewModel(),
) {
    val isNew = profileId == -1L || profileId == 0L
    val fieldValues by vm.fieldValues.collectAsState()
    val saved by vm.saved.collectAsState()

    LaunchedEffect(profileId) {
        vm.load(if (isNew) -1L else profileId, lat, lon)
    }

    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建档案" else "编辑档案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.save() }) {
                Icon(Icons.Default.Save, contentDescription = "保存")
            }
        },
    ) { innerPadding ->
        val categories = remember { FieldSpec.allCategories() }
        val expandedState = remember { mutableStateMapOf<String, Boolean>() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((category, fields) in categories) {
                val expanded = expandedState[category] ?: (category == "定位")
                val activeCount = fields.count { fieldValues.containsKey(it.dbColumn) }

                CategoryCard(
                    title = category,
                    activeCount = activeCount,
                    totalCount = fields.size,
                    expanded = expanded,
                    onToggle = { expandedState[category] = !expanded },
                ) {
                    for (spec in fields) {
                        val value = fieldValues[spec.dbColumn] ?: ""
                        when (spec.type) {
                            FieldType.BOOLEAN -> BooleanField(
                                spec = spec,
                                value = value,
                                onValueChange = { vm.updateField(spec.dbColumn, it) },
                            )
                            else -> TextField(
                                spec = spec,
                                value = value,
                                onValueChange = { vm.updateField(spec.dbColumn, it) },
                            )
                        }
                    }
                }
            }
            // Bottom padding for FAB clearance
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.padding(bottom = 72.dp)
            )
        }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    activeCount: Int,
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (activeCount > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text("$activeCount/$totalCount")
                    }
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun TextField(
    spec: FieldSpec,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val keyboardType = when (spec.type) {
        FieldType.INTEGER -> KeyboardType.Number
        FieldType.DOUBLE, FieldType.FLOAT -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }

    val label = buildString {
        append(spec.displayName)
        if (spec.unit != null) append(" (${spec.unit})")
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(spec.hint) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BooleanField(
    spec: FieldSpec,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("" to "透传", "1" to "是", "0" to "否")
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: "透传"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(spec.displayName) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((optValue, label) in options) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onValueChange(optValue)
                        expanded = false
                    },
                )
            }
        }
    }
}
