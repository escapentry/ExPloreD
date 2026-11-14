package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.files.FileEntry
import com.example.files.PermissionBits

@Composable
fun PermissionsDialog(
  entry: FileEntry,
  isSuActive: Boolean,
  onApply: (octal: String, recursive: Boolean, ownerGroup: String?) -> Unit,
  onDismiss: () -> Unit,
) {
  val initialBits = remember(entry.path) {
    PermissionBits.fromOctal(entry.permissionsOctal)
      ?: PermissionBits.fromSymbolic(entry.permissionsSymbolic)
  }
  var bits by remember(entry.path) { mutableStateOf(initialBits) }
  var octalText by remember(entry.path) { mutableStateOf(initialBits.toOctalString()) }
  var recursive by remember(entry.path) { mutableStateOf(false) }
  val initialOwner = remember(entry.path) {
    if (entry.owner.isNotBlank() && entry.group.isNotBlank()) "${entry.owner}:${entry.group}" else ""
  }
  var ownerGroupText by remember(entry.path) { mutableStateOf(initialOwner) }

  val isOctalValid = PermissionBits.fromOctal(octalText) != null

  fun updateBits(next: PermissionBits) {
    bits = next
    octalText = next.toOctalString()
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      tonalElevation = 8.dp,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("permissions_dialog"),
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "Permissions",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = entry.name,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        Column(
          modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

        // Octal input + live symbolic readout
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          OutlinedTextField(
            value = octalText,
            onValueChange = { raw ->
              val filtered = raw.filter { it in '0'..'7' }.take(4)
              octalText = filtered
              PermissionBits.fromOctal(filtered)?.let { parsed ->
                bits = parsed
              }
            },
            label = { Text("Octal") },
            singleLine = true,
            isError = !isOctalValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
              .weight(1f)
              .testTag("chmod_octal_input"),
          )

          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                text = "Symbolic",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = bits.toSymbolicString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("chmod_symbolic_preview"),
              )
            }
          }
        }

        // Quick octal presets
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf("0644", "0755", "0600", "0777", "4755").forEach { preset ->
            AssistChip(
              onClick = {
                PermissionBits.fromOctal(preset)?.let { updateBits(it) }
              },
              label = { Text(preset, fontFamily = FontFamily.Monospace) },
              modifier = Modifier.testTag("chmod_preset_$preset"),
            )
          }
        }

        // Checkboxes grid (Owner / Group / Others)
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Spacer(modifier = Modifier.weight(1.2f))
              Text("Read", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
              Text("Write", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
              Text("Exec", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            }

            PermRow(
              label = "Owner",
              r = bits.ownerRead,
              w = bits.ownerWrite,
              x = bits.ownerExecute,
              tagPrefix = "owner",
              onR = { updateBits(bits.copy(ownerRead = it)) },
              onW = { updateBits(bits.copy(ownerWrite = it)) },
              onX = { updateBits(bits.copy(ownerExecute = it)) },
            )
            PermRow(
              label = "Group",
              r = bits.groupRead,
              w = bits.groupWrite,
              x = bits.groupExecute,
              tagPrefix = "group",
              onR = { updateBits(bits.copy(groupRead = it)) },
              onW = { updateBits(bits.copy(groupWrite = it)) },
              onX = { updateBits(bits.copy(groupExecute = it)) },
            )
            PermRow(
              label = "Others",
              r = bits.otherRead,
              w = bits.otherWrite,
              x = bits.otherExecute,
              tagPrefix = "other",
              onR = { updateBits(bits.copy(otherRead = it)) },
              onW = { updateBits(bits.copy(otherWrite = it)) },
              onX = { updateBits(bits.copy(otherExecute = it)) },
            )
          }
        }

        // Special bits (SetUID / SetGID / Sticky)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          SpecialBitChip(
            label = "SUID",
            checked = bits.setUid,
            tag = "chmod_checkbox_suid",
            onToggle = { updateBits(bits.copy(setUid = it)) },
          )
          SpecialBitChip(
            label = "SGID",
            checked = bits.setGid,
            tag = "chmod_checkbox_sgid",
            onToggle = { updateBits(bits.copy(setGid = it)) },
          )
          SpecialBitChip(
            label = "Sticky",
            checked = bits.sticky,
            tag = "chmod_checkbox_sticky",
            onToggle = { updateBits(bits.copy(sticky = it)) },
          )
        }

        if (entry.isDirectory) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { recursive = !recursive }
              .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Apply recursively (-R)",
              style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
              checked = recursive,
              onCheckedChange = { recursive = it },
              modifier = Modifier.testTag("chmod_recursive_switch"),
            )
          }
        }

        if (isSuActive) {
          OutlinedTextField(
            value = ownerGroupText,
            onValueChange = { ownerGroupText = it },
            label = { Text("Owner:Group (chown)") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("chmod_chown_input"),
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            listOf("root:root", "system:system", "shell:shell", "media_rw:media_rw").forEach { preset ->
              AssistChip(
                onClick = { ownerGroupText = preset },
                label = { Text(preset, fontFamily = FontFamily.Monospace) },
              )
            }
          }
        }

        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("chmod_cancel_button"),
          ) {
            Text("Cancel")
          }
          Spacer(modifier = Modifier.width(6.dp))
          Button(
            enabled = isOctalValid,
            onClick = {
              val finalOctal = if (octalText.length == 3) "0$octalText" else octalText
              onApply(finalOctal, recursive, ownerGroupText.takeIf { it.isNotBlank() })
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.testTag("chmod_apply_button"),
          ) {
            Text("Apply")
          }
        }
      }
    }
  }
}

@Composable
private fun PermRow(
  label: String,
  r: Boolean,
  w: Boolean,
  x: Boolean,
  tagPrefix: String,
  onR: (Boolean) -> Unit,
  onW: (Boolean) -> Unit,
  onX: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1.2f),
    )
    Checkbox(
      checked = r,
      onCheckedChange = onR,
      colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
      modifier = Modifier
        .weight(1f)
        .testTag("chmod_checkbox_${tagPrefix}_r"),
    )
    Checkbox(
      checked = w,
      onCheckedChange = onW,
      colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
      modifier = Modifier
        .weight(1f)
        .testTag("chmod_checkbox_${tagPrefix}_w"),
    )
    Checkbox(
      checked = x,
      onCheckedChange = onX,
      colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
      modifier = Modifier
        .weight(1f)
        .testTag("chmod_checkbox_${tagPrefix}_x"),
    )
  }
}

@Composable
private fun SpecialBitChip(
  label: String,
  checked: Boolean,
  tag: String,
  onToggle: (Boolean) -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.clickable { onToggle(!checked) },
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = onToggle,
      modifier = Modifier.testTag(tag),
    )
    Text(text = label, style = MaterialTheme.typography.labelSmall)
  }
}
