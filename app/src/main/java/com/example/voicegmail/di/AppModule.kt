package com.example.voicegmail.di

import com.example.voicegmail.data.GmailRepository
import com.example.voicegmail.data.GoogleGmailRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that wires interface bindings for the application scope.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindGmailRepository(impl: GoogleGmailRepository): GmailRepository
}
