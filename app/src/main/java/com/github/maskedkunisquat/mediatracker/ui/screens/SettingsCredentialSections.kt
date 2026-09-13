package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.hub.media.ui.SettingsUiState

/*
 * The two provider-credential sections of the Settings screen, lifted out of `SettingsScreen.kt`
 * (#81) as the fourth cut.
 *
 * The row itself was already generalised over its strings and test tag (#75), so what was left at
 * the call sites was twelve resource arguments apiece — the bulk of which said nothing except which
 * provider this was. Each section now names its provider once and supplies the rest itself, which is
 * what makes the screen's own body readable at a glance.
 *
 * Only the two `*Section` composables are visible outside this file.
 */

/**
 * The Book lookups section: the Google Books credential.
 *
 * Its own section rather than a row under "Data": this is about how books are looked up when they
 * are added, not about moving data in and out of the app.
 *
 * @param credentialSet Whether a key is currently stored.
 * @param onSave Called with the entered key.
 * @param onClear Called when the stored key is cleared.
 */
@Composable
internal fun BookLookupsSection(
    credentialSet: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_book_lookups)) {
        ProviderCredentialSetting(
            labelRes = R.string.settings_google_books_key_label,
            descriptionRes = R.string.settings_google_books_key_description,
            savedRes = R.string.settings_google_books_key_saved,
            notSavedRes = R.string.settings_google_books_key_not_saved,
            fieldLabelRes = R.string.settings_google_books_key_field_label,
            saveButtonRes = R.string.settings_google_books_key_save_button,
            replaceButtonRes = R.string.settings_google_books_key_replace_button,
            clearButtonRes = R.string.settings_google_books_key_clear_button,
            fieldTestTag = TestTags.Settings.API_KEY_FIELD,
            credentialSet = credentialSet,
            onSave = onSave,
            onClear = onClear,
        )
    }
}

/**
 * The Film & TV lookups section: the TMDB credential, and the only one with a "test this" button.
 *
 * Its own section rather than sharing "Book lookups", which is what the first recording of this
 * screen's golden actually showed: a TMDB credential filed under a heading that says books. The tag
 * assertion could not see that -- the control was present and correct, under the wrong words. This
 * is the case #102 rule 3 exists for.
 *
 * @param credentialSet Whether a credential is currently stored.
 * @param onSave Called with the entered credential.
 * @param onClear Called when the stored credential is cleared.
 * @param onTest Called when the "test this" button is tapped.
 */
@Composable
internal fun FilmAndTvLookupsSection(
    credentialSet: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_film_tv_lookups)) {
        ProviderCredentialSetting(
            labelRes = R.string.settings_tmdb_key_label,
            descriptionRes = R.string.settings_tmdb_key_description,
            savedRes = R.string.settings_tmdb_key_saved,
            notSavedRes = R.string.settings_tmdb_key_not_saved,
            fieldLabelRes = R.string.settings_tmdb_key_field_label,
            saveButtonRes = R.string.settings_tmdb_key_save_button,
            replaceButtonRes = R.string.settings_tmdb_key_replace_button,
            clearButtonRes = R.string.settings_tmdb_key_clear_button,
            fieldTestTag = TestTags.Settings.TMDB_KEY_FIELD,
            credentialSet = credentialSet,
            onSave = onSave,
            onClear = onClear,
            testAction =
                CredentialTestAction(
                    labelRes = R.string.settings_tmdb_key_test_button,
                    onTest = onTest,
                ),
        )
    }
}

/**
 * A credential row's optional "test this" button: its label and what pressing it does.
 *
 * One nullable parameter rather than two, because two independently-nullable ones can disagree: a
 * caller passing the action and forgetting the label gets no button and no complaint. That is the
 * failure shape this screen has already produced twice on this branch -- a control present but
 * wrong, and silent about it -- so the type makes half-specifying it unrepresentable.
 */
private data class CredentialTestAction(
    @StringRes val labelRes: Int,
    val onTest: () -> Unit,
)

