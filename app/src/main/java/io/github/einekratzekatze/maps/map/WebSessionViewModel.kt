package io.github.einekratzekatze.maps.map

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Tells a configuration-change recreation apart from a real launch (compose-host.md §2.3).
 * ViewModels survive configuration changes but not process death, so [started] is false on the
 * first launch and after process death, and true on a recreation.
 */
@HiltViewModel
class WebSessionViewModel @Inject constructor() : ViewModel() {
    var started = false

    /** The page to reload after a recreation. 1.3b replaces this with `MapStateRepository.url`. */
    var lastUrl: String? = null
}
