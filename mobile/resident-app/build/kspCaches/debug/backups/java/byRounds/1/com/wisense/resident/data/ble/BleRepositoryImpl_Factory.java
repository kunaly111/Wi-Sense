package com.wisense.resident.data.ble;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class BleRepositoryImpl_Factory implements Factory<BleRepositoryImpl> {
  private final Provider<BleConnectionManager> managerProvider;

  public BleRepositoryImpl_Factory(Provider<BleConnectionManager> managerProvider) {
    this.managerProvider = managerProvider;
  }

  @Override
  public BleRepositoryImpl get() {
    return newInstance(managerProvider.get());
  }

  public static BleRepositoryImpl_Factory create(Provider<BleConnectionManager> managerProvider) {
    return new BleRepositoryImpl_Factory(managerProvider);
  }

  public static BleRepositoryImpl newInstance(BleConnectionManager manager) {
    return new BleRepositoryImpl(manager);
  }
}