/**
 * One provider credential row: label, explanation, saved/not-saved status, a masked field, and
 * Save/Clear.
 *
 * ### Generalised, because there are two of these now
 * This was `GoogleBooksApiKeySetting` until TMDB's credential arrived (#75). Copying ninety lines to
 * get a second one would have been two rows that drift apart -- and credential handling is the worst
 * possible place for a drift, since the half that stops masking its field is not obviously broken to
 * look at. #81 already tracks duplicated layers of exactly this shape, so this takes its strings and
 * test tag as parameters instead.
 *
 * ### The stored credential is never displayed
 * [credentialSet] is a boolean, and this row has no way to read the saved value even if it wanted to
 * (see [SettingsUiState.googleBooksApiKeySet]/[SettingsUiState.tmdbCredentialSet]). A saved
 * credential is reported as saved; the text field always starts empty and holds its own local state,
 * so what it contains is only ever what the user has just typed in this composition. Re-entering a
 * credential to change it is a deliberate cost: echoing one back into an on-screen field, in an app
 * whose Settings screen is a normal, non-authenticated destination, buys nothing but a
 * shoulder-surfing surface.
 *
 * Masked by default with an explicit Show toggle, and [KeyboardType.Password] regardless of that
 * toggle -- which is what keeps the soft keyboard from learning and later suggesting the value, a
 * leak that would outlive the app entirely. Show exists because these are pasted far more often than
 * typed, and a paste you cannot verify is a support problem.
 *
 * @param labelRes Row heading, e.g. "TMDB API key".
 * @param descriptionRes What the credential buys and what happens without it.
 * @param savedRes Status line shown when [credentialSet].
 * @param notSavedRes Status line shown when it is not.
 * @param fieldLabelRes Label on the text field itself.
 * @param saveButtonRes Save button text when no credential is stored yet.
 * @param replaceButtonRes Save button text when one already is -- saving overwrites, and a button
 *   still reading "Save" would hide that.
 * @param clearButtonRes Clear button text.
 * @param fieldTestTag Test tag for the field, so the two rows are separately addressable from
 *   instrumented tests.
 * @param credentialSet Whether a credential is currently stored, driving the status line and whether
 *   Clear is offered at all.
 * @param onSave Called with the trimmed-by-the-repository field contents when Save is tapped.
 * @param onClear Called when Clear is tapped -- offered only when [credentialSet], since clearing
 *   nothing is not an action.
 * @param testAction Optional "check this actually works" affordance, shown only when non-null *and*
 *   a credential is stored. Optional because only TMDB has an endpoint for it: Google Books has no
 *   equivalent, and a button that could only ever report "we tried a book lookup" would be a
 *   different, vaguer promise wearing the same label.
 */
@Composable
private fun ProviderCredentialSetting(
    @StringRes labelRes: Int,
    @StringRes descriptionRes: Int,
    @StringRes savedRes: Int,
    @StringRes notSavedRes: Int,
    @StringRes fieldLabelRes: Int,
    @StringRes saveButtonRes: Int,
    @StringRes replaceButtonRes: Int,
    @StringRes clearButtonRes: Int,
    fieldTestTag: String,
    credentialSet: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    testAction: CredentialTestAction? = null,
) {
    var entered by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (credentialSet) stringResource(savedRes) else stringResource(notSavedRes),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = entered,
            onValueChange = { entered = it },
            label = { Text(stringResource(fieldLabelRes)) },
            singleLine = true,
            visualTransformation =
                if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            // Password even when revealed -- see this composable's KDoc: the point is the keyboard's
            // learning/suggestion behavior, not the on-screen masking, and those are separate.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(
                        text =
                            if (revealed) {
                                stringResource(R.string.settings_google_books_key_hide)
                            } else {
                                stringResource(R.string.settings_google_books_key_show)
                            },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().testTag(fieldTestTag),
        )
        // FlowRow, not Row: three buttons of provider-length labels overflow 1080px at default font
        // scale -- verified on device, where the third simply never rendered and no test noticed,
        // because the golden asserts the *field's* tag rather than the buttons. Two of them already
        // came close enough that a larger font scale would have clipped Clear, so this fixes a
        // latent bug as well as the new one.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = {
                    onSave(entered)
                    // Dropped as soon as it has been handed over: nothing on this screen needs the
                    // credential again, and leaving it sitting in a composition-scoped field would
                    // keep it on screen (and in the recomposition snapshot) for the rest of the
                    // visit for no reason.
                    entered = ""
                    revealed = false
                },
                enabled = entered.isNotBlank(),
            ) {
                Text(text = if (credentialSet) stringResource(replaceButtonRes) else stringResource(saveButtonRes))
            }
            if (credentialSet) {
                OutlinedButton(
                    onClick = {
                        onClear()
                        entered = ""
                        revealed = false
                    },
                ) {
                    Text(stringResource(clearButtonRes))
                }
                // Only offered once something is stored: testing nothing is not an action, and the
                // answer would be a foregone "no credential" rather than anything about the
                // provider.
                if (testAction != null) {
                    TextButton(onClick = testAction.onTest) {
                        Text(stringResource(testAction.labelRes))
                    }
                }
            }
        }
    }
}
