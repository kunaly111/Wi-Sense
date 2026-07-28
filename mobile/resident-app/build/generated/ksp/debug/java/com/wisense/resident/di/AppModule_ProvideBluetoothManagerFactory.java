package com.wisense.resident.di;

import android.bluetooth.BluetoothManager;
import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class AppModule_ProvideBluetoothManagerFactory implements Factory<BluetoothManager> {
  private final Provider<Context> contextProvider;

  public AppModule_ProvideBluetoothManagerFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public BluetoothManager get() {
    return provideBluetoothManager(contextProvider.get());
  }

  public static AppModule_ProvideBluetoothManagerFactory create(Provider<Context> contextProvider) {
    return new AppModule_ProvideBluetoothManagerFactory(contextProvider);
  }

  public static BluetoothManager provideBluetoothManager(Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideBluetoothManager(context));
  }
}
