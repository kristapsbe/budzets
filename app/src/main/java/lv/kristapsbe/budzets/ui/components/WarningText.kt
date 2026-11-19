package lv.kristapsbe.budzets.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * [text], or with [warning] the same in red behind a warning icon. The words always say what is
 * wrong ("Over by …", "Took …"), so colour is never the only signal.
 */
@Composable
fun WarningText(text: String, warning: Boolean, style: TextStyle = LocalTextStyle.current) {
    if (!warning) {
        Text(text, style = style)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = style, color = MaterialTheme.colorScheme.error)
    }
}
