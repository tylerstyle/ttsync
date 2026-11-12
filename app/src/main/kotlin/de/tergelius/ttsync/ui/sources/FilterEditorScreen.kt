package de.tergelius.ttsync.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.tergelius.ttsync.R
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.mapping.LabelColor
import de.tergelius.ttsync.mapping.LabelNames
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtCalendarUser
import de.tergelius.ttsync.timetree.TtLabel
import de.tergelius.ttsync.ui.common.BottomActionBar
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/** What the filter editor's controls do; [done] also handles back. */
class FilterEditorActions(
  val toggleLabel: (Long) -> Unit,
  val toggleMember: (Long) -> Unit,
  val includeText: (String) -> Unit,
  val excludeText: (String) -> Unit,
  val clear: () -> Unit,
  val done: () -> Unit,
)

/**
 * The full-screen filter editor of one TimeTree calendar (DESIGN.md section 7.2), shown by the
 * Sources screen in place of its list. Back and "Done" return to the list and keep the edit as a
 * draft; it is stored with the list's Save (or Next).
 */
@Composable
fun FilterEditorContent(state: FilterEditorState, actions: FilterEditorActions) {
  val resources = LocalResources.current
  TtScaffold(
    title = stringResource(R.string.filter_title),
    onBack = actions.done,
    bottomBar = {
      BottomActionBar {
        OutlinedButton(onClick = actions.clear, enabled = !state.isEmpty) {
          Text(stringResource(R.string.filter_clear))
        }
        Button(onClick = actions.done) { Text(stringResource(R.string.filter_done)) }
      }
    },
  ) {
    Text(
      stringResource(
        R.string.filter_intro,
        state.calendarName ?: stringResource(R.string.sources_unnamed, state.calendarId),
      ),
      style = MaterialTheme.typography.bodyLarge,
    )
    SectionCard(title = stringResource(R.string.filter_labels_title)) {
      if (state.labels.isEmpty()) {
        Hint(stringResource(R.string.filter_labels_empty))
      } else {
        Hint(stringResource(R.string.filter_labels_hint))
        state.labels.forEachIndexed { index, choice ->
          if (index > 0) RowDivider()
          val name = labelDisplayName(choice.label, resources)
          ChoiceRow(
            selected = choice.selected,
            title = name,
            onToggle = { actions.toggleLabel(choice.id) },
            leading = { LabelSwatch(choice.label, shownName = name) },
          )
        }
      }
    }
    SectionCard(title = stringResource(R.string.filter_attendees_title)) {
      if (state.members.isEmpty()) {
        Hint(stringResource(R.string.filter_attendees_empty))
      } else {
        Hint(stringResource(R.string.filter_attendees_hint))
        state.members.forEachIndexed { index, member ->
          if (index > 0) RowDivider()
          ChoiceRow(
            selected = member.selected,
            title =
              member.name?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.filter_member_unnamed),
            subtitle = if (member.left) stringResource(R.string.filter_member_left) else null,
            onToggle = { actions.toggleMember(member.userId) },
          )
        }
      }
    }
    SectionCard(title = stringResource(R.string.filter_text_title)) {
      val focus = LocalFocusManager.current
      TermsField(
        value = state.includeText,
        onValueChange = actions.includeText,
        label = stringResource(R.string.filter_include),
        imeAction = ImeAction.Next,
      )
      TermsField(
        value = state.excludeText,
        onValueChange = actions.excludeText,
        label = stringResource(R.string.filter_exclude),
        imeAction = ImeAction.Done,
        onDone = { focus.clearFocus() },
      )
      Hint(stringResource(R.string.filter_text_hint))
    }
    Hint(stringResource(R.string.filter_save_note))
  }
}

/** A checkbox row; the whole row toggles (one accessibility node: checkbox, swatch and texts). */
@Composable
private fun ChoiceRow(
  selected: Boolean,
  title: String,
  onToggle: () -> Unit,
  subtitle: String? = null,
  leading: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = 48.dp)
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = selected, onCheckedChange = null)
    if (leading != null) {
      Box(modifier = Modifier.padding(start = 12.dp)) { leading() }
    }
    Column(modifier = Modifier.padding(start = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (subtitle != null) {
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * The label's colour as a dot. Its content description is the colour's name ("Custom colour" for
 * one TimeTree doesn't name), except when the row already shows that name (an unnamed label).
 */
@Composable
private fun LabelSwatch(label: TtLabel, shownName: String) {
  val rgb = label.color ?: label.defaultColor
  val named = LabelNames.colorOf(label)
  val resources = LocalResources.current
  val description =
    when {
      rgb == null -> null
      named != null -> resources.getString(named.nameRes()).takeUnless { it == shownName }
      else -> resources.getString(R.string.filter_color_custom)
    }
  Box(
    modifier =
      Modifier.size(20.dp)
        .background(
          rgb?.let { Color(0xFF000000 or (it and 0xFFFFFF)) } ?: Color.Transparent,
          CircleShape,
        )
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        .then(
          if (description != null) Modifier.semantics { contentDescription = description }
          else Modifier
        )
  )
}

@Composable
private fun TermsField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  imeAction: ImeAction,
  onDone: () -> Unit = {},
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    singleLine = true,
    keyboardOptions =
      KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = imeAction),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Preview(showBackground = true)
@Composable
private fun FilterEditorPreview() {
  val calendar =
    TtCalendar(
      id = 1,
      name = "Family",
      labels =
        listOf(
          TtLabel(id = 1, name = "", color = LabelColor.EMERALD_GREEN.rgb.toLong(), order = 2),
          TtLabel(id = 2, name = "Work", color = 0x123456, order = 1),
          TtLabel(id = 3, name = "Sports", color = LabelColor.APPLE_RED.rgb.toLong(), order = 3),
        ),
      users =
        listOf(
          TtCalendarUser(userId = 10, name = "Alex"),
          TtCalendarUser(userId = 11, name = "Sam"),
          TtCalendarUser(userId = 12, name = "Kim", deactivatedAt = 5),
        ),
    )
  TTCalendarSyncTheme {
    FilterEditorContent(
      state =
        FilterEditorState.of(
          calendar,
          EventFilter(labelIds = setOf(2), attendeeIds = setOf(12), excludeTerms = listOf("draft")),
        ),
      actions = FilterEditorActions({}, {}, {}, {}, {}, {}),
    )
  }
}
