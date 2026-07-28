package com.wisense.resident.di;

import android.content.Context;
import com.wisense.resident.data.capture.EmergencyCaptureController;
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
public final class AppModule_ProvideEmergencyCaptureControllerFactory implements Factory<EmergencyCaptureController> {
  private final Provider<Context> contextProvider;

  public AppModule_ProvideEmergencyCaptureControllerFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public EmergencyCaptureController get() {
    return provideEmergencyCaptureController(contextProvider.get());
  }

  public static AppModule_ProvideEmergencyCaptureControllerFactory create(
      Provider<Context> contextProvider) {
    return new AppModule_ProvideEmergencyCaptureControllerFactory(contextProvider);
  }

  public static EmergencyCaptureController provideEmergencyCaptureController(Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideEmergencyCaptureController(context));
  }
}
