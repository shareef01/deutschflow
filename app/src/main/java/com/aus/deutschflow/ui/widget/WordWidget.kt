package com.aus.deutschflow.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import com.aus.deutschflow.MainActivity
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.firstOrNull

class WordWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .vocabularyDao()
        val vocab = dao.getAllVocabulary().firstOrNull()?.randomOrNull()

        provideContent {
            WordWidgetContent(vocab)
        }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun WordWidgetContent(vocab: VocabularyEntity?) {
        // High-Contrast Dark Theme Colors
        val backgroundColor = Color(0xFF121212)
        
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "WORD OF THE DAY",
                style = TextStyle(
                    color = ColorProvider(R.color.primary_blue),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            
            Spacer(modifier = GlanceModifier.height(8.dp))
            
            Text(
                text = vocab?.germanText ?: "Öffne die App",
                style = TextStyle(
                    color = ColorProvider(R.color.on_background_light),
                    fontSize = 22.sp, 
                    fontWeight = FontWeight.Bold
                )
            )
            
            if (vocab != null) {
                Text(
                    text = vocab.englishTranslation,
                    style = TextStyle(
                        color = ColorProvider(R.color.on_surface_variant),
                        fontSize = 14.sp
                    )
                )
            } else {
                Text(
                    text = "Lerne jetzt Deutsch!",
                    style = TextStyle(
                        color = ColorProvider(R.color.on_surface_variant),
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}

class WordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WordWidget()
}
