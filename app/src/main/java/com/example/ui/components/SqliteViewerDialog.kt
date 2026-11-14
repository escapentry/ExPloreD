package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.files.SqliteDatabaseInfo

@Composable
fun SqliteViewerDialog(
  info: SqliteDatabaseInfo,
  onSelectTable: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.background,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      modifier = Modifier
        .fillMaxSize()
        .padding(14.dp)
        .testTag("sqlite_viewer_dialog"),
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
            shape = AppleSquircleShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.TableChart,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(8.dp),
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = info.entry.name,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${info.tables.size} table(s) · ${info.totalRows} row(s)",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          TextButton(onClick = onDismiss) {
            Text("Close")
          }
        }

        if (!info.error.isNullOrBlank()) {
          Text(
            text = info.error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        if (info.tables.isNotEmpty()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .testTag("sqlite_table_chips"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            info.tables.forEach { table ->
              FilterChip(
                selected = table == info.selectedTable,
                onClick = { onSelectTable(table) },
                label = { Text(table, fontFamily = FontFamily.Monospace) },
              )
            }
          }
        }

        val hScroll = rememberScrollState()
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        ) {
          if (info.columns.isEmpty()) {
            Column(
              modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                text = "No rows or tables to display",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            Column(modifier = Modifier.fillMaxSize()) {
              // Column header row
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(hScroll)
                  .padding(horizontal = 10.dp, vertical = 10.dp),
              ) {
                info.columns.forEach { col ->
                  Text(
                    text = col,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                      .width(140.dp)
                      .padding(end = 8.dp),
                  )
                }
              }
              HorizontalDivider(color = MaterialTheme.colorScheme.outline)

              LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(info.rows) { idx, row ->
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .horizontalScroll(hScroll)
                      .padding(horizontal = 10.dp, vertical = 8.dp),
                  ) {
                    row.forEach { cell ->
                      Text(
                        text = cell,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                          .width(140.dp)
                          .padding(end = 8.dp),
                      )
                    }
                  }
                  if (idx < info.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
