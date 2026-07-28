package com.wisense.resident.presentation;

import android.content.Context;
import com.wisense.resident.data.ble.BleRepository;
import com.wisense.resident.data.capture.EmergencyCaptureController;
import com.wisense.resident.data.settings.SettingsStore;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class MainViewModel_Factory implements Factory<MainViewModel> {
  private final Provider<BleRepository> bleRepositoryProvider;

  private final Provider<SettingsStore> settingsStoreProvider;

  private final Provider<EmergencyCaptureController> captureControllerProvider;

  private final Provider<Context> contextProvider;

  public MainViewModel_Factory(Provider<BleRepository> bleRepositoryProvider,
      Provider<SettingsStore> settingsStoreProvider,
      Provider<EmergencyCaptureController> captureControllerProvider,
      Provider<Context> contextProvider) {
    this.bleRepositoryProvider = bleRepositoryProvider;
    this.settingsStoreProvider = settingsStoreProvider;
    this.captureControllerProvider = captureControllerProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public MainViewModel get() {
    return newInstance(bleRepositoryProvider.get(), settingsStoreProvider.get(), captureControllerProvider.get(), contextProvider.get());
  }

  public static MainViewModel_Factory create(Provider<BleRepository> bleRepositoryProvider,
      Provider<SettingsStore> settingsStoreProvider,
      Provider<EmergencyCaptureController> captureControllerProvider,
      Provider<Context> contextProvider) {
    return new MainViewModel_Factory(bleRepositoryProvider, settingsStoreProvider, captureControllerProvider, contextProvider);
  }

  public static MainViewModel newInstance(BleRepository bleRepository, SettingsStore settingsStore,
      EmergencyCaptureController captureController, Context context) {
    return new MainViewModel(bleRepository, settingsStore, captureController, context);
  }
}
