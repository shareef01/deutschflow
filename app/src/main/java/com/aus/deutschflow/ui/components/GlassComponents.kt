package com.aus.deutschflow.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aus.deutschflow.ui.theme.ActionButtonHeight
import com.aus.deutschflow.ui.theme.AzureDeep
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.GlassFill
import com.aus.deutschflow.ui.theme.GlassShape
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassBorderBrush
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.theme.pressScale
import com.aus.deutschflow.ui.theme.rememberPressSource

/**
 * A glassmorphic card: the app's one surface treatment, as a named component.
 *
 * Screens had been painting `.glassSurface()` by hand, which is why a card and the
 * search bar beside it could disagree on corner radius and edge. This is the single
 * place the fill, border and corner live, so a screen picks a role instead of a
 * number. The border is [glassBorderBrush] - the same stroke the inputs use.
 *
 * Modifier order here is the one the whole app obeys: clip -> background -> border ->
 * padding. Content padding sits *inside* the border so the edge never overlaps text.
 */
@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    fill: Color = GlassFill,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .glassSurface(shape = shape, fill = fill)
            .padding(contentPadding)
    ) {
        content()
    }
}

/**
 * The one input container in the app.
 *
 * History, Library and Settings each carried a slightly different `OutlinedTextField`.
 * This is the one input: the same opaque fill as a card, the same hairline edge, the
 * same corner — and the edge turns up to the calm cyan while focused, which is the
 * only accent an input carries.
 *
 * Built on [BasicTextField] rather than Material's outlined field because the latter
 * draws its own solid border. Everything a caller needs - masking, keyboard options,
 * leading/trailing slots - is passed through unchanged, so the Settings password
 * field and the search bars are the same control.
 *
 * The placeholder is shown only while [value] is empty, and reads the *raw* value, so
 * a masked password still hides its hint the moment a character is typed.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: Shape = MaterialTheme.shapes.medium,
    minHeight: Dp = 56.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Focus turns the edge up; the fill stays put.
    val edge = if (isFocused) glassBorderBrush(AzureGlow, alpha = 0.55f)
        else glassBorderBrush(AzureDeep, alpha = 0.16f)

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.sm)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(GlassFill, shape)
                .border(BorderStroke(1.dp, edge), shape)
                .padding(horizontal = Spacing.md),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.heightIn(min = minHeight),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = textStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                if (trailingIcon != null) {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    trailingIcon()
                }
            }
        }
    }
}

/**
 * A [GlassTextField] fixed to the search role, so the two search bars are literally
 * the same component rather than two fields that merely look alike.
 */
@Composable
fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    trailingIcon: (@Composable () -> Unit)? = null
) {
    GlassTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingIcon = trailingIcon,
        singleLine = true
    )
}

/**
 * The app's one primary action button: a quiet tinted fill, a hairline edge in the
 * accent colour, a bold label.
 *
 * Colour is what marks this as the action to take — a faint fill of the same hue as
 * the edge, never a solid neon block. [glow] recolours the edge and the fill, so the
 * Practice screen's "Stop" passes the error colour and a recording still reads as
 * stop-the-world.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glow: Color = AzureDeep,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = rememberPressSource()

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .height(ActionButtonHeight)
            .pressScale(interactionSource),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, glassBorderBrush(glow, alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = glow.copy(alpha = 0.12f),
            contentColor = contentColor,
            disabledContainerColor = GlassFill,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        content()
    }
}
