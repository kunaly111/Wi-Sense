package com.wisense.resident.data.ble;

import com.wisense.resident.data.capture.EmergencyCaptureController;
import com.wisense.resident.data.settings.SettingsStore;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;

@QualifierMetadata
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
public final class BleMonitorService_MembersInjector implements MembersInjector<BleMonitorService> {
  private final Provider<BleRepository> bleRepositoryProvider;

  private final Provider<SettingsStore> settingsStoreProvider;

  private final Provider<EmergencyCaptureController> captureControllerProvider;

  public BleMonitorService_MembersInjector(Provider<BleRepository> bleRepositoryProvider,
      Provider<SettingsStore> settingsStoreProvider,
      Provider<EmergencyCaptureController> captureControllerProvider) {
    this.bleRepositoryProvider = bleRepositoryProvider;
    this.settingsStoreProvider = settingsStoreProvider;
    this.captureControllerProvider = captureControllerProvider;
  }

  public static MembersInjector<BleMonitorService> create(
      Provider<BleRepository> bleRepositoryProvider, Provider<SettingsStore> settingsStoreProvider,
      Provider<EmergencyCaptureController> captureControllerProvider) {
    return new BleMonitorService_MembersInjector(bleRepositoryProvider, settingsStoreProvider, captureControllerProvider);
  }

  @Override
  public void injectMembers(BleMonitorService instance) {
    injectBleRepository(instance, bleRepositoryProvider.get());
    injectSettingsStore(instance, settingsStoreProvider.get());
    injectCaptureController(instance, captureControllerProvider.get());
  }

  @InjectedFieldSignature("com.wisense.resident.data.ble.BleMonitorService.bleRepository")
  public static void injectBleRepository(BleMonitorService instance, BleRepository bleRepository) {
    instance.bleRepository = bleRepository;
  }

  @InjectedFieldSignature("com.wisense.resident.data.ble.BleMonitorService.settingsStore")
  public static void injectSettingsStore(BleMonitorService instance, SettingsStore settingsStore) {
    instance.settingsStore = settingsStore;
  }

  @InjectedFieldSignature("com.wisense.resident.data.ble.BleMonitorService.captureController")
  public static void injectCaptureController(BleMonitorService instance,
      EmergencyCaptureController captureController) {
    instance.captureController = captureController;
  }
}
