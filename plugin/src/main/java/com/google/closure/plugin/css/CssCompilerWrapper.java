package com.google.closure.plugin.css;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.logging.Log;
import org.sonatype.plexus.build.incremental.BuildContext;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.css.ExitCodeHandler;
import com.google.common.css.JobDescription;
import com.google.common.css.SubstitutionMapProvider;
import com.google.common.css.compiler.ast.ErrorManager;
import com.google.common.css.compiler.ast.GssError;
import com.google.common.css.compiler.commandline.ClosureCommandLineCompiler;
import com.google.closure.plugin.common.Sources;
import com.google.common.io.Files;

final class CssCompilerWrapper {
  private CssOptions cssOptions = new CssOptions();
  private ImmutableList<Sources.Source> inputs = ImmutableList.of();
  private Optional<File> outputFile = Optional.absent();
  private Optional<File> renameFile = Optional.absent();
  private Optional<File> sourceMapFile = Optional.absent();
  private SubstitutionMapProvider substitutionMapProvider;

  CssCompilerWrapper cssOptions(CssOptions newCssOptions) {
    this.cssOptions = newCssOptions;
    return this;
  }
  CssCompilerWrapper inputs(Iterable<? extends Sources.Source> newInputs) {
    this.inputs = ImmutableList.copyOf(newInputs);
    return this;
  }
  CssCompilerWrapper outputFile(File newOutputFile) {
    this.outputFile = Optional.of(newOutputFile);
    return this;
  }
  CssCompilerWrapper renameFile(File newRenameFile) {
    this.renameFile = Optional.of(newRenameFile);
    return this;
  }
  CssCompilerWrapper substitutionMapProvider(
      SubstitutionMapProvider newSubstitutionMapProvider) {
    this.substitutionMapProvider = newSubstitutionMapProvider;
    return this;
  }
  CssCompilerWrapper sourceMapFile(File newSourceMapFile) {
    this.sourceMapFile = Optional.of(newSourceMapFile);
    return this;
  }

  boolean compileCss(final BuildContext buildContext, Log log)
  throws IOException {
    if (inputs.isEmpty()) {
      log.info("No CSS files to compile");
      return true;
    }
    log.info("Compiling " + inputs.size() + " CSS files" +
        (outputFile.isPresent() ? " to " + outputFile.get().getPath() : ""));

    JobDescription job = cssOptions.getJobDescription(
        log, inputs, substitutionMapProvider);

    final class OkUnlessNonzeroExitCodeHandler implements ExitCodeHandler {
      boolean ok = true;

      @Override
      public void processExitCode(int exitCode) {
        if (exitCode != 0) {
          ok = false;
        }
      }
    }

    OkUnlessNonzeroExitCodeHandler exitCodeHandler =
        new OkUnlessNonzeroExitCodeHandler();

    ErrorManager errorManager = new MavenCssErrorManager(buildContext);

    ensureParentDirectoryFor(sourceMapFile);
    ensureParentDirectoryFor(renameFile);
    ensureParentDirectoryFor(outputFile);

    String compiledCss =
        new ClosureCommandLineCompiler(job, exitCodeHandler, errorManager) {
          @Override
          public String execute(File renameOutFile, File sourceMapOutFile) {
            return super.execute(renameOutFile, sourceMapOutFile);
          }
        }
        .execute(renameFile.orNull(), sourceMapFile.orNull());
    if (compiledCss == null) {
      return false;
    }
    if (outputFile.isPresent()) {
      Files.write(compiledCss, outputFile.get(), Charsets.UTF_8);
    }
    return exitCodeHandler.ok;
  }

  private static void ensureParentDirectoryFor(Optional<File> file)
  throws IOException {
    if (file.isPresent()) {
      Files.createParentDirs(file.get().getCanonicalFile());
    }
  }
}

final class MavenCssErrorManager implements ErrorManager {
  private boolean hasErrors;
  private final BuildContext buildContext;

  MavenCssErrorManager(BuildContext buildContext) {
    this.buildContext = buildContext;
  }

  @Override
  public void report(GssError error) {
    hasErrors = true;
    buildContext.addMessage(
        new File(error.getLocation().getSourceCode().getFileName()),
        error.getLocation().getBeginLineNumber(),
        error.getLocation().getBeginIndexInLine(),
        error.getMessage(),
        BuildContext.SEVERITY_ERROR,
        null);
  }

  @Override
  public void reportWarning(GssError warning) {
    buildContext.addMessage(
        new File(warning.getLocation().getSourceCode().getFileName()),
        warning.getLocation().getBeginLineNumber(),
        warning.getLocation().getBeginIndexInLine(),
        warning.getMessage(),
        BuildContext.SEVERITY_WARNING,
        null);
  }

  @Override
  public void generateReport() {
    // Errors reported eagerly.
    // TODO(summarize something)?
  }

  @Override
  public boolean hasErrors() {
    return hasErrors;
  }
}
