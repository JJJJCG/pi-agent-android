package com.pi.assistant.di

import android.content.Context
import androidx.room.Room
import com.pi.assistant.data.local.ChatDatabase
import com.pi.assistant.data.local.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, ChatDatabase.NAME)
            // M1 阶段表结构还会动，先不用迁移，直接重建
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMessageDao(db: ChatDatabase): MessageDao = db.messageDao()
}
