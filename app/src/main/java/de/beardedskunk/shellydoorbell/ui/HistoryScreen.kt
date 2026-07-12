@file:OptIn(ExperimentalMaterial3Api::class)

package de.beardedskunk.shellydoorbell.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.beardedskunk.shellydoorbell.data.AppDb

/** Komplette lokale Klingel-History (Room), nach Tagen gruppiert. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).ringDao() }
    val all by dao.all().collectAsState(initial = emptyList())
    val grouped = remember(all) { all.groupBy { Fmt.localDate(it.ts) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verlauf (${all.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            if (all.isEmpty()) {
                item {
                    Text(
                        "Noch keine Klingel-Ereignisse aufgezeichnet.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            grouped.forEach { (date, events) ->
                item(key = "header-$date") {
                    Text(
                        Fmt.date(date),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(events, key = { it.ts }) { event ->
                    ListItem(
                        headlineContent = { Text("${Fmt.time(event.ts)} Uhr") },
                        trailingContent = { event.power?.let { Text(Fmt.watts(it)) } },
                    )
                }
            }
        }
    }
}
