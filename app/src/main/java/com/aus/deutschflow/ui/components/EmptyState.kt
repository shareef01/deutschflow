package com.aus.deutschflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.deutschflow.ui.theme.OnSurfaceMuted

@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    description: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Refactored to perfectly circular background
        Box(
            modifier = Modifier
                .size(144.dp)
                // Solid, from the elevation ramp. At 0.4f alpha over a near-black
                // ground the disc behind the icon was all but invisible, so the icon
                // floated with nothing holding it.
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // A fifth larger, with the disc scaled to match so the ratio holds.
                modifier = Modifier.size(68.dp),
                // Flat, not alpha-dimmed, for the same reason OnSurfaceMuted exists:
                // alpha over a dark ground loses contrast faster than it looks like it should.
                tint = OnSurfaceMuted
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Purged ALL CAPS - using Title Case
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            letterSpacing = 0.5.sp
        )
        
        if (description != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}
