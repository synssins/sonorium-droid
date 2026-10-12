package app.sonorium.ui.server

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.sonorium.ui.UiSize

/**
 * A text field at the UI standard's height (Field Standard 48 dp; Small 44 with [small]) instead of
 * Material's 56: the same parameters as OutlinedTextField, so screens swap it in one for one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    small: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val height = if (small) UiSize.SMALL else UiSize.STANDARD
    val vertical = if (small) 10.dp else 12.dp
    val shape = RoundedCornerShape(8.dp)
    val color = textStyle.color.takeOrElse { if (enabled) app.sonorium.ui.Palette.text else app.sonorium.ui.Palette.textMuted }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = height.dp).defaultMinSize(minWidth = 48.dp),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.merge(TextStyle(color = color)),
        cursorBrush = SolidColor(if (isError) app.sonorium.ui.Palette.danger else app.sonorium.ui.Palette.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = inner,
            enabled = enabled,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            isError = isError,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            colors = colors,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = vertical, bottom = vertical),
            container = {
                OutlinedTextFieldDefaults.Container(enabled = enabled, isError = isError, interactionSource = interaction, colors = colors, shape = shape)
            },
        )
    }
}

/**
 * [SField] for a TextFieldValue (cursor and selection kept by the caller); same height (Field Standard 48 dp; Small 44 with [small]) instead of
 * Material's 56: the same parameters as OutlinedTextField, so screens swap it in one for one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    small: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val height = if (small) UiSize.SMALL else UiSize.STANDARD
    val vertical = if (small) 10.dp else 12.dp
    val shape = RoundedCornerShape(8.dp)
    val color = textStyle.color.takeOrElse { if (enabled) app.sonorium.ui.Palette.text else app.sonorium.ui.Palette.textMuted }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = height.dp).defaultMinSize(minWidth = 48.dp),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.merge(TextStyle(color = color)),
        cursorBrush = SolidColor(if (isError) app.sonorium.ui.Palette.danger else app.sonorium.ui.Palette.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value.text,
            innerTextField = inner,
            enabled = enabled,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            isError = isError,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            colors = colors,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = vertical, bottom = vertical),
            container = {
                OutlinedTextFieldDefaults.Container(enabled = enabled, isError = isError, interactionSource = interaction, colors = colors, shape = shape)
            },
        )
    }
}

private fun androidx.compose.ui.graphics.Color.takeOrElse(block: () -> androidx.compose.ui.graphics.Color) =
    if (this == androidx.compose.ui.graphics.Color.Unspecified) block() else this
