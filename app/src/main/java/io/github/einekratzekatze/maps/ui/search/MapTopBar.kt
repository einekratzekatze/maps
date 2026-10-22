package io.github.einekratzekatze.maps.ui.search

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.einekratzekatze.maps.R
import io.github.einekratzekatze.maps.domain.MapMode

/** Callbacks of the top bar and the suggestion panel; [SearchViewModel] provides them. */
interface SearchActions {
    fun onFocusChanged(focused: Boolean)
    fun onQueryChange(q: String)
    fun onSubmit()
    fun onMenuClick()
    /** Directions title back arrow: leaves the directions page. */
    fun onDirectionsBack()
}

/**
 * Phase 1.1 top bar with our search field (custom-search-field.md §1, §3). Directions shows a
 * back/close title instead; Street View and non-map pages show no content.
 * When the VM ends editing, this clears focus and hides the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapTopBar(state: SearchUiState, actions: SearchActions, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.editing) {
        if (!state.editing) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    when (state.mode) {
        MapMode.Map, MapMode.Search, MapMode.Place -> TopAppBar(
            modifier = modifier,
            navigationIcon = {
                IconButton(onClick = actions::onMenuClick, modifier = Modifier.testTag(SearchTestTags.Menu)) {
                    Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.search_menu))
                }
            },
            title = { SearchField(state, actions) },
        )
        MapMode.Directions -> TopAppBar(
            modifier = modifier,
            navigationIcon = {
                IconButton(onClick = actions::onDirectionsBack, modifier = Modifier.testTag(SearchTestTags.DirectionsBack)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.search_back_to_map))
                }
            },
            title = { Text(stringResource(R.string.search_directions)) },
        )
        MapMode.StreetView, MapMode.NonMap -> Unit
    }
}

@Composable
private fun SearchField(state: SearchUiState, actions: SearchActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        TextField(
            value = state.query,
            onValueChange = actions::onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onSubmit() }),
            trailingIcon = if (state.editing && state.query.isNotEmpty()) {
                {
                    IconButton(onClick = { actions.onQueryChange("") }, modifier = Modifier.testTag(SearchTestTags.Clear)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                    }
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { actions.onFocusChanged(it.isFocused) }
                .testTag(SearchTestTags.Field),
        )
    }
}
