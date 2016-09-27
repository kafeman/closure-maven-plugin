package com.google.closure.plugin.proto;

import java.io.File;
import java.util.Arrays;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.google.closure.plugin.common.FileExt;
import com.google.closure.plugin.common.SourceOptions;
import com.google.closure.plugin.plan.PlanContext;

/**
 * Options for protoc.
 */
public final class ProtoOptions extends SourceOptions {
  private static final long serialVersionUID = -5667643473298285485L;

  /**
   * Protobuf version to compile schema files for.  If omitted,
   * version is inferred from the project's depended-on
   * com.google.com:protobuf-java artifact, if any.  (If both are
   * present, the version must match.)
   */
  public String protobufVersion;

  /**
   * Path to existing protoc to use.  Overrides auto-detection and
   * use of bundled protoc.
   */
  public File protocExec;

  /**
   * Path of output descriptor set file.
   * @see <a href="https://developers.google.com/protocol-buffers/docs/reference/java/com/google/protobuf/Descriptors">Descriptors</a>
   */
  public File descriptorSetFile;

  /**
   * Path of output descriptor set file or test protos.
   * @see <a href="https://developers.google.com/protocol-buffers/docs/reference/java/com/google/protobuf/Descriptors">Descriptors</a>
   */
  public File testDescriptorSetFile;

  /**
   * Protobuf packages to only compile only to JS.
   * If a package appears on the jsOnly and {@link #javaOnly} lists
   * then it will be compiled to both.
   */
  public String[] jsOnly;

  /**
   * Protobuf packages to only compile only to Java.
   * If a package appears on the {@link #jsOnly} and javaOnly lists
   * then it will be compiled to both.
   */
  public String[] javaOnly;

  @Override
  public ProtoOptions clone() throws CloneNotSupportedException {
    return (ProtoOptions) super.clone();
  }

  @Override
  protected void createLazyDefaults() {
    if (jsOnly == null) {
      jsOnly = new String[0];
    }
    if (javaOnly == null) {
      javaOnly = new String[0];
    }

    // We have Java sources available as Java dependencies of this
    // because it is tightly integrated into soy, but not so for JavaScript.
    jsOnly = ImmutableSet.<String>builder()
        .addAll(Arrays.asList(jsOnly))
        .add("webutil.html.types")
        .build().toArray(jsOnly);
  }

  @Override
  protected ImmutableList<FileExt> sourceExtensions() {
    return ImmutableList.of(FileExt.PROTO);
  }

  ProtoFinalOptions freeze(
      PlanContext context,
      File defaultDescriptorSetFile, File defaultTestDescriptorSetFile) {
    return new ProtoFinalOptions(
        getId(),
        toDirectoryScannerSpec(context),
        Optional.fromNullable(protobufVersion),
        Optional.fromNullable(protocExec),
        (descriptorSetFile != null
         ? descriptorSetFile
         : defaultDescriptorSetFile),
        (testDescriptorSetFile != null
         ? testDescriptorSetFile
         : defaultTestDescriptorSetFile),
        ImmutableSet.copyOf(jsOnly),
        ImmutableSet.copyOf(javaOnly));
  }
}
