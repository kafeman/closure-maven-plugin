package com.google.common.html.plugin.proto;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.html.plugin.common.Ingredients.Bundle;
import com.google.common.html.plugin.common.Ingredients
    .DirScanFileSetIngredient;
import com.google.common.html.plugin.common.Ingredients.FileIngredient;
import com.google.common.html.plugin.common.Ingredients.FileSetIngredient;
import com.google.common.html.plugin.common.Ingredients.OptionsIngredient;
import com.google.common.html.plugin.common.Ingredients.PathValue;
import com.google.common.html.plugin.common.Sources.Source;
import com.google.common.html.plugin.common.ProcessRunner;
import com.google.common.html.plugin.common.SourceFileProperty;
import com.google.common.html.plugin.common.TypedFile;
import com.google.common.html.plugin.plan.Ingredient;
import com.google.common.html.plugin.plan.PlanKey;
import com.google.common.html.plugin.plan.Step;
import com.google.common.html.plugin.plan.StepSource;

final class RunProtoc extends Step {
  final ProcessRunner processRunner;
  final RunProtoc.RootSet rootSet;

  RunProtoc(
      ProcessRunner processRunner,
      RunProtoc.RootSet rootSet,
      OptionsIngredient<ProtoOptions> options,
      DirScanFileSetIngredient protoSources,
      FileSetIngredient protocSet,
      Bundle<FileIngredient> inputs,
      PathValue javaGenfilesPath,
      PathValue jsGenfilesPath,
      PathValue descriptorSetFile) {
    super(
        PlanKey.builder("proto-to-java")
            .addInp(options, protoSources)
            .addString(rootSet.name())
            .build(),
        ImmutableList.<Ingredient>builder()
            .add(options, protoSources, protocSet,
                 javaGenfilesPath, jsGenfilesPath, descriptorSetFile, inputs)
            .build(),
        Sets.immutableEnumSet(
            StepSource.PROTO_SRC, StepSource.PROTO_GENERATED,
            StepSource.PROTOC),
        Sets.immutableEnumSet(
            StepSource.PROTO_DESCRIPTOR_SET,
            // Compiles .proto to .js
            StepSource.JS_GENERATED));
    this.processRunner = processRunner;
    this.rootSet = Preconditions.checkNotNull(rootSet);
  }

  @Override
  public void execute(Log log) throws MojoExecutionException {
    @SuppressWarnings("unused")
    OptionsIngredient<ProtoOptions> options =
        ((OptionsIngredient<?>) inputs.get(0)).asSuperType(ProtoOptions.class);
    DirScanFileSetIngredient protoSources =
        (DirScanFileSetIngredient) inputs.get(1);
    FileSetIngredient protocSet = (FileSetIngredient) inputs.get(2);
    PathValue javaGenfilesPath = (PathValue) inputs.get(3);
    PathValue jsGenfilesPath = (PathValue) inputs.get(4);
    PathValue descriptorSetFile = (PathValue) inputs.get(5);
    Bundle<FileIngredient> inputFileBundle = ((Bundle<?>) inputs.get(6))
        .asSuperType(FileIngredient.class);

    ImmutableList<FileIngredient> sources = inputFileBundle.ings;

    if (Iterables.isEmpty(sources)) {
      // We're done.
      // TODO: Is it a problem that we will not generate
      // an empty descriptor set file?
      return;
    }

    ImmutableList<FileIngredient> protocs = protocSet.sources();
    if (protocs.isEmpty()) {
      throw new MojoExecutionException(
          "No protoc executable found."
          + "  Maybe specify <configuration><proto><protocExecutable>..."
          + " or make sure you have a dependency on protobuf-java");
    }
    Source protoc = protocs.get(0).source;

    ImmutableList.Builder<String> argv = ImmutableList.builder();
    argv.add(protoc.canonicalPath.getPath());

    argv.add("--include_imports")
        .add("--descriptor_set_out")
        .add(descriptorSetFile.value.getPath());
    File descriptorSetDir = descriptorSetFile.value.getParentFile();
    if (descriptorSetDir != null) {
      descriptorSetDir.mkdirs();
    }

    // Protoc is a little finicky about requiring that output directories
    // exist, though it will happily create directories for the packages.
    argv.add("--java_out").add(ensureDirExists(javaGenfilesPath.value));
    argv.add("--js_out").add(ensureDirExists(jsGenfilesPath.value));

    // Build a proto search path.
    Set<File> roots = Sets.newLinkedHashSet();
    for (TypedFile root : protoSources.spec().roots) {
      if ((rootSet == RootSet.TEST
           || !root.ps.contains(SourceFileProperty.TEST_ONLY))
          && roots.add(root.f)
          && root.f.exists()) {
        argv.add("--proto_path").add(root.f.getPath());
      }
    }

    for (FileIngredient input : sources) {
      TypedFile root = input.source.root;
      if (roots.add(root.f) && root.f.exists()) {
        argv.add("--proto_path").add(root.f.getPath());
        // We're not guarding against ambiguity here.
        // We warn on it below.
      }
    }

    // Check for obvious sources of ambiguity due to two inputs with the
    // same relative path.  We pass absolute paths to protoc, but the
    // paths resolved by `import "<relative-path>";` directives are still
    // a potential source of ambiguity.
    Map<File, Source> relPathToSource = Maps.newHashMap();
    for (FileIngredient input : sources) {
      Source inputSource = input.source;
      Source ambig = relPathToSource.put(inputSource.relativePath, inputSource);
      if (ambig == null) {
        argv.add(
            // Instead of using canonicalPath, we concat these two paths
            // because protoc insists that each input appear under a
            // search path element as determined by string comparison.
            FilenameUtils.concat(
                inputSource.root.f.getPath(),
                inputSource.relativePath.getPath()));
      } else {
        log.warn(
            "Ambiguous proto input " + inputSource.relativePath
            + " appears on search path twice: "
            + ambig.root + " and " + inputSource.root);
      }
    }

    Future<Integer> exitCodeFuture = processRunner.run(
        log, "protoc", argv.build());
    try {
      Integer exitCode = exitCodeFuture.get(30, TimeUnit.SECONDS);

      if (exitCode.intValue() != 0) {
        throw new MojoExecutionException(
            "protoc execution failed with exit code " + exitCode);
      }
    } catch (TimeoutException ex) {
      throw new MojoExecutionException("protoc execution timed out", ex);
    } catch (InterruptedException ex) {
      throw new MojoExecutionException("protoc execution was interrupted", ex);
    } catch (ExecutionException ex) {
      throw new MojoExecutionException("protoc execution failed", ex);
    } catch (CancellationException ex) {
      throw new MojoExecutionException("protoc execution was cancelled", ex);
    }
  }

  private static String ensureDirExists(File dirPath) {
    dirPath.mkdirs();
    return dirPath.getPath();
  }

  @Override
  public void skip(Log log) {
    // Ok.
  }

  @Override
  public ImmutableList<Step> extraSteps(Log log) throws MojoExecutionException {
    return ImmutableList.of();
  }

  enum RootSet {
    MAIN,
    TEST,
    ;
  }
}
