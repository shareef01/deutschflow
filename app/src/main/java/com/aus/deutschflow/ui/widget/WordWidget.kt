package com.aus.deutschflow.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
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

class WordWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val vocab = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .dailyWord()
            .today()

        provideContent {
            WordWidgetContent(vocab)
        }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun WordWidgetContent(vocab: VocabularyEntity?) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                // From the resource, like every other colour here and like the picker
                // preview beside it. This was a literal #121212, so when the app moved
                // to a true-black ground the widget kept the old grey while its own
                // preview - which reads the resource - went black. The two advertised
                // different products on the same screen.
                .background(ColorProvider(R.color.widget_background))
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                // The picker preview already reads this from resources; the widget
                // itself repeated it as a literal, so the two could drift apart.
                text = LocalContext.current.getString(R.string.widget_heading),
                style = TextStyle(
                    color = ColorProvider(R.color.widget_accent),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            
            Spacer(modifier = GlanceModifier.height(8.dp))
            
            Text(
                text = vocab?.germanText ?: LocalContext.current.getString(R.string.widget_empty_title),
                style = TextStyle(
                    color = ColorProvider(R.color.widget_on_background),
                    fontSize = 22.sp, 
                    fontWeight = FontWeight.Bold
                )
            )
            
            if (vocab != null) {
                Text(
                    text = vocab.englishTranslation,
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_on_background_variant),
                        fontSize = 14.sp
                    )
                )
            } else {
                Text(
                    text = LocalContext.current.getString(R.string.widget_empty_subtitle),
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_on_background_variant),
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
