package io.github.einekratzekatze.maps.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.github.einekratzekatze.maps.map.PageScript

/** Declares the (possibly empty) set features add their [PageScript]s to with `@IntoSet`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PageScriptModule {
    @Multibinds abstract fun pageScripts(): Set<PageScript>
}
