package io.github.einekratzekatze.maps.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A scope that lives as long as the map's retained state (survives rotation, not finish). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class RetainedScope

@Module
@InstallIn(ActivityRetainedComponent::class)
object RetainedScopeModule {
    @Provides @ActivityRetainedScoped @RetainedScope
    fun scope(lifecycle: ActivityRetainedLifecycle): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            lifecycle.addOnClearedListener { s.cancel() }
        }
}
