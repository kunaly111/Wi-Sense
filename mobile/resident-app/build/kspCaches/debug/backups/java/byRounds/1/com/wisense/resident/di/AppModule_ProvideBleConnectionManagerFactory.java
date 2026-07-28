package com.wisense.resident.di;

import android.bluetooth.BluetoothManager;
import android.content.Context;
import com.wisense.resident.data.ble.BleConnectionManager;
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
public final class AppModule_ProvideBleConnectionManagerFactory implements Factory<BleConnectionManager> {
  private final Provider<Context> contextProvider;

  private final Provider<BluetoothManager> bluetoothManagerProvider;

  public AppModule_ProvideBleConnectionManagerFactory(Provider<Context> contextProvider,
      Provider<BluetoothManager> bluetoothManagerProvider) {
    this.contextProvider = contextProvider;
    this.bluetoothManagerProvider = bluetoothManagerProvider;
  }

  @Override
  public BleConnectionManager get() {
    return provideBleConnectionManager(contextProvider.get(), bluetoothManagerProvider.get());
  }

  public static AppModule_ProvideBleConnectionManagerFactory create(
      Provider<Context> contextProvider, Provider<BluetoothManager> bluetoothManagerProvider) {
    return new AppModule_ProvideBleConnectionManagerFactory(contextProvider, bluetoothManagerProvider);
  }

  public static BleConnectionManager provideBleConnectionManager(Context context,
      BluetoothManager bluetoothManager) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideBleConnectionManager(context, bluetoothManager));
  }
}
