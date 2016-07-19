package com.google.common.html.plugin.proto;

import java.io.File;
import java.io.IOException;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.html.plugin.common.CommonPlanner;
import com.google.common.html.plugin.common.Ingredients.OptionsIngredient;
import com.google.common.html.plugin.common.Ingredients.SerializedObjectIngredient;
import com.google.common.html.plugin.common.Ingredients.SettableFileSetIngredient;

/**
 * Adds steps that feed .proto files to protoc.
 */
public final class ProtoPlanner {

  private final CommonPlanner planner;
  private final Function<ProtoOptions, File> protocExecSupplier;
  private File defaultProtoSource;
  private File defaultProtoTestSource;
  private File defaultMainDescriptorFile;
  private File defaultTestDescriptorFile;

  /** ctor */
  public ProtoPlanner(
      CommonPlanner planner, Function<ProtoOptions, File> protocExecSupplier) {
    this.planner = planner;
    this.protocExecSupplier = protocExecSupplier;
  }

  /** Sets the default source root for proto files used in sources. */
  public ProtoPlanner defaultProtoSource(File dir) {
    this.defaultProtoSource = dir;
    return this;
  }

  /** Sets the default source root for proto files used in tests. */
  public ProtoPlanner defaultProtoTestSource(File dir) {
    this.defaultProtoTestSource = dir;
    return this;
  }

  /** Path for generated proto descriptor file set. */
  public ProtoPlanner defaultMainDescriptorFile(File f) {
    this.defaultMainDescriptorFile = f;
    return this;
  }

  /** Path for generated proto descriptor file set for test protos. */
  public ProtoPlanner defaultTestDescriptorFile(File f) {
    this.defaultTestDescriptorFile = f;
    return this;
  }

  /** Adds steps to the common planner. */
  public void plan(ProtoOptions opts)
      throws IOException {
    Preconditions.checkNotNull(defaultProtoSource);
    Preconditions.checkNotNull(defaultProtoTestSource);
    Preconditions.checkNotNull(defaultMainDescriptorFile);
    Preconditions.checkNotNull(defaultTestDescriptorFile);
    Preconditions.checkNotNull(opts);

    File protoDir = new File(planner.outputDir, "proto");

    OptionsIngredient<ProtoOptions> protoOptions =
        planner.ingredients.options(ProtoOptions.class, opts);

    SerializedObjectIngredient<ProtocSpec> protoSpec =
        planner.ingredients.serializedObject(
            new File(protoDir, "protoc-files.ser"),
            ProtocSpec.class);

    SettableFileSetIngredient protocExec =
        planner.ingredients.namedFileSet("protocExec");

    planner.addStep(new FindProtoFilesAndProtoc(
        protocExecSupplier, planner.ingredients,
        protoOptions,
        planner.genfiles,
        planner.ingredients.stringValue(defaultProtoSource.getPath()),
        planner.ingredients.stringValue(defaultProtoTestSource.getPath()),
        planner.ingredients.stringValue(defaultMainDescriptorFile.getPath()),
        planner.ingredients.stringValue(defaultTestDescriptorFile.getPath()),
        protoSpec, protocExec));
  }

}
